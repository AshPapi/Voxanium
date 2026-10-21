#version 460 core
#extension GL_ARB_gpu_shader_int64 : enable

#define QUAD_BUFFER_BINDING 1
#define SECTION_METADATA_BUFFER_BINDING 2
#define MODEL_BUFFER_BINDING 3
#define MODEL_COLOUR_BUFFER_BINDING 4
#define LIGHTING_SAMPLER_BINDING 1


#import <voxy:lod/quad_format.glsl>
#import <voxy:lod/block_model.glsl>
#import <voxy:lod/gl46/bindings.glsl>

layout(location = 6) out flat uint quadDebug;

uint extractLodLevel() {
    return uint(gl_BaseInstance)>>27;
}

ivec3 extractRelativeLodPos() {
    return (ivec3(gl_BaseInstance)<<ivec3(5,14,23))>>ivec3(23);
}

vec4 uint2vec4RGBA(uint colour) {
    return vec4((uvec4(colour)>>uvec4(24,16,8,0))&uvec4(0xFF))/255.0;
}

vec4 getFaceSize(uint faceData) {
    float EPSILON = 0.001f;

    vec4 faceOffsetsSizes = extractFaceSizes(faceData);

    faceOffsetsSizes.xz -= vec2(EPSILON);
    faceOffsetsSizes.yw += vec2(EPSILON);

    faceOffsetsSizes.yw -= faceOffsetsSizes.xz;

    return faceOffsetsSizes;
}

vec3 swizzelDataAxis(uint axis, vec3 data) {
    if (axis == 0) {
        data = data.xzy;
    }
    if (axis == 2) {
        data = data.zxy;
    }
    return data;
}

void main() {
    int cornerIdx = gl_VertexID&3;
    Quad quad = quadData[uint(gl_VertexID)>>2];
    uint face = extractFace(quad);
    uint modelId = extractStateId(quad);
    BlockModel model = modelData[modelId];
    uint faceData = model.faceData[face];

    uint lodLevel = extractLodLevel();


    ivec2 quadSize = extractSize(quad);


    vec4 faceSize = getFaceSize(faceData);

    vec2 cQuadSize = (faceSize.yw + quadSize - 1) * vec2((cornerIdx>>1)&1, cornerIdx&1);

    vec3 cornerPos = extractPos(quad);
    float depthOffset = extractFaceIndentation(faceData);
    cornerPos += swizzelDataAxis(face>>1, vec3(faceSize.xz, mix(depthOffset, 1-depthOffset, float(face&1u))));


    vec3 origin = vec3(((extractRelativeLodPos()<<lodLevel) - (baseSectionPos&(ivec3((1<<lodLevel)-1))))<<5);
    gl_Position = MVP*vec4((cornerPos+swizzelDataAxis(face>>1,vec3(cQuadSize,0)))*(1<<lodLevel)+origin, 1.0);


    uint hash = lodLevel*1231421+123141;
    hash ^= hash>>16;
    hash = hash*1231421+123141;
    hash ^= hash>>16;
    hash = hash * 1827364925 + 123325621;
    quadDebug = hash;
}