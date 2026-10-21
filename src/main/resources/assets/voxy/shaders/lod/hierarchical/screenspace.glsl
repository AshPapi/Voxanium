
#import <voxy:util/depthutils.glsl>

layout(binding = HIZ_BINDING) uniform sampler2D hizDepthSampler;

bool within(vec2 a, vec2 b, vec2 c) {
    return all(lessThan(a,b)) && all(lessThan(b, c));
}

bool within(vec3 a, vec3 b, vec3 c) {
    return all(lessThan(a,b)) && all(lessThan(b, c));
}

bool within(float a, float b, float c) {
    return a<b && b<c;
}

float crossMag(vec2 a, vec2 b) {
    return abs(a.x*b.y-b.x*a.y);
}

bool checkPointInView(vec4 point) {
    return within(vec3(-point.w,-point.w,0.0f), point.xyz, vec3(point.w));
}

vec3 _minBB = vec3(0.0f);
vec3 _maxBB = vec3(0.0f);
bool _frustumCulled = false;

float _screenSize = 0.0f;

#ifdef TAA
vec2 getTAA();
#endif

UnpackedNode node22;
void setupScreenspace(in UnpackedNode node) {

    node22 = node;

    vec3 basePos = vec3(((node.pos<<node.lodLevel)-camSecPos)<<5)-camSubSecPos;

    _frustumCulled = outsideFrustum(frustum, basePos, float(32<<node.lodLevel));

    if (_frustumCulled) {
        return;
    }

    vec4 P000 = MVP * vec4(basePos, 1);
    mat3x4 Axis = mat3x4(MVP)*float(32<<node.lodLevel);
    vec4 P100 = Axis[0] + P000;
    vec4 P001 = Axis[2] + P000;
    vec4 P101 = Axis[2] + P100;
    vec4 P010 = Axis[1] + P000;
    vec4 P110 = Axis[1] + P100;
    vec4 P011 = Axis[1] + P001;
    vec4 P111 = Axis[1] + P101;

    bool crossesNearPlane = (P000.w <= 0.05f) || (P100.w <= 0.05f) || (P001.w <= 0.05f) || (P101.w <= 0.05f) ||
                            (P010.w <= 0.05f) || (P110.w <= 0.05f) || (P011.w <= 0.05f) || (P111.w <= 0.05f);

    if (crossesNearPlane) {
        _screenSize = 1.0f;
        _minBB = vec3(0.0f);
        _maxBB = vec3(1.0f);
        return;
    }

    vec3 p000 = NDC2SCREEN(P000.xyz/P000.w);
    vec3 p100 = NDC2SCREEN(P100.xyz/P100.w);
    vec3 p001 = NDC2SCREEN(P001.xyz/P001.w);
    vec3 p101 = NDC2SCREEN(P101.xyz/P101.w);
    vec3 p010 = NDC2SCREEN(P010.xyz/P010.w);
    vec3 p110 = NDC2SCREEN(P110.xyz/P110.w);
    vec3 p011 = NDC2SCREEN(P011.xyz/P011.w);
    vec3 p111 = NDC2SCREEN(P111.xyz/P111.w);


    {
        float ssize = 0;
        {

            vec2 A = p100.xy-p000.xy;
            vec2 B = p010.xy-p000.xy;
            vec2 C = p001.xy-p000.xy;
            ssize += crossMag(A,B);
            ssize += crossMag(A,C);
            ssize += crossMag(C,B);
        }
        {
            vec2 A = p011.xy-p111.xy;
            vec2 B = p101.xy-p111.xy;
            vec2 C = p110.xy-p111.xy;
            ssize += crossMag(A,B);
            ssize += crossMag(A,C);
            ssize += crossMag(C,B);
        }
        ssize *= 0.5f;
        _screenSize = ssize;
    }

    _minBB = min(min(min(p000, p100), min(p001, p101)), min(min(p010, p110), min(p011, p111)));
    _maxBB = max(max(max(p000, p100), max(p001, p101)), max(max(p010, p110), max(p011, p111)));


    #ifdef TAA
    vec2 taaValue = getTAA()*0.5f;
    _minBB.xy += taaValue;
    _maxBB.xy += taaValue;
    #endif

    _minBB = clamp(_minBB, vec3(0), vec3(1));
    _maxBB = clamp(_maxBB, vec3(0), vec3(1));
}

bool outsideFrustum() {
    return _frustumCulled;

}

bool isCulledByHiz() {
    if (_minBB.x <= 0.0f && _minBB.y <= 0.0f && _maxBB.x >= 1.0f && _maxBB.y >= 1.0f) return false;

    if (any(lessThan(abs(_maxBB.xy-_minBB.xy-vec2(1.0f)), vec2(0.000001f)))) return false;

    ivec2 ssize = ivec2(packedHizSize>>16,packedHizSize&0xFFFF);
    vec2 size = (_maxBB.xy-_minBB.xy)*ssize;
    float miplevel = log2(max(max(size.x, size.y),1));

    miplevel = floor(miplevel)-1;
    miplevel = clamp(miplevel, 0, textureQueryLevels(hizDepthSampler)-1);

    int ml = int(miplevel);
    ssize = max(ivec2(1), ssize>>ml);
    ivec2 mxbb = min(ivec2(ceil(_maxBB.xy*ssize)),ssize-1);
    ivec2 mnbb = ivec2(floor(_minBB.xy*ssize));

    float pointSample = (NEAR*3.0f)-1.0f;
    for (int x = mnbb.x; x<=mxbb.x; x++) {
        for (int y = mnbb.y; y<=mxbb.y; y++) {
            float sp = texelFetch(hizDepthSampler, ivec2(x, y), ml).r;
            pointSample = REDUCTION(sp, pointSample);
        }
    }
    float depthTestAgainst;
    #ifdef USE_REVERSE_Z
    depthTestAgainst = _maxBB.z;
    #else
    depthTestAgainst = _minBB.z;
    #endif
    return DEPTH_SCALAR_COMPARE_EQUAL(pointSample,depthTestAgainst);
}



bool shouldDecend() {
    return _screenSize > minSSS;
}
