#pragma once

#include <uniforms/lights.glsl>

#include <utils/constants.glsl>
#include <utils/specular.glsl>

#if DYNAMIC_LIGHTS
uniform sampler2DArray lightMaskArray;

float sampleLightMask(int lightIdx, vec3 position, PointLight light, float outerConeCos) {
    vec4 maskData = lightMaskData[lightIdx];
    float maskLayer = maskData.x;
    if (maskLayer < 0.0)
        return 1.0;

    vec3 lightAxis = light.direction.xyz;
    vec3 toFrag = position - light.position.xyz;
    float along = dot(toFrag, lightAxis);
    if (along <= 0.0)
        return 0.0;

    vec3 up = abs(lightAxis.y) < 0.999 ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0);
    vec3 right = normalize(cross(up, lightAxis));
    up = cross(lightAxis, right);

    vec3 perp = toFrag - lightAxis * along;
    float coneRadius = along * tan(acos(clamp(outerConeCos, -1.0, 1.0)));
    float maskScale = max(maskData.y, 0.001);
    vec2 uv = vec2(dot(perp, right), dot(perp, up)) / (coneRadius * maskScale);
    uv = uv * 0.5 + 0.5;

    // Spotlight cross-section is circular; clip square UV space to the inscribed circle.
    vec2 centeredUv = uv - 0.5;
    float radialSq = dot(centeredUv, centeredUv);
    if (radialSq > 0.25)
        return 0.0;

    return texture(lightMaskArray, vec3(uv, maskLayer)).r;
}

void calculateLight(
    int lightIdx, vec3 position, vec3 normals, vec3 viewDir,
    vec3 texBlend, vec3 specularGloss, vec3 specularStrength,
    inout vec3 pointLightsOut, inout vec3 pointLightsSpecularOut
) {
    PointLight light = PointLightArray[lightIdx];
    vec3 lightToFrag = light.position.xyz - position;
    float distanceSquared = dot(lightToFrag, lightToFrag);
    float radiusSquared = light.position.w;
    if (distanceSquared <= radiusSquared) {
        float attenuation = 1 - sqrt(distanceSquared / radiusSquared);
        attenuation *= attenuation;

        // Spotlight cone attenuation with near-field spill
        float outerConeCos = light.color.w;
        if (outerConeCos > 0.0) {
            vec3 fragDir = normalize(-lightToFrag); // direction from light to fragment
            float spotCos = dot(fragDir, light.direction.xyz);
            float innerConeCos = light.direction.w;
            float coneFactor = smoothstep(outerConeCos, innerConeCos, spotCos);
            // Near the light source, allow omnidirectional spill so the emitter itself glows
            float proximity = 1.0 - sqrt(distanceSquared / radiusSquared);
            float spill = proximity * proximity * proximity; // cubic falloff
            attenuation *= max(coneFactor, spill * 0.5);
            attenuation *= sampleLightMask(lightIdx, position, light, outerConeCos);
        }

        vec3 pointLightColor = light.color.rgb * attenuation;
        vec3 pointLightDir = normalize(lightToFrag);

        float pointLightDotNormals = max(dot(normals, pointLightDir), 0);
        pointLightsOut += pointLightColor * pointLightDotNormals;

        vec3 pointLightReflectDir = reflect(-pointLightDir, normals);
        pointLightsSpecularOut += pointLightColor * specular(texBlend, viewDir, pointLightReflectDir, specularGloss, specularStrength);
    }
}

void calculateLighting(
    vec3 position, vec3 normals, vec3 viewDir,
    vec3 texBlend, vec3 specularGloss, vec3 specularStrength,
    inout vec3 pointLightsOut, inout vec3 pointLightsSpecularOut
) {
    #if TILED_LIGHTING
        ivec2 tileXY = ivec2(gl_FragCoord.xy / sceneResolution * tiledLightingResolution);

        for (int tileLayer = 0; tileLayer < TILED_LIGHTING_LAYER_COUNT; tileLayer++) {
            uvec4 tileLayerData = texelFetch(tiledLightingArray, ivec3(tileXY, tileLayer), 0);
            ivec2 unpackedData = ivec2(0);

            #define PROCESS_TILED_LIGHT_COMPONENT(c)                 \
                if (tileLayerData[c] <= 0u)                          \
                    break;                                           \
                unpackedData = decodePackedLight(tileLayerData[c]);  \
                                                                     \
                if (unpackedData[0] >= 0)                            \
                    calculateLight(unpackedData[0],                  \
                        position, normals, viewDir,                  \
                        texBlend, specularGloss, specularStrength,   \
                        pointLightsOut, pointLightsSpecularOut);     \
                                                                     \
                if (unpackedData[1] >= 0)                            \
                    calculateLight(unpackedData[1],                  \
                        position, normals, viewDir,                  \
                        texBlend, specularGloss, specularStrength,   \
                        pointLightsOut, pointLightsSpecularOut);

            PROCESS_TILED_LIGHT_COMPONENT(0);
            PROCESS_TILED_LIGHT_COMPONENT(1);
            PROCESS_TILED_LIGHT_COMPONENT(2);
            PROCESS_TILED_LIGHT_COMPONENT(3);
        }
    #else
        for (int lightIdx = 0; lightIdx < pointLightsCount; lightIdx++)
            calculateLight(lightIdx, position, normals, viewDir,
                texBlend, specularGloss, specularStrength,
                pointLightsOut, pointLightsSpecularOut);
    #endif
}
#else
#define calculateLighting(position, normals, viewDir, texBlend, specularGloss, specularStrength, pointLightsOut,  pointLightsSpecularOut)
#endif
