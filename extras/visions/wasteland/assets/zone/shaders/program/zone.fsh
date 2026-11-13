#version 150

// "The Zone" grade (S.T.A.L.K.E.R.: Call of Pripyat golden autumn): vibrant AND washed out.
//  - selective saturation: yellows/ochres/rust stay rich, greens drift to olive-gold, blues and the
//    sky are drained
//  - bleach-bypass feel: lifted blacks, softened contrast
//  - olive-sepia cast and a soft overcast glow in the highlights, mild vignette

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float Strength;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec3 c = texture(DiffuseSampler, texCoord).rgb;
    float l = dot(c, vec3(0.299, 0.587, 0.114));

    float warm  = clamp((min(c.r, c.g * 1.2) - c.b) * 3.0, 0.0, 1.0);   // yellow / ochre / orange
    float green = clamp((c.g - max(c.r, c.b)) * 4.0, 0.0, 1.0);
    float blue  = clamp((c.b - max(c.r, c.g)) * 3.0, 0.0, 1.0);

    float sat = 0.45 + 0.75 * warm - 0.15 * green - 0.30 * blue;           // warm vivid, rest drained
    vec3 g = mix(vec3(l), c, sat);
    g = mix(g, vec3(l * 1.05, l * 1.00, l * 0.60), green * 0.35);         // greens -> olive-gold

    g = g * 0.86 + 0.07;                                                   // lifted blacks, low contrast
    g = mix(g, vec3(l * 1.06, l * 1.02, l * 0.80), 0.18);                  // olive-sepia cast
    g += smoothstep(0.6, 1.0, l) * vec3(0.04, 0.035, 0.02);               // overcast highlight glow

    vec2 d = texCoord - 0.5;
    g *= 1.0 - dot(d, d) * 0.6;                                            // mild vignette

    fragColor = vec4(clamp(mix(c, g, Strength), 0.0, 1.0), 1.0);
}
