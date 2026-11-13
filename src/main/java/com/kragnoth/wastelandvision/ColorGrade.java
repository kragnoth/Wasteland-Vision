package com.kragnoth.wastelandvision;

/** Pure int->int colour remaps driven by {@link Atmosphere.Tint}. No allocation on the hot path. */
public final class ColorGrade {
    private ColorGrade() {}

    /** Moves {@code rgb} toward the tint colour, scaled by source luminance so dark biomes stay dark. */
    public static int apply(int rgb, Atmosphere.Tint t) {
        float r = ((rgb >> 16) & 0xFF) / 255F;
        float g = ((rgb >> 8) & 0xFF) / 255F;
        float b = (rgb & 0xFF) / 255F;
        float lum = 0.299F * r + 0.587F * g + 0.114F * b;
        float scale = Math.min(1.6F, lum * 2.0F) * t.brightness();  // mid-lum source ~ tint colour
        r += (t.r() * scale - r) * t.amount();
        g += (t.g() * scale - g) * t.amount();
        b += (t.b() * scale - b) * t.amount();
        return (rgb & 0xFF000000) | (c(r) << 16) | (c(g) << 8) | c(b);
    }

    /** Same, for fog colour floats (0..1) in place. */
    public static void apply(float[] rgb, Atmosphere.Tint t) {
        int out = apply((c(rgb[0]) << 16) | (c(rgb[1]) << 8) | c(rgb[2]), t);
        rgb[0] = ((out >> 16) & 0xFF) / 255F;
        rgb[1] = ((out >> 8) & 0xFF) / 255F;
        rgb[2] = (out & 0xFF) / 255F;
    }

    /** Same, for a 0..1 double triple (cloud colour); returns {r, g, b}. */
    public static double[] apply(double r, double g, double b, Atmosphere.Tint t) {
        float[] rgb = {(float) r, (float) g, (float) b};
        apply(rgb, t);
        return new double[] {rgb[0], rgb[1], rgb[2]};
    }

    private static int c(float v) {
        int i = (int) (v * 255F + 0.5F);
        return i < 0 ? 0 : Math.min(i, 255);
    }
}
