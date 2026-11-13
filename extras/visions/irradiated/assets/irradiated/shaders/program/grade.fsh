#version 150

// Irradiated grade: desaturate, push mid-tones toward a sickly yellow-green, lift shadows to a
// murky green, and a heavier vignette.

uniform sampler2D DiffuseSampler;
uniform float Strength;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec3 c = texture(DiffuseSampler, texCoord).rgb;
    float l = dot(c, vec3(0.299, 0.587, 0.114));

    vec3 toxic = vec3(l * 0.92, l * 1.08, l * 0.62);          // sickly yellow-green
    vec3 graded = mix(c, vec3(l), 0.5);
    graded = mix(graded, toxic, 0.55);
    graded += vec3(0.0, 0.025, 0.0) * (1.0 - l);              // green-tinted shadows

    vec2 d = texCoord - 0.5;
    graded *= 1.0 - dot(d, d) * 1.2;

    fragColor = vec4(clamp(mix(c, graded, Strength), 0.0, 1.0), 1.0);
}
