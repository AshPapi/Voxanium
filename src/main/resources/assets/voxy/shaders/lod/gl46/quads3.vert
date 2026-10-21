#version 460 core
#extension GL_ARB_gpu_shader_int64 : enable

#ifdef GL_ARB_gpu_shader_int64
#define QUAD_DATA_USE_64_BIT
#endif


#ifdef USE_NV_JANK
#extension GL_NV_gpu_shader5 : enable
#endif

#define QUAD_BUFFER_BINDING 1
#define MODEL_BUFFER_BINDING 3
#define MODEL_COLOUR_BUFFER_BINDING 4
#define POSITION_SCRATCH_BINDING 5
#define LIGHTING_SAMPLER_BINDING 1

#ifdef USE_SINGLE_TRI
#define USE_NV_BARRY
#endif

#import <voxy:lod/quad_format.glsl>
#import <voxy:lod/block_model.glsl>
#import <voxy:lod/gl46/bindings.glsl>
#import <voxy:lod/quad_util.glsl>

layout(location = 0) out flat uvec4 interData;
#ifndef USE_NV_BARRY
layout(location = 1) out vec2 uv;
#endif

#ifdef USE_NV_JANK
#ifdef GL_NV_gpu_shader5
out gl_PerVertex {
    f16vec4 gl_Position;
};
#endif
#endif

#ifdef DEBUG_RENDER
layout(location = 7) out flat uint quadDebug;
#endif

#if defined(TRANSLUCENT) && !defined(USE_NV_JANK)
invariant gl_Position;
#endif

vec2 taaShift();

void main() {
    taaOffset = taaShift();

    Quad rawQuad = quadData[uint(gl_VertexID)>>2];

    #ifdef TRANSLUCENT
    #ifdef TRANSLUCENT_SKIRT_PASS
    if (!isSkirtQuad(rawQuad)) {
    #else
    if (isSkirtQuad(rawQuad)) {
    #endif
        gl_Position =
        #ifdef USE_NV_JANK
        #ifdef GL_NV_gpu_shader5
        f16vec4
        #endif
        #endif
        (vec4(-2.0, -2.0, -2.0, 1.0));
        return;
    }
    #endif

    QuadData quad;
    uvec2 pos = positionBuffer[gl_BaseInstance];
    setupQuad(quad, rawQuad, pos, (gl_VertexID&3) == 1);

    #ifdef TRANSLUCENT_SKIRT_PASS
    if (cameraSubPos.y < quad.basePoint.y + (quad.axis == 1u ? quad.quadSizeAddin.y : quad.quadSizeAddin.x) * quad.lodScale) {
        gl_Position =
        #ifdef USE_NV_JANK
        #ifdef GL_NV_gpu_shader5
        f16vec4
        #endif
        #endif
        (vec4(-2.0, -2.0, -2.0, 1.0));
        return;
    }
    #endif

    uint cornerId = gl_VertexID&3;

    gl_Position =
    #ifdef USE_NV_JANK
    #ifdef GL_NV_gpu_shader5
    f16vec4
    #endif
    #endif
    (getQuadCornerPos(quad, cornerId));


    #ifndef USE_NV_BARRY
    uv = getCornerUV(quad, cornerId);
    #endif

    interData = quad.attributeData;


    #ifdef DEBUG_RENDER
    quadDebug = uint(gl_VertexID)>>2;
    #endif
}

#ifndef TAA_PATCH
vec2 taaShift() {return vec2(0.0);}
#endif