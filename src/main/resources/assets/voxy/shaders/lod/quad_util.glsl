#import <voxy:lod/pos_util.glsl>
#import <voxy:lod/lighting.glsl>

vec3 swizzelDataAxis(uint axis, vec3 data) {
    return mix(mix(data.zxy,data.xzy,bvec3(axis==0)),data,bvec3(axis==1));
}

vec4 getFaceSize(uint faceData) {
    float EPSILON = 0.00005f;

    vec4 faceOffsetsSizes = extractFaceSizes(faceData);

    faceOffsetsSizes.xz -= vec2(EPSILON);

    faceOffsetsSizes.yw -= faceOffsetsSizes.xz;

    return faceOffsetsSizes;
}


vec2 taaOffset = vec2(0);

struct QuadData {
    uvec4 attributeData;

    float lodScale;
    uint axis;
    vec3 basePoint;
    vec2 quadSizeAddin;
    vec2 uvCorner;
};

uint makeQuadFlags(uint faceData, uint modelId, ivec2 quadSize, const in BlockModel model, uint face) {
    uint flags = 0;

    flags |= modelId<<16;
    flags |= (uint(quadSize.x-1)<<8)|(uint(quadSize.y-1)<<12);

    {
        flags |= faceHasAlphaCuttout(faceData);
        flags |= uint(any(greaterThan(quadSize, ivec2(1)))) & faceHasAlphaCuttoutOverride(faceData);
    }

    flags |= faceTintState(faceData)<<2;
    flags |= face<<4;

    return flags;
}

uint packVec4(vec4 vec) {
    uvec4 vec_=uvec4(vec*255)<<uvec4(24,16,8,0);
    return vec_.x|vec_.y|vec_.z|vec_.w;
}


#ifndef PATCHED_SHADER
float computeDirectionalFaceTint(bool isShaded, uint face);
#endif

uvec3 makeRemainingAttributes(const in BlockModel model, const in Quad quad, uint lodLevel, uint face) {
    uvec3 attributes = uvec3(0);

    uint lighting = extractLightId(quad);

    uint tintColour = model.colourTint;

    if (modelHasBiomeLUT(model)) {
        tintColour = colourData[tintColour + extractBiomeId(quad)];
    }

    #ifdef PATCHED_SHADER
    attributes.x = lighting;
    attributes.y = tintColour;
    #else
    bool isTranslucent = modelIsTranslucent(model);

    bool isShaded = modelIsShaded(model);
    bool hasAO = isShaded;

    vec4 tinting = getLighting(lighting);

    uint conditionalTinting = 0;
    if (tintColour != uint(-1)) {
        conditionalTinting = tintColour;
    }

    uint addin = 0;
    if (!isTranslucent) {
        tinting.w = 0.0;
        uint encodedData = 0;
        encodedData |= face;
        encodedData |= (lodLevel<<3);
        encodedData |= uint(hasAO)<<6;
        addin = encodedData;
    }

    tinting.rgb *= computeDirectionalFaceTint(isShaded, isSkirtQuad(quad) ? 1u : face);

    attributes.x = packVec4(tinting);
    attributes.y = conditionalTinting;
    attributes.z = addin|(face<<8);
    #endif

    return attributes;
}

void setupQuad(out QuadData quad, const in Quad rawQuad, uvec2 sPos, bool generateAttributes) {
    uint lodLevel = getLoDLevel(sPos);
    float lodScale = 1<<lodLevel;
    ivec3 baseSection = (getLoDPosition(sPos)<<lodLevel) - baseSectionPos;

    uint face = extractFace(rawQuad);
    uint modelId = extractStateId(rawQuad);
    BlockModel model = modelData[modelId];
    uint faceData = model.faceData[face];
    ivec2 quadSize = extractSize(rawQuad);
    bool skirt = isSkirtQuad(rawQuad);

    if (generateAttributes) {
        quad.attributeData.x = makeQuadFlags(skirt ? model.faceData[1] : faceData, modelId, quadSize, model, skirt ? 1u : face);
        quad.attributeData.yzw = makeRemainingAttributes(model, rawQuad, lodLevel, face);
    }

    vec4 faceSize = getFaceSize(faceData);
    if (skirt) {
        float skirtTop = 1.0 - extractFaceIndentation(model.faceData[1]);
        float skirtBottom = max(skirtTop / lodScale - 0.25, 0.0);
        if ((face>>1) == 1u) {
            faceSize.z = skirtBottom;
            faceSize.w = skirtTop - skirtBottom;
        } else {
            faceSize.x = skirtBottom;
            faceSize.y = skirtTop - skirtBottom;
        }
    }
    #ifdef USE_SINGLE_TRI
    faceSize *= 2;
    #endif
    vec3 quadStart = extractPos(rawQuad);
    float depthOffset = extractFaceIndentation(faceData);
    quadStart += swizzelDataAxis(face>>1, vec3(faceSize.xz, mix(depthOffset, 1-depthOffset, float(face&1u))));

    quad.lodScale = lodScale;
    quad.axis = face>>1;
    quad.basePoint = (quadStart*lodScale)+vec3(baseSection<<5);
    #ifdef USE_SINGLE_TRI
    quad.quadSizeAddin = (faceSize.yw + (quadSize - 1)*2);
    #else
    quad.quadSizeAddin = faceSize.yw + quadSize - 1;
    #endif
    quad.uvCorner = faceSize.xz;

    #ifdef PATCHED_GEOMETRIC_FACE
    if (generateAttributes) {
        uint axis = face>>1;
        float plane = axis == 0u ? quad.basePoint.y : (axis == 1u ? quad.basePoint.z : quad.basePoint.x);
        float eye = axis == 0u ? cameraSubPos.y : (axis == 1u ? cameraSubPos.z : cameraSubPos.x);
        quad.attributeData.w = skirt ? 1u : ((axis<<1) | uint(eye > plane));
    }
    #endif
}

vec4 getQuadCornerPos(in QuadData quad, uint cornerId) {
    vec2 cornerMask = vec2((cornerId>>1)&1u, cornerId&1u)*quad.lodScale;
    vec3 point = quad.basePoint + swizzelDataAxis(quad.axis,vec3(quad.quadSizeAddin*cornerMask,0));
    vec4 pos = MVP * vec4(point, 1.0f);
    pos.xy += taaOffset*pos.w;
    return pos;
}

#ifndef USE_NV_BARRY
vec2 getCornerUV(const in QuadData quad, uint cornerId) {
    return quad.uvCorner + quad.quadSizeAddin*vec2((cornerId>>1)&1u, cornerId&1u);
}
#endif

#ifndef PATCHED_SHADER
float computeDirectionalFaceTint(bool isShaded, uint face) {
    if (isShaded) {


        if ((face>>1) == 1) {
            return Z_AXIS_FACE_TINT;
        } else if ((face>>1) == 2) {
            return X_AXIS_FACE_TINT;
        } else if (face == 1) {
            return UP_FACE_TINT;
        }
        return DOWN_FACE_TINT;
    } else {
        return NO_SHADE_FACE_TINT;
    }
}
#endif