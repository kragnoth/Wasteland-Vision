package com.kragnoth.wastelandvision.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kragnoth.wastelandvision.WastelandState;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Ambience (vision.json "ambience"): client-only sound tied to the haunt zone (the ghosts' zone).
 *
 *  loops     - looping sounds whose volume follows how deep the player stands in the zone; a loop with
 *              "duck_on_ghost" drops out the moment a ghost phases in and creeps back afterwards. With
 *              "phrases" + "gap_seconds" it instead plays one phrase at a time (never the same twice
 *              running) with random silences between; a ghost stops the phrase and restarts the gap
 *  oneshots  - occasional sounds placed a few blocks around the player (whispers), weighted by depth
 *  midnight  - at 18000 day time a distant bell tolls N times, then ghosts walk outside the zone
 *              (and in greater numbers) for "ghost_surge_seconds"
 *
 * All sounds play in the AMBIENT category (Ambient/Environment slider). A vision without a ghosts block
 * has no zone, so its loops and one-shots play everywhere.
 */
public final class GroveAmbience {
    private GroveAmbience() {}

    /**
     * phrases empty: a continuous loop. phrases set: play one phrase (never the same twice running),
     * then stay silent for a random gap_seconds [min, max] before the next.
     */
    private static final class LoopDef {
        final SoundEvent sound;
        final float volume;
        final boolean duck;
        final List<SoundEvent> phrases = new ArrayList<>();
        float gapMin = 30, gapMax = 120;
        int wait, last = -1;

        LoopDef(SoundEvent sound, float volume, boolean duck) {
            this.sound = sound; this.volume = volume; this.duck = duck;
        }

        boolean phrased() {
            return !phrases.isEmpty();
        }

        void scheduleGap() {
            wait = (int) ((gapMin + RNG.nextFloat() * Math.max(0, gapMax - gapMin)) * 20);
        }
    }
    private record OneShot(SoundEvent sound, float volume, float perMinute, float minDepth) {}

    private static volatile boolean enabled;
    private static final List<LoopDef> loopDefs = new ArrayList<>();
    private static final List<OneShot> oneShots = new ArrayList<>();
    private static float radius = 12, fadeStep = 1 / 60F;
    @Nullable private static SoundEvent bell;
    private static float bellVolume = 0.9F, bellInterval = 3.2F, surgeSeconds = 60;
    private static int tolls = 12;

    private static final List<Loop> playing = new ArrayList<>();
    private static float depth, duck;
    private static long lastDayTime = -1;
    private static int tollsLeft, nextTollIn;
    private static final RandomSource RNG = RandomSource.create();

    public static void load(@Nullable JsonObject a) {
        stopAll();
        loopDefs.clear();
        oneShots.clear();
        bell = null;
        enabled = false;
        tollsLeft = 0;
        if (a == null) return;
        radius = a.has("depth_radius") ? a.get("depth_radius").getAsFloat() : 12;
        fadeStep = 1F / (20F * Math.max(0.1F, a.has("fade_seconds") ? a.get("fade_seconds").getAsFloat() : 3));
        if (a.has("loops")) for (JsonElement e : a.getAsJsonArray("loops")) {
            JsonObject o = e.getAsJsonObject();
            List<SoundEvent> ph = new ArrayList<>();
            if (o.has("phrases")) for (JsonElement pe : o.getAsJsonArray("phrases")) ph.add(event(pe.getAsString()));
            SoundEvent first = o.has("sound") ? event(o.get("sound").getAsString()) : ph.isEmpty() ? null : ph.get(0);
            if (first == null) continue;
            LoopDef d = new LoopDef(first, f(o, "volume", 0.4F), o.has("duck_on_ghost") && o.get("duck_on_ghost").getAsBoolean());
            d.phrases.addAll(ph);
            if (o.has("gap_seconds")) {
                d.gapMin = o.getAsJsonArray("gap_seconds").get(0).getAsFloat();
                d.gapMax = o.getAsJsonArray("gap_seconds").get(1).getAsFloat();
            }
            d.wait = (int) (RNG.nextFloat() * 20 * 20);   // first phrase within ~20 s of entering
            loopDefs.add(d);
        }
        if (a.has("oneshots")) for (JsonElement e : a.getAsJsonArray("oneshots")) {
            JsonObject o = e.getAsJsonObject();
            oneShots.add(new OneShot(event(o.get("sound").getAsString()), f(o, "volume", 0.4F), f(o, "per_minute", 2), f(o, "min_depth", 0.4F)));
        }
        if (a.has("midnight")) {
            JsonObject m = a.getAsJsonObject("midnight");
            bell = event(m.get("sound").getAsString());
            bellVolume = f(m, "volume", 0.9F);
            tolls = Math.max(1, (int) f(m, "tolls", 12));
            bellInterval = Math.max(0.5F, f(m, "interval_seconds", 3.2F));
            surgeSeconds = f(m, "ghost_surge_seconds", 60);
        }
        enabled = true;
    }

    private static float f(JsonObject o, String k, float d) {
        return o.has(k) ? o.get(k).getAsFloat() : d;
    }

    private static SoundEvent event(String id) {
        return SoundEvent.createVariableRangeEvent(ResourceLocation.parse(id));
    }

    private static void stopAll() {
        Minecraft mc = Minecraft.getInstance();
        for (Loop l : playing) mc.getSoundManager().stop(l);
        playing.clear();
        depth = duck = 0;
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (!enabled || !WastelandState.active || level == null || player == null) {
            if (!playing.isEmpty()) stopAll();
            lastDayTime = -1;
            return;
        }
        if (mc.isPaused()) return;

        // how deep in the zone (smoothed so walking across an edge swells rather than snaps)
        float target = Ghosts.enabled() ? Ghosts.hauntDepth(player.getX(), player.getZ(), radius) : 1F;
        target = Math.max(target, SkyOmens.ambienceOverride);     // eclipse totality: the grove comes to you
        depth += Mth.clamp(target - depth, -0.02F, 0.02F);
        // ducking: snap down when a ghost phases in, recover slowly
        float ghost = Ghosts.visibleGhost();
        duck = ghost > 0.25F ? Math.min(1, duck + 0.2F) : Math.max(0, duck - 0.008F);

        for (LoopDef d : loopDefs) {
            float want = d.volume * depth * (d.duck ? 1 - duck : 1);
            Loop l = find(d);
            if (d.phrased()) {
                if (l != null && l.age > 40 && !mc.getSoundManager().isActive(l)) {   // phrase finished
                    l.done = true; l = null; d.scheduleGap();
                }
                if (l != null && d.duck && duck > 0.9F) {                           // a ghost: the music stops
                    l.done = true; mc.getSoundManager().stop(l); l = null; d.scheduleGap();
                }
                if (l == null) {
                    if (d.wait > 0) d.wait--;
                    else if (want > 0.01F) {
                        int i = d.phrases.size() == 1 ? 0 : RNG.nextInt(d.phrases.size() - 1);
                        if (d.phrases.size() > 1 && i >= d.last) i++;               // never the same twice running
                        d.last = i;
                        l = new Loop(d, d.phrases.get(i));
                        l.startAt(want);                                             // a record starts, it doesn't fade in
                        playing.add(l);
                        mc.getSoundManager().play(l);
                    }
                }
            } else if (l == null && want > 0.01F) {
                l = new Loop(d, d.sound);
                playing.add(l);
                mc.getSoundManager().play(l);
            }
            if (l != null) l.target = want;
        }
        playing.removeIf(l -> l.isStopped() || l.done);

        for (OneShot o : oneShots) {
            if (depth < o.minDepth) continue;
            if (RNG.nextFloat() < o.perMinute / 1200F * depth) {
                double a = RNG.nextDouble() * Math.PI * 2, r = 4 + RNG.nextDouble() * 6;
                mc.getSoundManager().play(new SimpleSoundInstance(o.sound, SoundSource.AMBIENT, o.volume,
                        0.9F + RNG.nextFloat() * 0.2F, RNG, player.getX() + Math.cos(a) * r, player.getEyeY() + RNG.nextDouble() * 2 - 1,
                        player.getZ() + Math.sin(a) * r));
            }
        }

        // midnight: day time crossing 18000 by normal ticking (not a big /time jump)
        if (bell != null) {
            long day = level.getDayTime() % 24000L;
            if (lastDayTime >= 0 && lastDayTime < 18000 && day >= 18000 && day - lastDayTime < 200) {
                tollsLeft = tolls;
                nextTollIn = 0;
                if (surgeSeconds > 0) Ghosts.surge(level.getGameTime() + (long) ((tolls * bellInterval + surgeSeconds) * 20));
            }
            lastDayTime = day;
            if (tollsLeft > 0 && --nextTollIn <= 0) {
                tollsLeft--;
                nextTollIn = (int) (bellInterval * 20);
                mc.getSoundManager().play(new SimpleSoundInstance(bell.getLocation(), SoundSource.AMBIENT, bellVolume,
                        0.99F + RNG.nextFloat() * 0.02F, RNG, false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true));
            }
        }
    }

    @Nullable
    private static Loop find(LoopDef d) {
        for (Loop l : playing) if (l.def == d && !l.isStopped()) return l;
        return null;
    }

    /** A looping, non-positional sound whose volume glides toward {@link #target}. */
    private static final class Loop extends AbstractTickableSoundInstance {
        final LoopDef def;
        float target;
        int silentTicks, age;
        boolean done;

        Loop(LoopDef def, SoundEvent sound) {
            super(sound, SoundSource.AMBIENT, RandomSource.create());
            this.def = def;
            this.looping = !def.phrased();
            this.delay = 0;
            this.volume = 0.001F;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
        }

        void startAt(float v) {
            volume = v;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            age++;
            volume += Mth.clamp(target - volume, -fadeStep * Math.max(1, def.volume), fadeStep * def.volume);
            if (def.duck && target < volume) volume = Math.max(target, volume - 0.08F);   // the music cuts out fast
            volume = Mth.clamp(volume, 0F, 1F);
            silentTicks = volume < 0.003F && target < 0.003F ? silentTicks + 1 : 0;
            if (silentTicks > 100) {
                done = true;
                stop();
            }
        }
    }
}
