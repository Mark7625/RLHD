#include <uniforms/global.glsl>
#include <uniforms/lava_types.glsl>

#include <utils/misc.glsl>
#include <utils/specular.glsl>
#include <utils/lights.glsl>

const mat2 LAVA_FBM_ROT = mat2(
    cos(0.6), sin(0.6),
    -sin(0.6), cos(0.6)
);

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

float lavaFbmOctaves3(vec2 p) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 3; i++) {
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
        lavaFbmOctaves3(p + vec2(0.0, time * 0.72)),
        lavaFbmOctaves3(p + vec2(5.2, 1.3) + vec2(time * 0.62, 0.0))
    );
    return p + warp1 * (flowStrength * 0.26);
}

float sampleLavaHeatAt(vec2 flowUv, vec2 uv, float time, LavaType lavaType, out float sharpHeight) {
    vec2 detailUv = flowUv * lavaEffectiveDetail();
    float sharp = lavaFbmOctaves4(detailUv);
    sharpHeight = sharp;
    float heat = sharp;
    if (lavaType.crustAmount <= 0.0)
        return lavaToonHeat(heat);

    // Crust on unwarped UVs — 2-octave flow warp smears crust when sampled on flowUv.
    vec2 crustDetailUv = uv * lavaEffectiveDetail();
    float crustNoise = lavaFbmOctaves2(crustDetailUv * 0.52 + vec2(3.1, 7.4) - vec2(time * 0.06, time * 0.05));
    float crustBand = mix(0.18, 0.12, lavaType.crustSharpness);
    float crustMask = smoothstep(
        lavaType.crustSharpness,
        lavaType.crustSharpness + crustBand,
        crustNoise * (0.88 + lavaType.crustAmount * 0.28) + heat * 0.06
    );
    heat = mix(heat, heat * (1.0 - crustMask * 0.86), lavaType.crustAmount);
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
    float crustBlend = smoothstep(0.0, lavaType.crustThreshold + 0.14, heat);
    crustBlend = pow(crustBlend, mix(1.0, 1.4, lavaType.crustSharpness));
    vec3 color = mix(lavaType.crustColor, lavaType.magmaColor, crustBlend);
    float vein = smoothstep(lavaType.crustThreshold + 0.18, 0.9, heat);
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

// 0 at terrain edge, 1 deep inside lava (from shoreline vertex colors).
float lavaShoreLavaAmount() {
    return clamp(dot(IN.texBlend, (fAlphaBiasHsl & 127) / 127.0), 0.0, 1.0);
}

float lavaShoreEdgeBand(float shoreLineMask, float lavaAmount) {
    if (shoreLineMask <= 0.001)
        return 0.0;
    float edgeBand = min(shoreLineMask, 0.88);
    // More lava on the triangle → thicker crust inland; narrow strips stay thin.
    float thickness = mix(0.36, 0.76, smoothstep(0.15, 0.68, lavaAmount));
    float sharpness = mix(2.85, 2.2, smoothstep(0.15, 0.68, lavaAmount));
    return clamp(pow(1.0 - ((1.0 - edgeBand) / thickness), sharpness), 0.0, 1.0);
}

// Flat shore crust plates: x = tone, y = unused, z = solid crust cover.
vec3 lavaShoreCrustPlates(vec2 crustUv, float edgeBand) {
    if (edgeBand <= 0.001)
        return vec3(0.0);

    float plateNoise = lavaFbmOctaves2(crustUv * 0.26 + vec2(3.8, 9.6));
    float crustCover = smoothstep(0.14, 0.48, plateNoise) * edgeBand;
    float tone = lavaFbmOctaves2(crustUv * 0.55 + vec2(1.4, 6.2));
    return vec3(tone, 0.0, crustCover);
}

// Glowing sulfuric crust — bright yellow, softer toward the terrain edge.
vec3 lavaShoreCrustColor(float plateTone, float terrainEdge, LavaType lavaType) {
    vec3 yellowLight = mix(lavaHeatTint(lavaType, 0.28), vec3(1.08, 0.9, 0.28), 0.55);
    vec3 yellowMid = mix(lavaType.magmaColor * 0.72, vec3(0.95, 0.76, 0.2), 0.55);
    vec3 yellowDark = vec3(0.78, 0.6, 0.14);
    vec3 edgeAmber = vec3(0.58, 0.44, 0.1);

    float tone = smoothstep(0.15, 0.85, 0.45 + plateTone * 0.5);
    vec3 color = mix(yellowDark, mix(yellowMid, yellowLight, tone), tone);
    color = mix(color, edgeAmber, terrainEdge * 0.45);
    return color;
}

vec3 lavaShoreCrustGlow(vec3 crustColor, float plateTone, float terrainEdge, LavaType lavaType) {
    vec3 glowColor = mix(vec3(1.12, 0.92, 0.32), crustColor * 1.35, 0.35);
    float glowStrength = (0.55 + plateTone * 0.35) * lavaType.emissiveStrength * lavaType.surfaceGlow;
    glowStrength *= mix(1.0, 0.62, terrainEdge);
    return glowColor * glowStrength;
}

void applyLavaShoreCrust(
    inout float heat,
    inout vec3 baseColor,
    inout vec3 surfaceEmission,
    out float shoreSolidCrust,
    out float shoreCrustBump,
    vec2 uv,
    float shoreLineMask,
    float lavaAmount,
    LavaType lavaType
) {
    shoreSolidCrust = 0.0;
    shoreCrustBump = 0.0;
    float edgeBand = lavaShoreEdgeBand(shoreLineMask, lavaAmount);
    if (edgeBand <= 0.001)
        return;

    vec2 crustUv = uv * lavaEffectiveDetail() * 1.85;
    vec3 plates = lavaShoreCrustPlates(crustUv, edgeBand);
    float plateTone = plates.x;
    float crustCover = plates.z;

    float terrainEdge = smoothstep(0.44, 0.84, shoreLineMask);
    vec3 crustPlateColor = lavaShoreCrustColor(plateTone, terrainEdge, lavaType);

    float solidCrust = min(crustCover + crustCover * 0.12, 1.0);
    shoreSolidCrust = solidCrust;
    vec3 crustGlow = lavaShoreCrustGlow(crustPlateColor, plateTone, terrainEdge, lavaType);
    baseColor = mix(baseColor, crustPlateColor, solidCrust);
    surfaceEmission = mix(surfaceEmission, crustGlow, solidCrust);
    heat = mix(heat, mix(0.35, 0.55, plateTone), solidCrust * 0.5);
}

// Wall/object fill only — no flow warp, single FBM (cheaper than surface heat).
float sampleLavaHeatIrradianceAt(vec3 worldPos, float time, LavaType lavaType) {
    vec2 detailUv = lavaWorldUvs(worldPos, lavaType.scale) * lavaEffectiveDetail();
    detailUv += vec2(time * 0.08, -time * 0.06);
    return lavaFbmOctaves2(detailUv);
}

// Procedural light from molten veins — lights nearby walls/objects without scene lights.
vec3 sampleLavaEmissiveLighting(vec3 worldPos, vec3 normals) {
    float upFacing = max(dot(normals, vec3(0.0, 1.0, 0.0)), 0.0);
    float fill = mix(0.32, 1.0, upFacing);
    vec3 emission = vec3(0.0);

    for (int lavaTypeIndex = 1; lavaTypeIndex < LAVA_TYPE_COUNT; lavaTypeIndex++) {
        LavaType lavaType = getLavaType(lavaTypeIndex);
        if (lavaType.irradianceStrength <= 0.0)
            continue;

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
    float animTime = lavaAnimTime(lavaType);
    vec2 uv = worldUvs(lavaType.scale);

    float lavaAmount = lavaShoreLavaAmount();
    float shoreLineMask = 1.0 - lavaAmount;
    float edgeBand = lavaShoreEdgeBand(shoreLineMask, lavaAmount);
    // Shore crust barely moves — interior keeps full flow.
    float edgeAnimScale = mix(1.0, 0.05, edgeBand);
    float edgeFlowScale = mix(1.0, 0.03, edgeBand);
    float time = animTime * edgeAnimScale;
    vec2 flowUv = lavaFlowCoord(uv, time, lavaType.flowStrength * edgeFlowScale);

    float sharpHeight;
    float heat = sampleLavaHeatAt(flowUv, uv, time, lavaType, sharpHeight);
    vec3 baseColor = sampleLavaColor(heat, lavaType);
    vec3 surfaceEmission = sampleLavaSurfaceEmission(heat, lavaType);

    float shoreSolidCrust = 0.0;
    float shoreCrustBump = 0.0;
    applyLavaShoreCrust(heat, baseColor, surfaceEmission, shoreSolidCrust, shoreCrustBump, uv, shoreLineMask, lavaAmount, lavaType);

    float eps = 0.013 / lavaType.scale;
    float height = sharpHeight;
    float heightX = sampleLavaHeightAt(flowUv + vec2(eps, 0.0));
    float heightY = sampleLavaHeightAt(flowUv + vec2(0.0, eps));

    // No surface waves on the shore crust — keep molten ripples only on open lava.
    float wave = lavaType.waveStrength * (1.0 - edgeBand);
    if (wave > 0.001) {
        height += lavaWaveDetail(flowUv, time) * wave;
        heightX += lavaWaveDetail(flowUv + vec2(eps, 0.0), time) * wave;
        heightY += lavaWaveDetail(flowUv + vec2(0.0, eps), time) * wave;
    }
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

    float crustLightScale = mix(0.15, 0.14, shoreSolidCrust);
    float crustSpecScale = mix(0.16, 0.1, shoreSolidCrust);
    vec3 color = baseColor + surfaceEmission + compositeLight * crustLightScale + specularOut * crustSpecScale;

    float fresnel = pow(1.0 - clamp(viewDotNormals, 0.0, 1.0), 2.6);
    vec3 crustFresnel = vec3(1.0, 0.85, 0.3) * lavaType.emissiveStrength * 0.18;
    color += mix(
        lavaHeatTint(lavaType, 0.25) * fresnel * smoothstep(0.62, 0.96, heat) * 0.025,
        crustFresnel * fresnel,
        shoreSolidCrust
    );

    return vec4(color, 1.0);
}
