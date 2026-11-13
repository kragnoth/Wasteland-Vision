package com.kragnoth.wastelandvision.client;

import com.kragnoth.wastelandvision.Atmosphere;
import com.kragnoth.wastelandvision.ColorGrade;
import com.kragnoth.wastelandvision.WVConfig;
import com.kragnoth.wastelandvision.WVServerConfig;
import com.kragnoth.wastelandvision.WastelandState;
import com.kragnoth.wastelandvision.vision.Vision;
import com.kragnoth.wastelandvision.vision.VisionManager;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.FogType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

public final class WastelandClient {
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final KeyMapping TOGGLE = new KeyMapping("key.wastelandvision.toggle",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, "key.categories.wastelandvision");
    public static final KeyMapping CYCLE = new KeyMapping("key.wastelandvision.cycle",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.wastelandvision");

    /** Post chains we loaded (so we only ever shut down our own, never a spectator effect). */
    private static final Set<String> ourShaders = new HashSet<>();
    private static final Set<ResourceLocation> failedShaders = new HashSet<>();
    /** Set when models/atlases were rebuilt (F3+T): sprite objects changed, so recompile the vision. */
    private static volatile boolean modelsReloaded = false;

    private WastelandClient() {}

    public static void init(IEventBus modBus) {
        modBus.addListener(WastelandClient::onRegisterKeys);
        modBus.addListener(WastelandClient::onModifyBakingResult);
        modBus.addListener(VisionPacks::onAddPackFinders);
        modBus.addListener(RecolorSource::onRegister);

        NeoForge.EVENT_BUS.addListener(WastelandClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(WastelandClient::onTagsUpdated);
        NeoForge.EVENT_BUS.addListener(WastelandClient::onRenderFog);
        NeoForge.EVENT_BUS.addListener(WastelandClient::onFogColor);
        NeoForge.EVENT_BUS.addListener(WastelandClient::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(WastelandClient::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(Ghosts::onClientTick);
        NeoForge.EVENT_BUS.addListener(Ghosts::onRenderStage);
        NeoForge.EVENT_BUS.addListener(GroveAmbience::onClientTick);
        NeoForge.EVENT_BUS.addListener(SmokeColumns::onRenderStage);
        NeoForge.EVENT_BUS.addListener(SkyOmens::onRenderStage);
    }

    // ------------------------------------------------------------------ mod bus

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE);
        event.register(CYCLE);
    }

    private static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        // Nothing is wrapped up-front (that broke mods that cast their own model classes).
        // Just drop wrappers that point at the old, about-to-be-discarded models.
        WastelandModel.clearCache();
        modelsReloaded = true;
    }

    // ------------------------------------------------------------------ vision selection

    // ------------------------------------------------------------------ server lock (forceVision)

    /** The server's forced vision id while forceVision is on and we're in a world, else null. */
    @Nullable
    public static String forcedVisionId() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !WVServerConfig.SPEC.isLoaded()) return null;
        try {
            return WVServerConfig.FORCE_VISION.get() ? WVServerConfig.FORCED_VISION.get() : null;
        } catch (IllegalStateException e) {
            return null; // server config not synced yet
        }
    }

    /** Creative/spectator (if allowed) or a high enough permission level escapes the lock. */
    public static boolean canBypassLock(Minecraft mc) {
        if (mc.player == null) return false;
        try {
            if (WVServerConfig.CREATIVE_BYPASS.get() && (mc.player.isCreative() || mc.player.isSpectator())) return true;
            return mc.player.hasPermissions(WVServerConfig.BYPASS_PERMISSION_LEVEL.get());
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** True while the server lock applies to this player. */
    public static boolean locked(Minecraft mc) {
        return forcedVisionId() != null && !canBypassLock(mc);
    }

    private static boolean lockWarned = false;

    /**
     * Keeps a locked player in the forced vision every tick: turns it on at join, and back on (and back
     * to the forced vision) if they lose creative/op after switching it off or cycling away.
     */
    private static void enforceLock(Minecraft mc) {
        String id = forcedVisionId();
        if (id == null || canBypassLock(mc)) return;
        Vision forced = VisionManager.get(id);
        if (forced == null) {
            if (!lockWarned) {
                lockWarned = true;
                LOGGER.warn("Wasteland Vision: server forces vision '{}' but this client has no such vision folder", id);
            }
            return;
        }
        if (WastelandState.active && id.equals(WastelandState.visionId)) return;
        WastelandState.active = true;
        if (compileSelected()) {
            mc.levelRenderer.allChanged();
        } else {
            WastelandState.active = false;
        }
    }

    /** The configured vision (or the server's forced one while locked), or the first one available. */
    @Nullable
    public static Vision selectedVision() {
        Minecraft mc = Minecraft.getInstance();
        if (locked(mc)) {
            Vision f = VisionManager.get(forcedVisionId());
            if (f != null) return f;
        }
        Vision v = VisionManager.get(WVConfig.ACTIVE_VISION.get());
        if (v == null && !VisionManager.all().isEmpty()) v = VisionManager.all().get(0);
        return v;
    }

    /**
     * Re-reads the selected vision's vision.json, then compiles its rules + atmosphere.
     * Needs tags (i.e. a loaded world). Runs on toggle-on, cycle, tag sync and after F3+T.
     */
    private static boolean compileSelected() {
        Vision v = VisionManager.reload(selectedVision());
        if (v == null) {
            LOGGER.warn("Wasteland Vision: no visions found in {}", VisionManager.visionsDir());
            return false;
        }
        String err = VisionManager.lastReloadError();
        Minecraft mc = Minecraft.getInstance();
        if (err != null && mc.player != null) {
            mc.player.displayClientMessage(Component.literal(
                    "Wasteland Vision: vision.json error (" + err + ") - using the last good version, see log"), false);
        }
        Atmosphere.load(v.json().has("atmosphere") ? v.json().getAsJsonObject("atmosphere") : null);
        SubstitutionTable.rebuild(v);
        Ghosts.load(v.json().has("ghosts") ? v.json().getAsJsonObject("ghosts") : null);
        GroveAmbience.load(v.json().has("ambience") ? v.json().getAsJsonObject("ambience") : null);
        SmokeColumns.load(v.json().has("smoke_columns") ? v.json().getAsJsonObject("smoke_columns") : null);
        SkyOmens.load(v.json().has("sky_omens") ? v.json().getAsJsonObject("sky_omens") : null);
        return true;
    }

    // ------------------------------------------------------------------ game bus

    private static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED) return;
        Minecraft.getInstance().execute(() -> {
            if (compileSelected() && WastelandState.active) Minecraft.getInstance().levelRenderer.allChanged();
        });
    }

    private static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (WVConfig.START_ACTIVE.get()) WastelandState.active = true;
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        failedShaders.clear();
        lockWarned = false;
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        WastelandState.setMeshing(false); // safety net: a sync rebuild that threw can't leave the render thread "meshing"
        checkSodiumHook(mc);
        while (TOGGLE.consumeClick()) toggle(mc);
        while (CYCLE.consumeClick()) cycle(mc);
        if (mc.level != null) {
            enforceLock(mc);
            if (modelsReloaded) {
                modelsReloaded = false;
                // retexture caches hold sprites from the old atlas; tags are still valid
                if (compileSelected() && WastelandState.active) mc.levelRenderer.allChanged();
            }
            maintainShader(mc);
        }
    }

    public static void toggle(Minecraft mc) {
        if (locked(mc)) {
            if (mc.player != null) mc.player.displayClientMessage(Component.translatable("message.wastelandvision.locked"), true);
            return;
        }
        WastelandState.active = !WastelandState.active;
        if (WastelandState.active && !compileSelected()) {
            WastelandState.active = false;
            return;
        }
        mc.levelRenderer.allChanged(); // full re-mesh (same as F3+A); also clears biome tint caches
        if (mc.player != null) {
            Vision v = selectedVision();
            mc.player.displayClientMessage(WastelandState.active
                    ? Component.translatable("message.wastelandvision.on", v != null ? v.displayName() : "?")
                    : Component.translatable("message.wastelandvision.off"), true);
        }
    }

    /** Switches to the next vision folder (alphabetical) and saves it as activeVision. */
    public static void cycle(Minecraft mc) {
        if (locked(mc)) {
            if (mc.player != null) mc.player.displayClientMessage(Component.translatable("message.wastelandvision.locked"), true);
            return;
        }
        List<Vision> all = VisionManager.all();
        if (all.isEmpty()) return;
        Vision cur = selectedVision();
        int i = cur == null ? -1 : all.indexOf(cur);
        Vision next = all.get((i + 1) % all.size());
        WVConfig.ACTIVE_VISION.set(next.id());
        WVConfig.ACTIVE_VISION.save();
        if (mc.level != null) {
            compileSelected();
            if (WastelandState.active) mc.levelRenderer.allChanged();
        }
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable("message.wastelandvision.selected", next.displayName()), true);
        }
    }

    private static int activeTicks = 0;
    private static boolean sodiumWarned = false;

    /**
     * Sodium meshes all terrain. If it's installed but our hook into its mesher never fires (e.g. a
     * future Sodium renamed the method), the vision silently shows the real world - say so once.
     */
    private static void checkSodiumHook(Minecraft mc) {
        if (sodiumWarned || !WastelandState.active || mc.level == null) { activeTicks = 0; return; }
        if (WastelandState.sodiumHookSeen || !net.neoforged.fml.ModList.get().isLoaded("sodium")) return;
        if (++activeTicks < 200) return; // ~10s of being active with chunks rebuilding
        sodiumWarned = true;
        LOGGER.warn("Wasteland Vision: Sodium is installed but its chunk mesher was never hooked "
                + "(ChunkBuilderMeshingTask#execute). This Sodium version may have changed - blocks will look real.");
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(
                    "Wasteland Vision: couldn't hook this Sodium version - blocks may look real (see log)."), false);
        }
    }

    /**
     * Keeps the vision's post shader in vanilla's post-effect slot while active. Vanilla clears the
     * slot on resource reload / camera change, so re-apply each tick. Never stomps a foreign effect.
     */
    private static void maintainShader(Minecraft mc) {
        GameRenderer gr = mc.gameRenderer;
        ResourceLocation desired = WastelandState.active ? Atmosphere.postShader : null;
        PostChain current = gr.currentEffect();
        if (current != null && ourShaders.contains(current.getName())
                && (desired == null || !current.getName().equals(desired.toString()))) {
            gr.shutdownEffect();
            current = null;
        }
        if (desired != null && current == null && !failedShaders.contains(desired)) {
            gr.loadEffect(desired);
            PostChain loaded = gr.currentEffect();
            if (loaded == null) {
                failedShaders.add(desired);
                LOGGER.warn("Wasteland Vision: post shader {} failed to load; continuing without it", desired);
            } else {
                ourShaders.add(loaded.getName());
            }
        }
    }

    /** Haze: pull terrain fog in while active (per-vision "fog_distance"). */
    private static void onRenderFog(ViewportEvent.RenderFog event) {
        float cap = Atmosphere.fogDistance;
        if (!WastelandState.active || cap <= 0) return;
        if (event.getMode() != FogRenderer.FogMode.FOG_TERRAIN || event.getType() != FogType.NONE) return;
        float far = Math.min(event.getFarPlaneDistance(), cap);
        event.setFarPlaneDistance(far);
        event.setNearPlaneDistance(far * 0.15F);
        event.setCanceled(true); // required for NeoForge to apply the new plane distances
    }

    private static void onFogColor(ViewportEvent.ComputeFogColor event) {
        Atmosphere.Tint fog = Atmosphere.fog;
        if (!WastelandState.active || fog == null || event.getCamera().getFluidInCamera() != FogType.NONE) return;
        float[] rgb = {event.getRed(), event.getGreen(), event.getBlue()};
        ColorGrade.apply(rgb, fog);
        event.setRed(rgb[0]);
        event.setGreen(rgb[1]);
        event.setBlue(rgb[2]);
    }
}
