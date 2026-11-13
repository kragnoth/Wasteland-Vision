package com.kragnoth.wastelandvision.client;

import com.google.gson.JsonObject;
import com.kragnoth.wastelandvision.WastelandState;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Foundry smoke (vision.json "smoke_columns"): client-only columns of smoke on the horizon, as if
 * factories are always just out of sight. Columns are fixed in the world (one chance per grid cell),
 * drawn as camera-facing puffs using the vanilla campfire smoke sprites (so resource packs restyle them),
 * leaning with a shared wind. They only show in a distance band just inside the fog and dissolve as
 * you approach - you never reach the chimney. At night the lowest puffs pick up a furnace glow.
 */
public final class SmokeColumns {
    private SmokeColumns() {}

    private static volatile boolean enabled;
    private static float density = 0.35F, cell = 192, height = 110, width = 9, rise = 1.2F, maxDistance = 180, windDeg = 25, windLean = 0.45F;
    private static int puffs = 28;
    private static float lr = 0.14F, lg = 0.13F, lb = 0.12F, hr = 0.36F, hg = 0.36F, hb = 0.38F, er = 0.48F, eg = 0.18F, eb = 0.06F;
    private static final RenderType[] FRAMES = new RenderType[12];

    static {
        for (int i = 0; i < 12; i++) {
            FRAMES[i] = RenderType.entityTranslucent(ResourceLocation.withDefaultNamespace("textures/particle/big_smoke_" + i + ".png"));
        }
    }

