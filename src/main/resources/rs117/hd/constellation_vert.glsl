#version 330

#include <uniforms/global.glsl>
#include <uniforms/sky.glsl>

#include <utils/starfield.glsl>

layout(location = 0) in vec3 aDirA; // field-space unit direction of the edge's first node
layout(location = 1) in vec3 aDirB; // field-space unit direction of the edge's second node

out float vAlpha;
out float vAcross; // -1 at one edge of the line, 0 at the center, 1 at the other edge

// Which endpoint (x: 0 = A, 1 = B) and which side of the line (y: -1 or 1) this quad corner is.
const vec2 CORNERS[6] = vec2[6](
    vec2(0.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0),
    vec2(0.0, -1.0), vec2(1.0, 1.0), vec2(0.0, 1.0)
);

// Oversized relative to the visible core so the soft falloff below has room to fade out,
// the same way the star sprite's gaussian falloff needs margin past its visible radius.
const float LINE_WIDTH_PIXELS = 3.0;

vec4 projectStarfieldDir(vec3 dir) {
    return uboGlobal.projectionMatrix * vec4(uboGlobal.cameraPos + dir * 1.0e6, 1.0);
}

void main() {
    vAlpha = 0.0;
    vAcross = 0.0;

    if (uboGlobal.orthographicProjection) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        return;
    }

    vec3 dirA = inverseRotateStarfield(aDirA, uboGlobal.elapsedTime, 1.0);
    vec3 dirB = inverseRotateStarfield(aDirB, uboGlobal.elapsedTime, 1.0);

    vec4 clipA = projectStarfieldDir(dirA);
    vec4 clipB = projectStarfieldDir(dirB);
    if (clipA.w <= 0.0 || clipB.w <= 0.0) {
        // Push off-screen rather than rendering a line through the camera.
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        return;
    }

    vec2 screenA = clipA.xy / clipA.w;
    vec2 screenB = clipB.xy / clipB.w;
    vec2 lineDir = screenB - screenA;
    float lineLen = length(lineDir);
    vec2 normal = lineLen > 1e-6 ? vec2(-lineDir.y, lineDir.x) / lineLen : vec2(1.0, 0.0);

    vec2 corner = CORNERS[gl_VertexID];
    vec4 base = mix(clipA, clipB, corner.x);
    vec2 offset = normal * (LINE_WIDTH_PIXELS * corner.y) / vec2(uboGlobal.sceneResolution) * base.w;

    gl_Position = base;
    gl_Position.xy += offset;
    vAcross = corner.y;

    // Match the stars' night-sky fade, using the edge's average direction.
    float upAmount = -0.5 * (dirA.y + dirB.y);
    float nightFade = smoothstep(-0.26, 0.0, uboSky.sunDir.y) * (1.0 - uboSky.customGradient);
    float horizonShift = nightHorizonOffset(uboSky.starHorizonHeight);
    float horizonFade = smoothstep(horizonShift, 0.12 + horizonShift, upAmount);
    vAlpha = (1.0 - nightFade) * horizonFade * uboSky.starVisibility;
}
