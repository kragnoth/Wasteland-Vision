package com.kragnoth.wastelandvision.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kragnoth.wastelandvision.WastelandState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.Tags;

/**
 * Ghosts (vision.json "ghosts"): translucent, client-only mobs that haunt a vision's blight zones
 * ("haunted groves"). 100% client side - the mobs are built with EntityType.create() but never added
 * to the level, so the server, other players, Jade, targeting and collision never see them. They
 * wander inside the zone and only phase in while the player is looking toward them (view cone, range,
 * line of sight); looking away, or a ghost drifting out of the zone, phases it back out.
 *
 * Any mob type in the pack may appear (monster/creature/ambient categories, bosses and huge mobs
 * excluded); a type whose constructor or renderer throws is dropped for the session.
 */
public final class Ghosts {
    private Ghosts() {}

    // ---- config (vision.json "ghosts") --------------------------------------------------------------
    private static volatile boolean enabled = false;
    private static float percent = 18, scale = 18, threshold = 2F;
    private static int max = 6, spawnEvery = 30, fadeTicks = 25;
    private static float radius = 48, minDistance = 10, viewAngle = 40, alpha = 0.38F, flicker = 0.25F;
    private static float tr = 0.70F, tg = 0.92F, tb = 1.0F;
    private static boolean landOnly = false;
    private static final List<net.minecraft.tags.TagKey<EntityType<?>>> onlyTags = new ArrayList<>();
    private static boolean lineOfSight = true, includeMonsters = true, includeCreatures = true, includeAmbient = true;
    private static final Set<ResourceLocation> exclude = new HashSet<>();
    private static final List<String> only = new ArrayList<>();
    private static final Set<EntityType<?>> broken = new HashSet<>();
    @Nullable private static List<EntityType<?>> pool;

    private static final List<Ghost> ghosts = new ArrayList<>();
    private static final Random RNG = new Random();

