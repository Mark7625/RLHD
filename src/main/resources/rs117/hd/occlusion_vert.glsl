#version 330

#include <uniforms/occlusion.glsl>

layout (location = 0) in vec3 vPosition;

uniform int offset;
uniform mat4 viewProj;

 void main() {
    int index = offset + gl_InstanceID;
    vec3 worldPosition = vPosition * sizes[index] + positions[index];
    gl_Position = viewProj * vec4(worldPosition, 1.0);
}