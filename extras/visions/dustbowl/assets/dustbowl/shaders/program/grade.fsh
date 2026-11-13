#version 150

// Wasteland color grade: hue-selective desaturation (greens die hardest),
// sepia/dust warmth, mild contrast, soft vignette.

uniform sampler2D DiffuseSampler;
uniform float Strength;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec3 c = texture(DiffuseSampler, texCoord).rgb;
    float l = dot(c, vec3(0.299, 0.587, 0.114));

    // How "alive-green" is this pixel? 0 = not green, 1 = very green.
    float green = clamp((c.g - max(c.r, c.b)) * 4.0, 0.0, 1.0);
    // Blues (sky/water) get pushed to grey too, a bit less.
    float blue = clamp((c.b - max(c.r, c.g)) * 3.0, 0.0, 1.0);

    vec3 dust = vec3(l * 1.08, l * 0.96, l * 0.78);
    vec3 graded = mix(c, vec3(l), 0.45);              // general desaturation
    graded = mix(graded, dust, 0.30 + 0.60 * green + 0.30 * blue);

    graded = (graded - 0.5) * 1.06 + 0.5;            // contrast

    vec2 d = texCoord - 0.5;
    graded *= 1.0 - dot(d, d) * 0.8;                 // vignette

    fragColor = vec4(clamp(mix(c, graded, Strength), 0.0, 1.0), 1.0);
}
