#pragma once

#include <utils/constants.glsl>

const vec3 RAINBOW_SPECTRUM[6] = vec3[6](
    vec3(0.5, 0.0, 0.5),
    vec3(0.0, 0.0, 1.0),
    vec3(0.0, 0.5, 0.0),
    vec3(1.0, 1.0, 0.0),
    vec3(1.0, 0.5, 0.0),
    vec3(1.0, 0.0, 0.0)
);

vec3 rainbowSpectrum(float t) {
    t = clamp(t, 0.0, 1.0) * 5.0;
    int i = int(floor(t));
    float f = t - float(i);
    return mix(RAINBOW_SPECTRUM[i], RAINBOW_SPECTRUM[min(i + 1, 5)], f);
}

vec3 proceduralRainbow(vec3 viewDir, vec3 sunDir) {
    vec3 v = normalize(viewDir);
    vec3 s = normalize(sunDir);

    float sunElevation = asin(clamp(-s.y, -1.0, 1.0));
    float sunAzimuth = atan(s.z, s.x);

    const float elevationBias = radians(12.0);
    float antiSunElevation = -sunElevation - elevationBias;
    float antiSunAzimuth = sunAzimuth + PI;

    vec3 antiSun = vec3(
        cos(antiSunElevation) * cos(antiSunAzimuth),
        -sin(antiSunElevation),
        cos(antiSunElevation) * sin(antiSunAzimuth)
    );

    float theta = acos(clamp(dot(v, antiSun), -1.0, 1.0));

    const float innerRadius = radians(40.2);
    const float outerRadius = radians(42.0);
    const float margin = radians(0.8);

    float envelope =
        smoothstep(innerRadius - margin, innerRadius, theta) *
        (1.0 - smoothstep(outerRadius, outerRadius + margin, theta));
    if (envelope < 0.001)
        return vec3(0.0);

    float azimuth = atan(v.z, v.x);
    float deltaAzimuth = mod(azimuth - antiSunAzimuth + PI, TAU) - PI;

    const float halfSpan = radians(55.0);
    const float edgeMargin = radians(11.0);
    envelope *= 1.0 - smoothstep(halfSpan - edgeMargin, halfSpan, abs(deltaAzimuth));
    if (envelope < 0.001)
        return vec3(0.0);

    float t = (theta - innerRadius) / (outerRadius - innerRadius);
    return rainbowSpectrum(t) * envelope;
}
