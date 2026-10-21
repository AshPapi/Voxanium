struct Frustum {
    vec4 planes[6];
};

bool testPlane(vec4 plane, vec3 base, float size) {
    return dot(plane.xyz, base+mix(vec3(size), vec3(0), lessThan(plane.xyz, vec3(0)))) >= -plane.w;
}

bool outsideFrustum(in Frustum frustum, vec3 pos, float size) {
#ifdef USE_REVERSE_Z
    return !(testPlane(frustum.planes[0], pos, size) && testPlane(frustum.planes[1], pos, size) &&
           testPlane(frustum.planes[2], pos, size) && testPlane(frustum.planes[3], pos, size) &&
           testPlane(frustum.planes[5], pos, size));
#else
    return !(testPlane(frustum.planes[0], pos, size) && testPlane(frustum.planes[1], pos, size) &&
           testPlane(frustum.planes[2], pos, size) && testPlane(frustum.planes[3], pos, size) &&
           testPlane(frustum.planes[4], pos, size));
#endif
}
