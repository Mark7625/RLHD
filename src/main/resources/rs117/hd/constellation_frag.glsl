#version 330

#include <uniforms/global.glsl>
#include <uniforms/sky.glsl>

#include <utils/output_transform.glsl>

in float vAlpha;
in float vAcross;

out vec4 FragColor;

void main() {
    if (!uboSky.enabled || vAlpha <= 0.0)
        discard;

    // Soft-edged core, the same anti-flicker rationale as the star sprites' gaussian falloff.
    float coverage = 1.0 - smoothstep(0.45, 1.0, abs(vAcross));
    if (coverage <= 0.0)
        discard;

    vec3 color = vec3(0.6, 0.78, 1.0) * vAlpha * coverage * 0.009;
    color = linearToSrgb(color);
    color = applyColorAdjustments(color);
    color = applyOutputCorrection(color);
    FragColor = vec4(color, 0.0);
}
