#pragma once

layout(std140) uniform UBOOcclusion {
    vec3 positions[2000];
    vec3 sizes[2000];
};