    public static void load(@Nullable JsonObject s) {
        enabled = false;
        if (s == null) return;
        density = f(s, "density", 0.35F); cell = Math.max(32, f(s, "cell", 192)); height = f(s, "height", 110);
        width = f(s, "width", 9); rise = f(s, "rise", 1.2F); maxDistance = f(s, "max_distance", 180);
        puffs = Mth.clamp((int) f(s, "puffs", 28), 4, 96); windDeg = f(s, "wind", 25); windLean = f(s, "lean", 0.45F);
        float[] c;
        if ((c = col(s, "color_low")) != null) { lr = c[0]; lg = c[1]; lb = c[2]; }
        if ((c = col(s, "color_high")) != null) { hr = c[0]; hg = c[1]; hb = c[2]; }
        if ((c = col(s, "ember")) != null) { er = c[0]; eg = c[1]; eb = c[2]; }
        enabled = density > 0;
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

    private static long hash(long x, long z, long salt) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    private static float unit(long h, int shift) {
        return ((h >>> shift) & 0xFFFF) / 65535F;
    }

    private record Puff(double x, double y, double z, float size, float a, float r, float g, float b, float spin, int frame, double dist) {}

    public static void onRenderStage(RenderLevelStageEvent event) {
        if (!enabled || !WastelandState.active || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double time = (level.getGameTime() + pt) / 20.0;

        // distance band: just inside the fog wall, whatever the render distance / fog_distance is
        float far = Math.min(maxDistance, RenderSystem.getShaderFogEnd() * 0.97F);
        float near = far * 0.5F;
        if (far < 48) return;
        float sky = level.getSkyDarken(pt);                // 1 = full day, ~0.2 = night
        float night = Mth.clamp((0.75F - sky) / 0.5F, 0F, 1F);
        float dayLight = Mth.clamp(0.35F + sky * 0.75F, 0.3F, 1F);

        double wx = -Math.sin(Math.toRadians(windDeg)), wz = Math.cos(Math.toRadians(windDeg));
        List<Puff> list = new ArrayList<>();
        int r = (int) Math.ceil(far / cell) + 1;
        int cx0 = Mth.floor(cam.x / cell), cz0 = Mth.floor(cam.z / cell);
        for (int cx = cx0 - r; cx <= cx0 + r; cx++) {
            for (int cz = cz0 - r; cz <= cz0 + r; cz++) {
                long h = hash(cx, cz, 4242);
                if (unit(h, 0) >= density) continue;
                double x = (cx + 0.15 + 0.7 * unit(h, 16)) * cell, z = (cz + 0.15 + 0.7 * unit(h, 32)) * cell;
                double d = Math.sqrt((x - cam.x) * (x - cam.x) + (z - cam.z) * (z - cam.z));
                if (d < near || d > far) continue;
                float vis = Mth.clamp((float) (d - near) / 20F, 0, 1) * Mth.clamp((float) (far - d) / 12F, 0, 1);
                if (vis <= 0) continue;
                int bx = Mth.floor(x), bz = Mth.floor(z);
                double base = level.hasChunk(bx >> 4, bz >> 4) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz) : level.getSeaLevel() + 4;
                float scaleH = height * (0.75F + 0.5F * unit(h, 48));
                float w = width * (0.8F + 0.4F * unit(h, 8));
                for (int k = 0; k < puffs; k++) {
                    // each puff rises from the stack, swells, drifts downwind and thins out
                    double ph = (time * rise / scaleH + (double) k / puffs + unit(hash(k, cx, cz), 0) * 0.02) % 1.0;
                    float p = (float) ph;
                    long ph2 = hash(k, cx * 31L + cz, 99);
                    // plume physics-ish: rises straight from the stack, then bends over with the wind and spreads
                    double lean = Math.pow(p, 2.2) * scaleH * windLean;
                    double spread = w * (0.25 + 1.6 * p);
                    double side = (unit(ph2, 0) - 0.5) * spread + Math.sin(time * 0.3 + k * 2.1 + cx) * w * 0.35 * p;
                    double along = (unit(ph2, 16) - 0.5) * spread * 0.6;
                    double px = x + wx * (lean + along) + wz * side, pz = z + wz * (lean + along) - wx * side;
                    double py = base + p * scaleH - Math.pow(p, 2) * scaleH * 0.12;   // flattens as it bends
                    float size = w * (0.9F + 2.8F * p) * (0.8F + 0.4F * unit(ph2, 32));
                    float a = vis * Mth.clamp(p * 10F, 0, 1) * Mth.clamp((1F - p) * 1.8F, 0, 1) * (0.42F + 0.25F * (1F - p));
                    float cr = Mth.lerp(p, lr, hr), cg = Mth.lerp(p, lg, hg), cb = Mth.lerp(p, lb, hb);
                    if (p < 0.22F && night > 0) {          // furnace glow under the plume at night
                        float e = night * (1F - p / 0.22F);
                        cr = Mth.lerp(e, cr, er); cg = Mth.lerp(e, cg, eg); cb = Mth.lerp(e, cb, eb);
                    } else {
                        cr *= dayLight; cg *= dayLight; cb *= dayLight;
                    }
                    int frame = Mth.clamp((int) (p * 12), 0, 11);
                    double dist = (px - cam.x) * (px - cam.x) + (py - cam.y) * (py - cam.y) + (pz - cam.z) * (pz - cam.z);
                    list.add(new Puff(px, py, pz, size, a, cr, cg, cb, (k * 53 % 360), frame, dist));
                }
            }
        }
        if (list.isEmpty()) return;
        list.sort(Comparator.comparingDouble(Puff::dist).reversed());   // far to near

        PoseStack ps = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        for (Puff p : list) {
            ps.pushPose();
            ps.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z);
            ps.mulPose(camera.rotation());
            ps.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(p.spin));
            PoseStack.Pose pose = ps.last();
            Matrix4f m = pose.pose();
            VertexConsumer vc = buffers.getBuffer(FRAMES[p.frame]);
            float s = p.size / 2;
            int a = (int) (p.a * 255), rr = (int) (p.r * 255), gg = (int) (p.g * 255), bb = (int) (p.b * 255);
            vert(vc, pose, m, -s, -s, 1, 1, rr, gg, bb, a);
            vert(vc, pose, m, -s, s, 1, 0, rr, gg, bb, a);
            vert(vc, pose, m, s, s, 0, 0, rr, gg, bb, a);
            vert(vc, pose, m, s, -s, 0, 1, rr, gg, bb, a);
            ps.popPose();
            buffers.endBatch(FRAMES[p.frame]);             // keep far-to-near order across sprite frames
        }
    }

    private static void vert(VertexConsumer vc, PoseStack.Pose pose, Matrix4f m, float x, float y, float u, float v, int r, int g, int b, int a) {
        vc.addVertex(m, x, y, 0).setColor(r, g, b, a).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(0xF000F0).setNormal(pose, 0, 0, 1);
    }
}
