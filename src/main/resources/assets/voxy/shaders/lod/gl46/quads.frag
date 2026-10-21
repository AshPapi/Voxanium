#version 460 core
#ifdef USE_SINGLE_TRI
#define USE_NV_BARRY
#endif

#ifdef USE_NV_BARRY
#extension GL_NV_fragment_shader_barycentric: require
#endif

layout(binding = 0) uniform sampler2D blockModelAtlas;
layout(binding = 2) uniform sampler2D depthTex;

layout(location = 0) in flat uvec4 interData;
#ifndef USE_NV_BARRY
layout(location = 1) in vec2 uv;
#endif

#ifdef DEBUG_RENDER
layout(location = 7) in flat uint quadDebug;
#endif


#ifndef PATCHED_SHADER
layout(location = 0) out vec4 outColour;
#else

#define MODEL_BUFFER_BINDING 3
#import <voxy:lod/block_model.glsl>

#ifdef PATCHED_LOD_DEPTH_OUTPUT
layout(location = PATCHED_LOD_DEPTH_OUTPUT) out float voxyLodDepthOut;
#endif

#ifdef PATCHED_COVERAGE_OUTPUT
layout(location = PATCHED_COVERAGE_OUTPUT) out float voxyCoverageOut;
#endif

#ifdef PATCHED_SCENE_DEPTH_TEST
bool voxy_sceneDepthHidden();
#endif

#endif

#import <voxy:lod/gl46/bindings.glsl>
#import <voxy:lod/lighting.glsl>


#import <voxy:util/depthutils.glsl>


vec4 uint2vec4RGBA(uint colour) {
    return vec4((uvec4(colour)>>uvec4(24,16,8,0))&uvec4(0xFF))/255.0;
}

uint tintingState() {
    return (interData.x>>2)&3u;
}

bool useDiscard() {
    return (interData.x&1u)==1u;
}

uint getFace() {
    return (interData.x>>4)&7u;
}


uint getModelId() {
    return interData.x>>16;
}

vec2 getBaseUV() {
    uint face = getFace();
    uint modelId = interData.x>>16;
    vec2 modelUV = vec2(modelId&0xFFu, (modelId>>8)&0xFFu)*(1.0/(256.0));
    return modelUV + (vec2(face>>1, face&1u) * (1.0/(vec2(3.0, 2.0)*256.0)));
}


#ifdef PATCHED_SHADER
struct VoxyFragmentParameters {
    vec4 sampledColour;
    vec2 tile;
    vec2 uv;
    uint face;
    uint modelId;
    vec2 lightMap;
    vec4 tinting;
    uint customId;
};

void voxy_emitFragment(VoxyFragmentParameters parameters);
#else

vec4 computeColour(vec2 texturePos, vec4 colour) {

    uint tintingFunction = tintingState();
    bool doTint = tintingFunction==2;
    if (tintingFunction == 1) {
        vec4 tintTest = textureLod(blockModelAtlas, texturePos, 0);
        if (abs(tintTest.r-tintTest.g) < 0.02f && abs(tintTest.g-tintTest.b) < 0.02f) {
            doTint = true;
        }
    }
    if (doTint) {
        colour *= uint2vec4RGBA(interData.z).yzwx;
    }
    return (colour * uint2vec4RGBA(interData.y)) + vec4(0,0,0,float(interData.w&0xFFu)/255);
}

#endif


void main() {
    vec2 tile;
    #ifdef USE_NV_BARRY
    #ifdef USE_SINGLE_TRI
    if (gl_BaryCoordNV.x>=0.5||gl_BaryCoordNV.y>=0.5) discard;
    vec2 uv = gl_BaryCoordNV.yx*(vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu)+1)*2;
    #else
    vec2 uv = mix(gl_BaryCoordNV.yx, 1-gl_BaryCoordNV.xz, gl_PrimitiveID&1)*(vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu)+1);
    #endif
    #endif

    vec2 uv2 = modf(uv, tile)*(1.0/(vec2(3.0,2.0)*256.0));
    vec4 colour;
    vec2 texPos = uv2 + getBaseUV();
    {
        vec2 uvSmol = uv*(1.0/(vec2(3.0,2.0)*256.0));
        vec2 dx = dFdx(uvSmol);
        vec2 dy = dFdy(uvSmol);
        colour = textureGrad(blockModelAtlas, texPos, dx, dy);
    }

    #ifndef PATCHED_SHADER_ALLOW_DERIVATIVES
    if (gl_HelperInvocation) {
        return;
    }
    #endif

    if (any(notEqual(clamp(tile, vec2(0), vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu)), tile))) {
        discard;
        return;
    }

    #ifndef NO_BOUNDING_DEPTH_TEST
    if (DEPTH_SCALAR_COMPARE(gl_FragCoord.z, texelFetch(depthTex, ivec2(gl_FragCoord.xy), 0).r)) {
        discard;
        return;
    }
    #endif

    #ifdef PATCHED_SCENE_DEPTH_TEST
    if (voxy_sceneDepthHidden()) {
        discard;
        return;
    }
    #endif


    #ifndef TRANSLUCENT
    colour.a = 1.0f;
    if (useDiscard() && (textureLod(blockModelAtlas, texPos, 0).a <= 0.1f)) {
    #else
    if (textureLod(blockModelAtlas, texPos, 0).a == 0.0f) {
    #endif
        #ifndef DEBUG_RENDER
        discard;
        return;
        #endif
    }

    #ifndef PATCHED_SHADER_ALLOW_DERIVATIVES
    if (gl_HelperInvocation) {
        return;
    }
    #endif

    #ifndef PATCHED_SHADER
    colour = computeColour(texPos, colour);
    outColour = colour;

    #ifdef DEBUG_RENDER
    uint hash = quadDebug*1231421+123141;
    hash ^= hash>>16;
    hash = hash*1231421+123141;
    hash ^= hash>>16;
    hash = hash * 1827364925 + 123325621;
    outColour = vec4(float(hash&15u)/15, float((hash>>4)&15u)/15, float((hash>>8)&15u)/15, 0);
    #endif

    #else
    uint modelId = getModelId();
    BlockModel model = modelData[modelId];
    uint tintingFunction = tintingState();
    bool doTint = tintingFunction==2;
    if (tintingFunction==1) {
        vec4 tintTest = texture(blockModelAtlas, texPos, -2);
        if (abs(tintTest.r-tintTest.g) < 0.02f && abs(tintTest.g-tintTest.b) < 0.02f) {
            doTint = true;
        }
    }
    vec4 tint = vec4(1);
    if (doTint) {
        tint = uint2vec4RGBA(interData.z).yzwx;
    }

    #ifdef PATCHED_GEOMETRIC_FACE
    uint face = interData.w&7u;
    #else
    uint face = getFace();
    face ^= uint((face&1u)!=uint(gl_FrontFacing!=((face>>1)!=0u)));
    #endif
    #ifdef TRANSLUCENT_SKIRT_PASS
    face = 1u;
    #endif
    voxy_emitFragment(VoxyFragmentParameters(colour, tile, texPos, face, modelId, getLightmapUv(interData.y), tint, model.customId));
    #ifdef PATCHED_COVERAGE_OUTPUT
    voxyCoverageOut = PATCHED_SCENE_DEPTH_CLEAR;
    #endif
    #ifdef PATCHED_LOD_DEPTH_OUTPUT
    voxyLodDepthOut = PATCHED_LOD_DEPTH_VALUE;
    #endif

    #endif
}



#import <voxy:util/depthutils.glsl>
