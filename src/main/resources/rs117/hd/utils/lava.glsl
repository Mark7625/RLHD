/*
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
 * All rights reserved.
 */
#include <uniforms/global.glsl>
#include <uniforms/lava_types.glsl>

#include <utils/misc.glsl>
#include <utils/specular.glsl>
#include <utils/lights.glsl>

const mat2 LAVA_FBM_ROT = mat2(
    cos(0.6), sin(0.6),
    -sin(0.6), cos(0.6)
);

// Soft blend kept low — subtle stylization without heavy blur.
const float LAVA_SOFT_BLEND = 0.08;
const float LAVA_TOON_BLEND = 0.0;
const float LAVA_DETAIL_BASE = 1.35;

float lavaEffectiveDetail() {
    return LAVA_DETAIL_BASE;
}

float lavaFbmOctaves4(vec2 p) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 4; i++) {
        value += amplitude * noise(p);
        p = LAVA_FBM_ROT * p * 2.03 + vec2(17.3, 9.1);
        amplitude *= 0.5;
    }
    return value;
}

float lavaFbmOctaves2(vec2 p) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 2; i++) {
        value += amplitude * noise(p);
        p = LAVA_FBM_ROT * p * 2.03 + vec2(17.3, 9.1);
        amplitude *= 0.5;
    }
    return value;
}

float lavaToonHeat(float heat) {
    if (LAVA_TOON_BLEND <= 0.0)
        return heat;
    const float bands = 4.0;
    float stepped = (floor(heat * bands + 0.32) + 0.5) / bands;
    return mix(heat, stepped, LAVA_TOON_BLEND);
}

vec2 lavaWorldUvs(vec3 worldPos, float scale) {
    return -worldPos.xz / (128.0 * scale);
}

float lavaAnimTime(LavaType lavaType) {
    return elapsedTime / max(lavaType.duration, 0.001) * max(lavaType.animSpeed, 0.01);
}

vec2 lavaFlowCoord(vec2 p, float time, float flowStrength) {
    if (flowStrength <= 0.0)
        return p;

    vec2 warp1 = vec2(
        lavaFbmOctaves4(p + vec2(0.0, time * 0.72)),
        lavaFbmOctaves4(p + vec2(5.2, 1.3) + vec2(time * 0.62, 0.0))
    );
    return p + warp1 * (flowStrength * 0.26);
}

float sampleLavaHeatAt(vec2 flowUv, float time, LavaType lavaType) {
    vec2 detailUv = flowUv * lavaEffectiveDetail();
    float sharp = lavaFbmOctaves4(detailUv);
    float soft = lavaFbmOctaves2(detailUv * 0.56 + vec2(2.1, 4.6));
    float heat = mix(sharp, soft, LAVA_SOFT_BLEND);
    if (lavaType.crustAmount <= 0.0)
        return lavaToonHeat(heat);

    float crustNoise = lavaFbmOctaves4(detailUv * 0.55 + vec2(3.1, 7.4) - vec2(time * 0.1, time * 0.08));
    float crustMask = smoothstep(lavaType.crustSharpness, 1.0, crustNoise * lavaType.crustAmount + heat * 0.2);
    heat = mix(heat, heat * (1.0 - crustMask * 0.55), lavaType.crustAmount);
    return lavaToonHeat(heat);
}

float sampleLavaHeightAt(vec2 flowUv) {
    vec2 detailUv = flowUv * lavaEffectiveDetail();
    return lavaFbmOctaves4(detailUv);
}

float lavaWaveDetail(vec2 p, float time) {
    vec2 wp = p * 3.4 * lavaEffectiveDetail() + vec2(time * 0.19, -time * 0.15);
    return lavaFbmOctaves4(wp);
}

vec3 lavaNormalsFromHeights(float height, float heightX, float heightY, float eps) {
    return normalize(vec3((height - heightX) / eps * 0.72, 1.7, (height - heightY) / eps * 0.72));
}

vec3 lavaHeatTint(LavaType lavaType, float heatBias) {
    return mix(lavaType.magmaColor, lavaType.hotColor, clamp(heatBias, 0.0, 0.42));
}

vec3 sampleLavaColor(float heat, LavaType lavaType) {
    vec3 color = mix(lavaType.crustColor, lavaType.magmaColor, smoothstep(0.0, lavaType.crustThreshold + 0.2, heat));
    float vein = smoothstep(lavaType.crustThreshold + 0.22, 0.9, heat);
    vec3 veinColor = lavaType.magmaColor * (1.08 + vein * 0.22);
    veinColor = mix(veinColor, lavaHeatTint(lavaType, (heat - 0.72) / 0.28), smoothstep(0.8, 0.97, heat));
    color = mix(color, veinColor, vein * vein * 0.55);
    return color;
}

vec3 sampleLavaSurfaceEmission(float heat, LavaType lavaType) {
    float hotMask = smoothstep(0.48, 0.94, heat);
    hotMask = hotMask * hotMask * 0.85 + hotMask * 0.15;
    vec3 glowColor = lavaType.magmaColor * (1.05 + hotMask * 0.35);
    glowColor = mix(glowColor, lavaHeatTint(lavaType, hotMask * 0.35), hotMask);
    return glowColor * lavaType.emissiveStrength * lavaType.surfaceGlow * hotMask;
}

