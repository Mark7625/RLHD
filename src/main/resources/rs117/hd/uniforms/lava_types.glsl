#pragma once

#include LAVA_TYPE_COUNT

struct LavaType {
    float duration;
    float scale;
    float crustThreshold;
    float crustAmount;
    float crustSharpness;
    float flowStrength;
    float emissiveStrength;
    float specularStrength;
    float specularGloss;
    float waveStrength;
    float surfaceGlow;
    float irradianceStrength;
    float animSpeed;
    vec3 crustColor;
    vec3 magmaColor;
    vec3 hotColor;
};

layout(std140) uniform UBOLavaTypes {
    LavaType LavaTypeArray[LAVA_TYPE_COUNT];
};

#include LAVA_TYPE_GETTER
