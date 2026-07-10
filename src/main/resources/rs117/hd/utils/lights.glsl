#pragma once

#include <uniforms/lights.glsl>

#include <utils/constants.glsl>
#include <utils/specular.glsl>

#if DYNAMIC_LIGHTS
uniform sampler2DArray lightMaskArray;

#define MASK_PROJECTION_AUTO 0
#define MASK_PROJECTION_FLAT 1
#define MASK_PROJECTION_WALL 2
#define MASK_SURFACE_THRESHOLD 0.5

float sampleLightMaskPlane(vec2 planar, float maskLayer)
{
    if (dot(planar, planar) > 1.0)
        return 0.0;

    vec2 uv = planar * 0.5 + 0.5;
    return texture(lightMaskArray, vec3(uv, maskLayer)).r;
}

float sampleLightMask(int lightIdx, vec3 position, vec3 normals, PointLight light)
{
    vec4 maskData = lightMaskData[lightIdx];

    if (maskData.x < 0.0)
        return 1.0;

    float maskLayer = maskData.x;
    float maskScale = max(maskData.y, 0.001);
    int projection = int(maskData.z + 0.5);
    float radius = sqrt(light.position.w);

    vec3 local = position - light.position.xyz;
    float maskRadius = radius * maskScale;
    vec3 n = abs(normals);
    bool isFlat = n.y >= MASK_SURFACE_THRESHOLD;
    bool isWall = !isFlat;

    if (projection == MASK_PROJECTION_FLAT && !isFlat)
        return 1.0;
    if (projection == MASK_PROJECTION_WALL && !isWall)
        return 1.0;

    vec2 planar;
    if (projection == MASK_PROJECTION_FLAT || (projection == MASK_PROJECTION_AUTO && n.y >= n.x && n.y >= n.z))
        planar = -local.xz / maskRadius;
    else if (n.x >= n.z)
        planar = vec2(local.z, -local.y) / maskRadius;
    else
        planar = vec2(local.x, -local.y) / maskRadius;

    return sampleLightMaskPlane(planar, maskLayer);
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

        attenuation *= sampleLightMask(lightIdx, position, normals, light);

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
