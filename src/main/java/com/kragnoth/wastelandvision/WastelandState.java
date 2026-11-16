package com.kragnoth.wastelandvision;

/**
 * Global on/off flag plus a few per-vision values read from hot paths (mixins, meshing threads).
 * Kept free of client-only imports so common-side mixins can read it.
 */
public final class WastelandState {
    public static volatile boolean active = false;
    /** Per-player lock sent by the server (/wastelandvision lock); "" = none. Reset on logout. */
    public static volatile String serverLock = "";
    /** Folder id of the vision currently compiled into SubstitutionTable. */
    public static volatile String visionId = "";
    public static volatile int visiblePlantPercent = 20;
    public static volatile boolean hidePlantOutlines = true;

    /**
     * OPT-IN hallucination. Block models are only swapped while the current thread is inside a known
     * world mesher (vanilla SectionCompiler, Sodium's meshing task, Sable's sub-level mesher) or a
     * terrain particle. Every other caller of getBlockModel - map mods, LOD mods, Create's cached
     * wheels/contraptions/schematics, unknown mods - always gets the REAL model, so nothing can cache
     * the hallucination.
     */
    private static final ThreadLocal<Boolean> MESHING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static boolean meshing() {
        return MESHING.get();
    }

    public static void setMeshing(boolean on) {
        MESHING.set(on);
        if (!on) DEPTH_HOLDER.get()[0] = 0;
    }

    /** Nested enter/exit (an outer compile overload delegating to an inner one). */
    private static final ThreadLocal<int[]> DEPTH_HOLDER = ThreadLocal.withInitial(() -> new int[1]);

    public static void enterMeshing() {
        DEPTH_HOLDER.get()[0]++;
        MESHING.set(Boolean.TRUE);
    }

    public static void exitMeshing() {
        int[] d = DEPTH_HOLDER.get();
        if (--d[0] <= 0) { d[0] = 0; MESHING.set(Boolean.FALSE); }
    }

    /** Set the first time the Sodium hook fires (to warn if Sodium is present but the hook didn't apply). */
    public static volatile boolean sodiumHookSeen = false;

    private WastelandState() {}
}
