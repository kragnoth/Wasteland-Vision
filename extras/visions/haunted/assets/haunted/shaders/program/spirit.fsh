#version 150

// Haunted "spirit world" grade (think WoW ghost form):
//  - soft blur that grows toward the screen edges (dreamy periphery)
//  - almost all colour drained, remapped onto a cold blue-grey ramp
//  - shadows lifted into blue, highlights given a faint pale glow
//  - blue vignette

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float Strength;

in vec2 texCoord;
out vec4 fragColor;

vec3 tap(vec2 o) { return texture(DiffuseSampler, texCoord + o).rgb; }

void main() {
    vec3 c = tap(vec2(0.0));
    vec2 px = 1.0 / InSize;

    vec3 blur = (tap(vec2( 2.0, 0.0) * px) + tap(vec2(-2.0, 0.0) * px)
               + tap(vec2( 0.0, 2.0) * px) + tap(vec2( 0.0,-2.0) * px)
               + tap(vec2( 1.5, 1.5) * px) + tap(vec2(-1.5, 1.5) * px)
               + tap(vec2( 1.5,-1.5) * px) + tap(vec2(-1.5,-1.5) * px)) / 8.0;

    vec2 d = texCoord - 0.5;
    float edge = smoothstep(0.18, 0.62, length(d) * 1.3);
    vec3 base = mix(c, blur, edge * 0.85);

    float l = dot(base, vec3(0.299, 0.587, 0.114));
    vec3 spirit = vec3(l * 0.80, l * 0.93, l * 1.16) + vec3(0.015, 0.03, 0.065);   // cold ramp, blue shadows
    spirit += smoothstep(0.55, 1.0, l) * vec3(0.04, 0.07, 0.11);                     // pale ghostly highlights
    vec3 graded = mix(base, spirit, 0.9);

    graded = mix(graded * vec3(0.80, 0.86, 1.0), graded, 1.0 - dot(d, d) * 1.6);   // blue vignette

    fragColor = vec4(clamp(mix(c, graded, Strength), 0.0, 1.0), 1.0);
}