    public static void load(@Nullable JsonObject g) {
        ghosts.clear();
        pool = null;
        enabled = false;
        exclude.clear();
        only.clear();
        onlyTags.clear();
        if (g == null || (g.has("enabled") && !g.get("enabled").getAsBoolean())) return;
        percent = f(g, "percent", 18); scale = Math.max(1, f(g, "scale", 18));
        max = (int) f(g, "max", 6); spawnEvery = Math.max(1, (int) f(g, "spawn_every", 30));
        fadeTicks = Math.max(1, (int) f(g, "fade_ticks", 25));
        radius = f(g, "radius", 48); minDistance = f(g, "min_distance", 10); viewAngle = f(g, "view_angle", 40);
        alpha = Mth.clamp(f(g, "alpha", 0.38F), 0.02F, 1F); flicker = Mth.clamp(f(g, "flicker", 0.25F), 0, 1);
        lineOfSight = !g.has("line_of_sight") || g.get("line_of_sight").getAsBoolean();
        tr = 0.70F; tg = 0.92F; tb = 1.0F;
        if (g.has("tint")) {
            int c = Integer.parseInt(g.get("tint").getAsString().replace("#", ""), 16);
            tr = ((c >> 16) & 0xFF) / 255F; tg = ((c >> 8) & 0xFF) / 255F; tb = (c & 0xFF) / 255F;
        }
        includeMonsters = includeCreatures = includeAmbient = true;
        if (g.has("categories")) {
            includeMonsters = includeCreatures = includeAmbient = false;
            for (JsonElement e : g.getAsJsonArray("categories")) {
                switch (e.getAsString()) {
                    case "monster" -> includeMonsters = true;
                    case "creature" -> includeCreatures = true;
                    case "ambient" -> includeAmbient = true;
                    default -> {}
                }
            }
        }
        if (g.has("exclude")) for (JsonElement e : g.getAsJsonArray("exclude")) {
            ResourceLocation id = ResourceLocation.tryParse(e.getAsString());
            if (id != null) exclude.add(id);
        }
        if (g.has("mobs")) for (JsonElement e : g.getAsJsonArray("mobs")) {
            String m = e.getAsString();
            if (m.startsWith("#")) {
                ResourceLocation tag = ResourceLocation.tryParse(m.substring(1));
                if (tag != null) onlyTags.add(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, tag));
            } else {
                only.add(m);
            }
        }
        landOnly = g.has("land_only") && g.get("land_only").getAsBoolean();
        threshold = Variation.blightThreshold(percent, scale);
        enabled = max > 0;
    }

    private static float f(JsonObject o, String k, float d) {
        return o.has(k) ? o.get(k).getAsFloat() : d;
    }

    /** Same field as the vision's blight rules (same percent/scale -> exactly the haunted groves). */
    private static boolean inZone(double x, double z) {
        return surging() || inHaunt(x, z);
    }

    // ---- shared with GroveAmbience -------------------------------------------------------------------
    /** Midnight surge: until this game time, ghosts may walk anywhere and in greater numbers. */
    private static long surgeUntil = Long.MIN_VALUE;

    public static void surge(long untilGameTime) {
        surgeUntil = untilGameTime;
    }

    private static boolean surging() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.getGameTime() < surgeUntil;
    }

    public static boolean enabled() {
        return enabled;
    }

    /** True inside the vision's haunt zone (ghost percent/scale). */
    public static boolean inHaunt(double x, double z) {
        return Variation.blight(BlockPos.containing(x, 64, z), scale) >= threshold;
    }

    /** 0..1: how deep in the haunt zone a point is - fraction of a 9-point ring (radius r) inside it. */
    public static float hauntDepth(double x, double z, double r) {
        int in = inHaunt(x, z) ? 1 : 0;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            if (inHaunt(x + Math.cos(a) * r, z + Math.sin(a) * r)) in++;
        }
        return in / 9F;
    }

    /** Strongest fade of any ghost currently phased in (0 = none visible). */
    public static float visibleGhost() {
        float m = 0;
        for (Ghost g : ghosts) m = Math.max(m, g.fade);
        return m;
    }

    // ---- mob pool -----------------------------------------------------------------------------------
    private static List<EntityType<?>> pool() {
        if (pool != null) return pool;
        // "mobs": ids/globs and/or #tags (e.g. #minecraft:undead); empty = everything
        boolean any = only.isEmpty() && onlyTags.isEmpty();
        List<java.util.regex.Pattern> globs = Globs.compileAll(only);
        List<EntityType<?>> out = new ArrayList<>();
        for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(t);
            if (exclude.contains(id) || broken.contains(t)) continue;
            if (!any && !Globs.any(globs, id) && onlyTags.stream().noneMatch(t::is)) continue;
            MobCategory c = t.getCategory();
            boolean cat = (c == MobCategory.MONSTER && includeMonsters) || (c == MobCategory.CREATURE && includeCreatures)
                    || (c == MobCategory.AMBIENT && includeAmbient);
            if (!cat) continue;
            if (t.is(Tags.EntityTypes.BOSSES)) continue;
            if (t.getDimensions().width() > 2.5F || t.getDimensions().height() > 4F) continue;
            out.add(t);
        }
        pool = out;
        WastelandClient.LOGGER.info("Wasteland Vision: [{}] ghosts: {} mob types can haunt", WastelandState.visionId, out.size());
        WastelandClient.LOGGER.debug("Wasteland Vision: ghost pool {}", out.stream().map(BuiltInRegistries.ENTITY_TYPE::getKey).toList());
        return out;
    }

    // ---- one ghost ----------------------------------------------------------------------------------
    private static final class Ghost {
        final Entity e;
        final boolean flies;
        double x, y, z, px, py, pz, groundY;
        float heading, speed, turn, fade, prevFade, phase;
        int outside, sightTimer;
        boolean seen;

        Ghost(Entity e, double x, double y, double z) {
            this.e = e;
            this.flies = isFlyer(e);
            this.x = px = x; this.groundY = y; this.y = py = y + (flies ? 2 + RNG.nextFloat() * 3 : 0); this.z = pz = z;
            this.heading = RNG.nextFloat() * 360F;
            this.speed = 0.025F + RNG.nextFloat() * 0.035F;
            this.phase = RNG.nextFloat() * 100F;
            e.setPos(this.x, this.y, this.z);
        }
    }

    private static boolean isFlyer(Entity e) {
        return e instanceof FlyingMob || e instanceof FlyingAnimal || e instanceof AmbientCreature;
    }

    // ---- tick ---------------------------------------------------------------------------------------
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (!enabled || !WastelandState.active || level == null || mc.player == null || mc.isPaused()) {
            if (!WastelandState.active || level == null) ghosts.clear();
            return;
        }
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 eye = cam.getPosition();
        Vec3 look = new Vec3(cam.getLookVector());
        double cos = Math.cos(Math.toRadians(viewAngle));
        float step = 1F / fadeTicks;

        for (int i = ghosts.size() - 1; i >= 0; i--) {
            Ghost g = ghosts.get(i);
            g.px = g.x; g.py = g.y; g.pz = g.z; g.prevFade = g.fade;
            // wander: slow drift with gentle turning, mostly turning back at the zone's edge
            g.turn = Mth.clamp(g.turn + (RNG.nextFloat() - 0.5F) * 3F, -4F, 4F);
            g.heading += g.turn;
            double nx = g.x - Math.sin(Math.toRadians(g.heading)) * g.speed;
            double nz = g.z + Math.cos(Math.toRadians(g.heading)) * g.speed;
            if (!inZone(nx, nz) && RNG.nextFloat() < 0.9F) {
                g.heading += 180F;                      // usually turn back; sometimes drift out and fade
            } else {
                g.x = nx; g.z = nz;
            }
            int gy = groundAt(level, g.x, g.z);
            if (gy != Integer.MIN_VALUE) g.groundY = gy;
            double targetY = g.groundY + (g.flies ? 2.5 + Math.sin((g.phase + g.e.tickCount) * 0.03) : 0)
                    + 0.08 * Math.sin((g.phase + g.e.tickCount) * 0.07);   // faint hover
            g.y += (targetY - g.y) * 0.15;
            boolean zone = inZone(g.x, g.z);
            g.outside = zone ? 0 : g.outside + 1;

            // gaze: in range, in the view cone, and (every few ticks) not behind terrain
            Vec3 mid = new Vec3(g.x, g.y + g.e.getBbHeight() * 0.6, g.z);
            Vec3 to = mid.subtract(eye);
            double dist = to.length();
            boolean inView = dist >= 3 && dist <= radius && to.scale(1 / dist).dot(look) >= cos;
            if (inView && lineOfSight && --g.sightTimer <= 0) {
                g.sightTimer = 5;
                g.seen = level.clip(new ClipContext(eye, mid, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player))
                        .getType() == HitResult.Type.MISS;
            } else if (!lineOfSight) {
                g.seen = true;
            }
            boolean show = zone && inView && g.seen;
            g.fade = Mth.clamp(g.fade + (show ? step : -step), 0, 1);

            // drive the model's animation
            Entity e = g.e;
            e.xo = g.px; e.yo = g.py; e.zo = g.pz;
            e.setPos(g.x, g.y, g.z);
            e.yRotO = e.getYRot(); e.setYRot(g.heading);
            e.tickCount++;
            if (e instanceof LivingEntity le) {
                le.yBodyRotO = le.yBodyRot; le.yBodyRot = g.heading;
                le.yHeadRotO = le.yHeadRot; le.yHeadRot = g.heading;
                le.walkAnimation.update(g.flies ? 0F : Math.min(1F, g.speed * 12F), 0.4F);
            }

            boolean gone = g.fade <= 0 && (g.outside > 40 || dist > radius * 1.6);
            if (gone) ghosts.remove(i);
        }

        int cap = surging() ? max * 2 : max;
        if (ghosts.size() < cap && level.getGameTime() % (surging() ? Math.max(1, spawnEvery / 3) : spawnEvery) == 0) trySpawn(mc, level, eye);
    }

    private static void trySpawn(Minecraft mc, ClientLevel level, Vec3 eye) {
        List<EntityType<?>> types = pool();
        if (types.isEmpty()) return;
        for (int tries = 0; tries < 12; tries++) {
            double a = RNG.nextDouble() * Math.PI * 2, d = minDistance + RNG.nextDouble() * (radius - minDistance);
            double x = eye.x + Math.cos(a) * d, z = eye.z + Math.sin(a) * d;
            if (!inZone(x, z)) continue;
            int y = groundAt(level, x, z);
            if (y == Integer.MIN_VALUE) continue;
            EntityType<?> t = types.get(RNG.nextInt(types.size()));
            Entity e;
            try {
                e = t.create(level);
            } catch (Throwable ex) {
                drop(t, ex);
                return;
            }
            if (e == null) { drop(t, null); return; }
            if (landOnly && (isFlyer(e) || e instanceof net.minecraft.world.entity.animal.WaterAnimal)) {
                types.remove(t);                           // e.g. phantoms under #undead (pool is rebuilt on reload)
                if (types.isEmpty()) return;
                continue;
            }
            e.setSilent(true);
            e.noPhysics = true;
            ghosts.add(new Ghost(e, x, y, z));
            return;
        }
    }

    /**
     * Top of the real ground at x/z, client side. Only MOTION_BLOCKING and WORLD_SURFACE heightmaps are
     * synced to the client (MOTION_BLOCKING_NO_LEAVES reads as the world floor there), so start at
     * MOTION_BLOCKING and walk down through leaves and anything without collision.
     * MIN_VALUE = chunk not loaded / no ground found.
     */
    private static int groundAt(ClientLevel level, double x, double z) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        if (!level.hasChunk(bx >> 4, bz >> 4)) return Integer.MIN_VALUE;
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
        int min = level.getMinBuildHeight();
        if (top <= min + 1) return Integer.MIN_VALUE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(bx, top - 1, bz);
        for (int i = 0; i < 48 && p.getY() > min; i++, p.move(0, -1, 0)) {
            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(p);
            if (st.is(net.minecraft.tags.BlockTags.LEAVES) || st.getCollisionShape(level, p).isEmpty()) continue;
            if (!st.getFluidState().isEmpty()) return Integer.MIN_VALUE;   // no ghosts standing on water
            return p.getY() + 1;
        }
        return Integer.MIN_VALUE;
    }

    private static void drop(EntityType<?> t, @Nullable Throwable ex) {
        broken.add(t);
        if (pool != null) pool.remove(t);
        WastelandClient.LOGGER.debug("Wasteland Vision: ghost type {} unusable client-side ({})", BuiltInRegistries.ENTITY_TYPE.getKey(t),
                ex == null ? "create() returned null" : ex.toString());
    }

    // ---- render -------------------------------------------------------------------------------------
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || ghosts.isEmpty() || !WastelandState.active) return;
        Minecraft mc = Minecraft.getInstance();
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        EntityRenderDispatcher disp = mc.getEntityRenderDispatcher();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        PoseStack ps = event.getPoseStack();
        disp.setRenderShadow(false);
        Set<RenderType> used = new LinkedHashSet<>();
        try {
            for (int i = ghosts.size() - 1; i >= 0; i--) {
                Ghost g = ghosts.get(i);
                float fade = Mth.lerp(pt, g.prevFade, g.fade);
                if (fade <= 0.01F) continue;
                float time = g.e.tickCount + pt + g.phase;
                // phasing shimmer: a slow breath plus an occasional stutter
                float shimmer = 1F - flicker * (0.5F + 0.5F * Mth.sin(time * 0.11F)) * (0.6F + 0.4F * Mth.sin(time * 0.53F));
                float a = alpha * fade * fade * Mth.clamp(shimmer, 0.15F, 1F);
                double x = Mth.lerp(pt, g.px, g.x), y = Mth.lerp(pt, g.py, g.y), z = Mth.lerp(pt, g.pz, g.z);
                GhostBuffers gb = new GhostBuffers(buffers, a, used);
                try {
                    disp.render(g.e, x - cam.x, y - cam.y, z - cam.z, Mth.lerp(pt, g.e.yRotO, g.e.getYRot()), pt, ps, gb, 0xF000F0);
                } catch (Throwable ex) {
                    ghosts.remove(i);
                    drop(g.e.getType(), ex);
                }
            }
        } finally {
            disp.setRenderShadow(true);          // vanilla's default; only GUI previews ever turn it off
            for (RenderType rt : used) buffers.endBatch(rt);
        }
    }

    // ---- ghostly buffers: every textured layer -> translucent, tinted, faded ------------------------
    private static final Map<RenderType, Optional<RenderType>> MAPPED = new IdentityHashMap<>();
    private static Field stateField, textureField;
    private static Method cutoutTexture;
    private static boolean reflectFailed;

    @Nullable
    private static RenderType translucentFor(RenderType rt) {
        return MAPPED.computeIfAbsent(rt, r -> Optional.ofNullable(textureOf(r)).map(RenderType::entityTranslucent)).orElse(null);
    }

    @Nullable
    private static ResourceLocation textureOf(RenderType rt) {
        if (reflectFailed) return null;
        try {
            if (stateField == null) {
                Class<?> composite = Class.forName("net.minecraft.client.renderer.RenderType$CompositeRenderType");
                stateField = composite.getDeclaredField("state");
                stateField.setAccessible(true);
                textureField = stateField.getType().getDeclaredField("textureState");
                textureField.setAccessible(true);
                cutoutTexture = net.minecraft.client.renderer.RenderStateShard.EmptyTextureStateShard.class.getDeclaredMethod("cutoutTexture");
                cutoutTexture.setAccessible(true);
            }
            if (!stateField.getDeclaringClass().isInstance(rt)) return null;
            Object tex = textureField.get(stateField.get(rt));
            @SuppressWarnings("unchecked")
            Optional<ResourceLocation> o = (Optional<ResourceLocation>) cutoutTexture.invoke(tex);
            return o.orElse(null);
        } catch (Throwable ex) {
            reflectFailed = true;
            WastelandClient.LOGGER.warn("Wasteland Vision: ghosts can't read render-type textures, ghosts disabled ({})", ex.toString());
            enabled = false;
            return null;
        }
    }

    private record GhostBuffers(MultiBufferSource.BufferSource src, float a, Set<RenderType> used) implements MultiBufferSource {
        @Override
        public VertexConsumer getBuffer(RenderType type) {
            RenderType t = translucentFor(type);
            if (t == null) return NOOP;              // untextured layers (leashes, beams...) are skipped
            used.add(t);
            return new Tinted(src.getBuffer(t), a);
        }
    }

    private record Tinted(VertexConsumer d, float a) implements VertexConsumer {
        @Override public VertexConsumer addVertex(float x, float y, float z) { d.addVertex(x, y, z); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int al) {
            d.setColor((int) (r * tr), (int) (g * tg), (int) (b * tb), (int) (al * a));
            return this;
        }
        @Override public VertexConsumer setUv(float u, float v) { d.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { d.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { d.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { d.setNormal(x, y, z); return this; }
    }

    private static final VertexConsumer NOOP = new VertexConsumer() {
        @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    };
}
