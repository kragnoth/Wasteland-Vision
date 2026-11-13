package com.kragnoth.wastelandvision;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/**
 * The active vision's "atmosphere" block: biome tint targets, fog and post shader. Any entry left
 * out of vision.json is simply not touched. Read by BiomeMixin (any thread) and the client hooks.
 */
public final class Atmosphere {
    /** Pull a colour toward {@code r,g,b} by {@code amount}, scaled by source luminance * brightness. */
    public record Tint(float r, float g, float b, float amount, float brightness) {}

    public static volatile Tint grass, foliage, water, sky, fog, clouds;

    /**
     * One "ambient_particles" entry: tried at every random position the client already samples for
     * ambient effects (ClientLevel#doAnimateTick, ~1300 per tick within 32 blocks) - same mechanism
     * as the basalt deltas' ash. Only spawns in air; {@code skyOnly} keeps it out of caves and roofs.
     */
    public record AmbientParticle(ParticleOptions options, float probability, boolean skyOnly) {}

    public static volatile AmbientParticle[] particles = new AmbientParticle[0];
    /** Terrain fog far plane cap in blocks; <= 0 leaves fog distance alone. */
    public static volatile float fogDistance = 0;
    @Nullable public static volatile ResourceLocation postShader;

    private Atmosphere() {}

    public static void clear() {
        grass = foliage = water = sky = fog = clouds = null;
        particles = new AmbientParticle[0];
        fogDistance = 0;
        postShader = null;
    }

    public static void load(@Nullable JsonObject atm) {
        clear();
        if (atm == null) return;
        grass = tint(atm, "grass");
        foliage = tint(atm, "foliage");
        water = tint(atm, "water");
        sky = tint(atm, "sky");
        fog = tint(atm, "fog");
        clouds = tint(atm, "clouds");
        particles = particles(atm);
        if (atm.has("fog_distance")) fogDistance = atm.get("fog_distance").getAsFloat();
        if (atm.has("post_shader") && !atm.get("post_shader").isJsonNull()) {
            postShader = ResourceLocation.tryParse(atm.get("post_shader").getAsString());
        }
    }

    /** "ambient_particles": [{"type": "minecraft:ash", "probability": 0.006, "sky_only": true}, ...] */
    private static AmbientParticle[] particles(JsonObject atm) {
        List<AmbientParticle> out = new ArrayList<>();
        JsonElement e = atm.get("ambient_particles");
        if (e == null || !e.isJsonArray()) return new AmbientParticle[0];
        for (JsonElement x : e.getAsJsonArray()) {
            try {
                JsonObject o = x.getAsJsonObject();
                ResourceLocation id = ResourceLocation.parse(o.get("type").getAsString());
                ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.get(id);
                if (!(type instanceof ParticleOptions opts)) {
                    // only simple particle types work (ash, white_ash, smoke, spore_blossom_air, ...):
                    // ones that need extra data (dust colour, block state) can't be built from a name
                    throw new IllegalArgumentException(type == null ? "unknown particle " + id
                            : id + " needs extra data - use a simple particle type");
                }
                float p = o.has("probability") ? o.get("probability").getAsFloat() : 0.005F;
                boolean sky = !o.has("sky_only") || o.get("sky_only").getAsBoolean();
                if (p > 0) out.add(new AmbientParticle(opts, Math.min(1F, p), sky));
            } catch (Exception ex) {
                com.mojang.logging.LogUtils.getLogger().error("Wasteland Vision: atmosphere.ambient_particles entry skipped: {}", ex.toString());
            }
        }
        return out.toArray(new AmbientParticle[0]);
    }

    @Nullable
    private static Tint tint(JsonObject atm, String key) {
        JsonElement e = atm.get(key);
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        String hex = o.get("color").getAsString().replace("#", "");
        int rgb = Integer.parseInt(hex, 16);
        float amount = o.has("amount") ? o.get("amount").getAsFloat() : 0.8F;
        float brightness = o.has("brightness") ? o.get("brightness").getAsFloat() : 1.0F;
        return new Tint(((rgb >> 16) & 0xFF) / 255F, ((rgb >> 8) & 0xFF) / 255F, (rgb & 0xFF) / 255F, amount, brightness);
    }
}
