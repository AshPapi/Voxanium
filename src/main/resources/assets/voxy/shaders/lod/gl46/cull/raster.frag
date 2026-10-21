#version 460 core
#define VISIBILITY_BUFFER_BINDING 2
layout(binding = VISIBILITY_BUFFER_BINDING, std430) restrict buffer VisibilityBuffer {
    uint visibilityData[];
};

layout(early_fragment_tests) in;

layout(location = 0) flat in uint id;
layout(location = 1) flat in uint value;

void main() {
    visibilityData[id] = value;
}