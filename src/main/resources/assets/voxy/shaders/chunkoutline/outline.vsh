#version 460

#import <voxy:util/depthutils.glsl>

layout(binding = 0, std140) uniform SceneUniform {
    mat4 MVP;
    ivec4 cameraBlockPos;
    vec4 negInnerBlock;
};

layout(binding = 1, std430) restrict readonly buffer ChunkPosBuffer {
    ivec2[] chunkPos;
};

ivec3 unpackPos(ivec2 pos) {
    return ivec3(pos.y>>10, (pos.x<<12)>>12, ((pos.y<<22)|int(uint(pos.x)>>10))>>10);
}

bool shouldRender(ivec3 icorner) {
    vec3 corner = vec3(mix(mix(ivec3(0), icorner-1, greaterThan(icorner-1, ivec3(0))), icorner+17, lessThan(icorner+17, ivec3(0))))-negInnerBlock.xyz;
    bool visible = (corner.x*corner.x + corner.z*corner.z) < (negInnerBlock.w*negInnerBlock.w);
    visible = visible && abs(corner.y) < negInnerBlock.w;
    return visible;
}

#ifdef TAA
vec2 getTAA();
#endif

void main() {
    #ifdef VULKAN
    uint id = (uint(gl_InstanceIndex)<<5)+(uint(gl_VertexIndex)>>3);
    #else
    uint id = (gl_InstanceID<<5)+gl_BaseInstance+(gl_VertexID>>3);
    #endif

    ivec3 origin = unpackPos(chunkPos[id])*16;
    origin -= cameraBlockPos.xyz;

    if (!shouldRender(origin)) {
        gl_Position = vec4(-100.0f, -100.0f, -100.0f, 0.0f);
        return;
    }

    ivec3 cubeCornerI = ivec3(gl_VertexID&1, (gl_VertexID>>2)&1, (gl_VertexID>>1)&1)*16;
    gl_Position = MVP * vec4(vec3(cubeCornerI+origin), 1);

    gl_Position.z += CLOSER_SIGN*0.0005f;

    #ifdef TAA
    gl_Position.xy += getTAA()*gl_Position.w;
    #endif
}



#import <voxy:util/depthutils.glsl>
