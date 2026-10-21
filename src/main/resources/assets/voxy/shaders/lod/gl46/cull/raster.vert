#version 460 core
#extension GL_ARB_gpu_shader_int64 : enable
#define VISIBILITY_ACCESS

#define SECTION_METADATA_BUFFER_BINDING 1
#define VISIBILITY_BUFFER_BINDING 2
#define INDIRECT_SECTION_LOOKUP_BINDING 3

#import <voxy:lod/section.glsl>
#import <voxy:lod/gl46/bindings.glsl>
#import <voxy:util/depthutils.glsl>

layout(location = 0) flat out uint id;
layout(location = 1) flat out uint value;


#ifdef TAA
vec2 getTAA();
#endif

void main() {
    uint sid = indirectLookup[gl_InstanceID];

    SectionMeta section = sectionData[sid];

    uint detail = extractDetail(section);
    ivec3 ipos = extractPosition(section);
    ivec3 aabbOffset = extractAABBOffset(section);
    ivec3 size = extractAABBSize(section);

    ivec3 pos = (((ipos<<detail)-baseSectionPos)<<5);

    const float EXPANSION = 1.0f;


    vec3 offset = aabbOffset-EXPANSION;
    offset += vec3(gl_VertexID&1, (gl_VertexID>>2)&1, (gl_VertexID>>1)&1)*(size+2*EXPANSION);

    gl_Position = MVP * vec4(vec3(pos)+offset*(1<<detail),1);

    gl_Position.z += (CLOSER_SIGN*0.000001f) * gl_Position.w;

    #ifdef TAA
    gl_Position.xy += getTAA()*gl_Position.w;
    #endif

    id = sid;

    uint previous = visibilityData[sid]&0x7fffffffu;
    bool wasVisibleLastFrame = previous==(frameId-1);
    value = (frameId&0x7fffffffu)|(uint(wasVisibleLastFrame)<<31);
}


#import <voxy:util/depthutils.glsl>