float sampleLavaHeatIrradiance(vec2 flowUv) {
    vec2 detailUv = flowUv * lavaEffectiveDetail();
    float sharp = lavaFbmOctaves2(detailUv);
    float soft = lavaFbmOctaves2(detailUv * 0.56 + vec2(2.1, 4.6));
    return mix(sharp, soft, LAVA_SOFT_BLEND);
}

float sampleLavaHeatIrradianceAt(vec3 worldPos, float time, LavaType lavaType) {
    vec2 uv = lavaWorldUvs(worldPos, lavaType.scale);
    vec2 flowUv = lavaFlowCoord(uv, time, lavaType.flowStrength);
    return sampleLavaHeatIrradiance(flowUv);
}

// Procedural light from molten veins — lights nearby walls/objects without scene lights.
vec3 sampleLavaEmissiveLighting(vec3 worldPos, vec3 normals) {
    float upFacing = max(dot(normals, vec3(0.0, 1.0, 0.0)), 0.0);
    float fill = mix(0.32, 1.0, upFacing);
    vec3 emission = vec3(0.0);

    for (int lavaTypeIndex = 1; lavaTypeIndex < LAVA_TYPE_COUNT; lavaTypeIndex++) {
        LavaType lavaType = getLavaType(lavaTypeIndex);
        float time = lavaAnimTime(lavaType);
        float heat = sampleLavaHeatIrradianceAt(worldPos, time, lavaType);
        float hotMask = smoothstep(0.7, 0.96, heat);
        hotMask *= hotMask;

        vec3 emissionColor = lavaHeatTint(lavaType, hotMask * 0.35);
        float strength = lavaType.emissiveStrength * lavaType.irradianceStrength * hotMask * 0.42;
        emission = max(emission, emissionColor * strength * fill);
    }

    return emission;
}

vec4 sampleLava(int lavaTypeIndex, vec3 viewDir) {
    LavaType lavaType = getLavaType(lavaTypeIndex);
    float time = lavaAnimTime(lavaType);
    vec2 uv = worldUvs(lavaType.scale);
    vec2 flowUv = lavaFlowCoord(uv, time, lavaType.flowStrength);

    float heat = sampleLavaHeatAt(flowUv, time, lavaType);
    vec3 baseColor = sampleLavaColor(heat, lavaType);
    vec3 surfaceEmission = sampleLavaSurfaceEmission(heat, lavaType);

    float eps = 0.013 / lavaType.scale;
    float height = sampleLavaHeightAt(flowUv);
    float heightX = sampleLavaHeightAt(flowUv + vec2(eps, 0.0));
    float heightY = sampleLavaHeightAt(flowUv + vec2(0.0, eps));
    float wave = lavaType.waveStrength;
    height += lavaWaveDetail(flowUv, time) * wave;
    heightX += lavaWaveDetail(flowUv + vec2(eps, 0.0), time) * wave;
    heightY += lavaWaveDetail(flowUv + vec2(0.0, eps), time) * wave;
    vec3 normals = lavaNormalsFromHeights(height, heightX, heightY, eps);

    float lightDotNormals = dot(normals, lightDir);
    float viewDotNormals = dot(viewDir, normals);

    vec3 ambientLightOut = ambientColor * ambientStrength * 0.35;
    vec3 dirLightColor = lightColor * lightStrength * max(lightDotNormals, 0.0) * 0.25;
    vec3 underglowOut = underglowColor * max(normals.y, 0.0) * underglowStrength * 0.5;

    vec3 lightReflectDir = reflect(-lightDir, normals);
    vec3 specularOut = lightColor * lightStrength * specular(
        vec3(1.0),
        viewDir,
        lightReflectDir,
        vec3(lavaType.specularGloss * 0.32),
        vec3(lavaType.specularStrength * smoothstep(0.52, 1.0, heat) * 0.28)
    );

    vec3 pointLightsOut = vec3(0.0);
    #if DYNAMIC_LIGHTS
    if (pointLightsCount > 0) {
        vec3 pointLightsSpecularOut = vec3(0.0);
        calculateLighting(
            IN.position,
            normals,
            viewDir,
            vec3(1.0),
            vec3(lavaType.specularGloss),
            vec3(0.0),
            pointLightsOut,
            pointLightsSpecularOut
        );
        pointLightsOut *= vec3(1.0, 0.68, 0.32) * 0.7;
    }
    #endif

    vec3 compositeLight = ambientLightOut + dirLightColor + underglowOut + pointLightsOut;

    vec3 color = baseColor + surfaceEmission + compositeLight * 0.15 + specularOut * 0.16;

    float fresnel = pow(1.0 - clamp(viewDotNormals, 0.0, 1.0), 2.6);
    color += lavaHeatTint(lavaType, 0.25) * fresnel * smoothstep(0.62, 0.96, heat) * 0.025;

    return vec4(color, 1.0);
}
