package com.kragnoth.wastelandvision.client;

import com.google.gson.JsonObject;
import com.kragnoth.wastelandvision.WastelandState;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * Sky omens (vision.json "sky_omens"): client-only events drawn over the vanilla sky. Each in-game day
 * rolls at most one omen from a hash of the day number - every client sees the same omen on the same
 * day without any syncing.
 *
 *  smog_sun   - common: the sun is a dull, dimmed orange disc you could stare at; the day goes sepia
 *  twin_suns  - rare ("The King in Yellow", Chambers 1895: "where black stars rise, and strange moons
 *               circle through the skies... twin suns sink behind the lake"): a second pale sun sets
 *               beside the real one; that night black stars rise and a small second moon circles the moon
 *  eclipse    - rarest: a black disc slides over the sun, a corona flares, the world goes dim and cold;
 *               at totality ghosts walk everywhere and the grove ambience plays wherever you stand
 *
 * Drawn in the vanilla sky frame (Y -90, X timeOfDay*360; sun quad at +100, moon at -100), so resource
 * packs' sun/moon and any sky size line up. "force": "<omen>" pins one for testing.
 */
public final class SkyOmens {
    private SkyOmens() {}

    private static final ResourceLocation SUN = ResourceLocation.withDefaultNamespace("textures/environment/sun.png");
    private static final ResourceLocation MOON = ResourceLocation.withDefaultNamespace("textures/environment/moon_phases.png");
    private static ResourceLocation softDisc, hardDisc, starRing, corona;

    private enum Omen { NONE, SMOG, TWIN, ECLIPSE }

    private static volatile boolean enabled;
    @Nullable private static Omen force;
    private static float smogChance, twinChance, eclipseChance;
    private static float smR = 0.72F, smG = 0.40F, smB = 0.18F, smCover = 0.92F, tintR = 0.92F, tintG = 0.86F, tintB = 0.76F;
    private static float twinOffset = 11, twinLag = 4, twinAlpha = 0.6F;
    private static int blackStars = 110;
    private static boolean secondMoon = true, eclipseSurge = true, eclipseMusic = true;
    private static float eclipseSeconds = 150, eclipseDarken = 0.72F;
    /** sky_omens.surface_only (default true): the screen tint, ghost surge and grove ambience fade out where you can't see the sky. */
    private static boolean surfaceOnly = true;
    /** 0 = no sky overhead (cave), 1 = open sky. Smoothed so walking into a cave fades instead of snapping. */
    private static float exposure = 1F;
    private static long lastExposureNanos;

    /** Read by GroveAmbience: 0..1 extra "you are in a grove" depth while an eclipse is total. */
    public static volatile float ambienceOverride;

    public static void load(@Nullable JsonObject o) {
        enabled = false;
        force = null;
        ambienceOverride = 0;
        if (o == null) return;
        // the vision's own assets namespace holding textures/environment/{soft_disc,hard_disc,star_ring,corona}.png
        String t = o.has("textures_namespace") ? o.get("textures_namespace").getAsString() : "wastelandvision";
        softDisc = ResourceLocation.fromNamespaceAndPath(t, "textures/environment/soft_disc.png");
        hardDisc = ResourceLocation.fromNamespaceAndPath(t, "textures/environment/hard_disc.png");
        starRing = ResourceLocation.fromNamespaceAndPath(t, "textures/environment/star_ring.png");
        corona = ResourceLocation.fromNamespaceAndPath(t, "textures/environment/corona.png");
        if (o.has("force") && !o.get("force").isJsonNull()) {
            switch (o.get("force").getAsString()) {
                case "smog_sun" -> force = Omen.SMOG;
                case "twin_suns" -> force = Omen.TWIN;
                case "eclipse" -> force = Omen.ECLIPSE;
                case "none" -> force = Omen.NONE;
                default -> {}
            }
        }
        smogChance = twinChance = eclipseChance = 0;
        surfaceOnly = !o.has("surface_only") || o.get("surface_only").getAsBoolean();
        exposure = 1F;
        if (o.has("smog_sun")) {
            JsonObject s = o.getAsJsonObject("smog_sun");
            smogChance = f(s, "chance", 0.3F);
            float[] c = col(s, "color"); if (c != null) { smR = c[0]; smG = c[1]; smB = c[2]; }
            float[] dt = col(s, "day_tint"); if (dt != null) { tintR = dt[0]; tintG = dt[1]; tintB = dt[2]; }
            smCover = f(s, "cover", 0.92F);
        }
        if (o.has("twin_suns")) {
            JsonObject s = o.getAsJsonObject("twin_suns");
            twinChance = f(s, "chance", 0.06F); twinOffset = f(s, "offset", 11); twinLag = f(s, "lag", 4);
            twinAlpha = f(s, "alpha", 0.6F); blackStars = (int) f(s, "black_stars", 40);
            secondMoon = !s.has("second_moon") || s.get("second_moon").getAsBoolean();
        }
        if (o.has("eclipse")) {
            JsonObject s = o.getAsJsonObject("eclipse");
            eclipseChance = f(s, "chance", 0.025F); eclipseSeconds = Math.max(20, f(s, "duration_seconds", 150));
            eclipseDarken = Mth.clamp(f(s, "darken", 0.72F), 0, 0.95F);
            eclipseSurge = !s.has("ghost_surge") || s.get("ghost_surge").getAsBoolean();
            eclipseMusic = !s.has("music") || s.get("music").getAsBoolean();
        }
        enabled = true;
    }

    private static float f(JsonObject o, String k, float d) {
        return o.has(k) ? o.get(k).getAsFloat() : d;
    }

    @Nullable
    private static float[] col(JsonObject o, String k) {
        if (!o.has(k)) return null;
        int c = Integer.parseInt(o.get(k).getAsString().replace("#", ""), 16);
        return new float[]{((c >> 16) & 0xFF) / 255F, ((c >> 8) & 0xFF) / 255F, (c & 0xFF) / 255F};
    }

    private static long hash(long a, long b) {
        long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    private static float unit(long h, int shift) {
        return ((h >>> shift) & 0xFFFF) / 65536F;
    }

    /** Which omen owns the given in-game day (the "day" runs sunrise to sunrise). */
    private static Omen omenFor(long day) {
        if (force != null) return force;
        float r = unit(hash(day, 0x0E1), 0);
        if (r < eclipseChance) return Omen.ECLIPSE;
        if (r < eclipseChance + twinChance) return Omen.TWIN;
        if (r < eclipseChance + twinChance + smogChance) return Omen.SMOG;
        return Omen.NONE;
    }

    // ---- eclipse timing -------------------------------------------------------------------------------
    /** 0..1 coverage of the sun right now (0 outside an eclipse). */
    private static float eclipseCoverage(ClientLevel level, float pt) {
        double dayTime = level.getDayTime() + pt;
        long day = (long) Math.floor(dayTime / 24000.0);
        if (omenFor(day) != Omen.ECLIPSE) return 0;
        double t0 = force == Omen.ECLIPSE ? 5000 : 3500 + unit(hash(day, 0xEC1), 0) * 4000;   // mid-morning to early afternoon
        double p = (dayTime - day * 24000.0 - t0) / (eclipseSeconds * 20);
        if (p <= 0 || p >= 1) return 0;
        if (p < 0.4) return (float) (p / 0.4);
        if (p < 0.6) return 1;
        return (float) ((1 - p) / 0.4);
    }

    /** Update the smoothed sky exposure from the sky light at the camera (called once per frame). */
    private static void updateExposure(ClientLevel level) {
        long now = System.nanoTime();
        float dt = lastExposureNanos == 0 ? 1F : Mth.clamp((now - lastExposureNanos) / 1.0e9F, 0F, 1F);
        lastExposureNanos = now;
        if (!surfaceOnly) { exposure = 1F; return; }
        net.minecraft.core.BlockPos pos = Minecraft.getInstance().gameRenderer.getMainCamera().getBlockPosition();
        int sky = level.getBrightness(LightLayer.SKY, pos);            // 0-15, raw (independent of time of day)
        float target = Mth.clamp((sky - 3) / 10F, 0F, 1F);             // a bit forgiving: shade under trees still counts
        exposure += (target - exposure) * Mth.clamp(dt * 2.5F, 0F, 1F); // ~1s fade
    }

    // ---- render: over the sky -------------------------------------------------------------------------
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (!enabled || !WastelandState.active) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !level.dimensionType().hasSkyLight() || level.effects().skyType() != net.minecraft.client.renderer.DimensionSpecialEffects.SkyType.NORMAL) return;
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) updateExposure(level);
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) renderSky(event, level, pt);
        else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_WEATHER) renderGrade(level, pt);
    }

    private static void renderSky(RenderLevelStageEvent event, ClientLevel level, float pt) {
        double dayTime = level.getDayTime() + pt;
        long day = (long) Math.floor(dayTime / 24000.0);
        float tod = (float) (dayTime - day * 24000.0);           // 0 = sunrise, 6000 noon, 12000 sunset, 18000 midnight
        Omen omen = omenFor(day);
        // a night belongs to the day that started at the previous sunrise
        Omen nightOmen = tod >= 12000 ? omen : omenFor(day - 1);
        float clear = 1F - level.getRainLevel(pt);
        if (clear <= 0.01F) { ambienceOverride = 0; return; }   // omens hide behind rain

        PoseStack ps = new PoseStack();
        ps.mulPose(event.getModelViewMatrix());
        ps.mulPose(Axis.YP.rotationDegrees(-90F));
        ps.mulPose(Axis.XP.rotationDegrees(level.getTimeOfDay(pt) * 360F));

        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.disableCull();                         // quads face either way on the sky sphere
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);

        Vec3 sky = level.getSkyColor(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition(), pt);
        float dayAmt = Mth.clamp((level.getSkyDarken(pt) - 0.3F) / 0.6F, 0, 1);

        if (omen == Omen.SMOG && tod < 12600) {
            // hide the real sun under haze, then a dull orange disc you could look straight at
            normal();
            quad(ps, softDisc, 0, 100, 0, 52, (float) sky.x, (float) sky.y, (float) sky.z, smCover * clear * dayAmt);
            quad(ps, softDisc, 0, 100, 0, 26, smR, smG, smB, 0.75F * clear);
            quad(ps, hardDisc, 0, 100, 0, 13, smR * 1.1F, smG * 1.05F, smB, 0.85F * clear);
        }
        if (omen == Omen.TWIN && tod > 7000 && tod < 13200) {
            float a = twinAlpha * clear * Mth.clamp((tod - 7000) / 1500F, 0, 1);
            ps.pushPose();
            ps.mulPose(Axis.XP.rotationDegrees(-twinLag));
            ps.mulPose(Axis.ZP.rotationDegrees(twinOffset));
            additive();
            quad(ps, softDisc, 0, 100, 0, 34, 0.95F, 0.92F, 0.80F, a * 0.35F);
            quad(ps, SUN, 0, 100, 0, 24, 0.9F, 0.88F, 0.82F, a);
            ps.popPose();
        }
        if (nightOmen == Omen.TWIN) {
            float stars = Mth.clamp(level.getStarBrightness(pt) * 2.2F, 0, 1) * clear;   // full strength once it's properly dark
            if (stars > 0.01F) {
                long seed = hash(tod >= 12000 ? day : day - 1, 0xB1AC);
                for (int i = 0; i < blackStars; i++) {
                    long h = hash(seed, i);
                    // uniform on the sphere, kept away from the horizon band
                    double u = unit(h, 0) * 2 - 1, th = unit(h, 16) * Math.PI * 2;
                    double r = Math.sqrt(1 - u * u);
                    float x = (float) (r * Math.cos(th) * 100), y = (float) (u * 100), z = (float) (r * Math.sin(th) * 100);
                    float s = 0.7F + unit(h, 32) * 1.2F;
                    additive();
                    quadAt(ps, starRing, x, y, z, s * 2.1F, 0.70F, 0.66F, 0.88F, 0.42F * stars);   // a cold rim of light...
                    normal();
                    quadAt(ps, hardDisc, x, y, z, s * 0.95F, 0.0F, 0.0F, 0.01F, stars);            // ...around a black star
                }
                if (secondMoon) {
                    // a small, wrong-coloured moon circling the real one (moon sits at y = -100)
                    double ang = (level.getGameTime() + pt) / 20.0 * (Math.PI * 2 / 180.0);
                    float ox = (float) Math.cos(ang) * 34, oz = (float) Math.sin(ang) * 34;
                    // always full, violet - a moon that doesn't belong
                    additive();
                    quadUV(ps, MOON, ox, -100, oz, 13, 0.25F, 0.5F, 0F, 0F, 0.80F, 0.66F, 1.0F, stars);
                }
            }
        }
        float cov = eclipseCoverage(level, pt);
        if (cov > 0) {
            // the black disc slides in from one side, sits dead centre at totality, slides out
            float off = (1 - cov) * 30F;
            float total = Mth.clamp((cov - 0.85F) / 0.15F, 0, 1);
            additive();
            quad(ps, corona, 0, 100, 0, 40, 0.92F, 0.95F, 1.0F, total * total * 0.95F * clear);
            normal();
            quad(ps, hardDisc, off, 100, off * 0.4F, 16.5F, 0.015F, 0.015F, 0.025F, Math.min(1, cov * 3) * clear);
            ambienceOverride = eclipseMusic ? total * exposure : 0;
            if (eclipseSurge && total > 0.5F && exposure > 0.5F) Ghosts.surge(level.getGameTime() + 100);
        } else {
            ambienceOverride = 0;
        }

        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
    }

    private static void normal() {
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
    }

    private static void additive() {
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
    }

    /** A quad lying in the sky sphere at height y (sun plane +100 / moon plane -100), centred on (x, z). */
    private static void quad(PoseStack ps, ResourceLocation tex, float x, float y, float z, float half, float r, float g, float b, float a) {
        quadUV(ps, tex, x, y, z, half, 0, 0, 1, 1, r, g, b, a);
    }

    private static void quadUV(PoseStack ps, ResourceLocation tex, float x, float y, float z, float half,
                               float u0, float v0, float u1, float v1, float r, float g, float b, float a) {
        if (a <= 0.003F) return;
        RenderSystem.setShaderTexture(0, tex);
        Matrix4f m = ps.last().pose();
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bb.addVertex(m, x - half, y, z - half).setUv(u0, v0).setColor(r, g, b, a);
        bb.addVertex(m, x + half, y, z - half).setUv(u1, v0).setColor(r, g, b, a);
        bb.addVertex(m, x + half, y, z + half).setUv(u1, v1).setColor(r, g, b, a);
        bb.addVertex(m, x - half, y, z + half).setUv(u0, v1).setColor(r, g, b, a);
        BufferUploader.drawWithShader(bb.buildOrThrow());
    }

    /** A quad at an arbitrary point of the sky sphere, facing the centre (for stars). */
    private static void quadAt(PoseStack ps, ResourceLocation tex, float x, float y, float z, float half, float r, float g, float b, float a) {
        ps.pushPose();
        ps.translate(x, y, z);
        // rotate the quad's +Y normal to point back at the origin
        org.joml.Vector3f dir = new org.joml.Vector3f(-x, -y, -z).normalize();
        ps.mulPose(new org.joml.Quaternionf().rotationTo(new org.joml.Vector3f(0, 1, 0), dir));
        quad(ps, tex, 0, 0, 0, half, r, g, b, a);
        ps.popPose();
    }

    // ---- render: whole-frame grade (eclipse dusk, smog sepia) --------------------------------------
    private static void renderGrade(ClientLevel level, float pt) {
        double dayTime = level.getDayTime() + pt;
        long day = (long) Math.floor(dayTime / 24000.0);
        float tod = (float) (dayTime - day * 24000.0);
        float r = 1, g = 1, b = 1;
        if (omenFor(day) == Omen.SMOG && tod < 12600) {
            float k = Mth.clamp(tod / 800F, 0, 1) * Mth.clamp((12600 - tod) / 800F, 0, 1) * exposure;
            r = Mth.lerp(k, 1, tintR); g = Mth.lerp(k, 1, tintG); b = Mth.lerp(k, 1, tintB);
        }
        float cov = eclipseCoverage(level, pt);
        if (cov > 0) {
            float d = eclipseDarken * cov * cov * exposure;
            r *= 1 - d * 1.0F; g *= 1 - d * 0.94F; b *= 1 - d * 0.82F;   // cold, blue-shifted dusk
        }
        if (r >= 0.999F && g >= 0.999F && b >= 0.999F) return;
        // multiply the finished frame (before the hand and HUD) by the tint
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        mv.identity();
        RenderSystem.applyModelViewMatrix();
        Matrix4f oldProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting oldSort = RenderSystem.getVertexSorting();
        RenderSystem.setProjectionMatrix(new Matrix4f(), VertexSorting.ORTHOGRAPHIC_Z);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ZERO);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bb.addVertex(-1, -1, 0).setColor(r, g, b, 1);
        bb.addVertex(1, -1, 0).setColor(r, g, b, 1);
        bb.addVertex(1, 1, 0).setColor(r, g, b, 1);
        bb.addVertex(-1, 1, 0).setColor(r, g, b, 1);
        BufferUploader.drawWithShader(bb.buildOrThrow());
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.setProjectionMatrix(oldProj, oldSort);
        mv.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }
}
