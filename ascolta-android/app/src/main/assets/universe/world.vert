uniform mat4 uMvp;
uniform mat4 uModel;
uniform mediump float uTime;
uniform mediump float uSea;
attribute vec3 aPosition;
attribute vec3 aColor;
attribute vec3 aNormal;
varying mediump vec3 vColor;
varying mediump vec3 vNormal;
varying mediump vec3 vWorld;
void main() {
    vec4 p = uModel * vec4(aPosition, 1.0);
    if (uSea > 0.5) p.y += 0.025 * sin(p.x * 1.7 + uTime * 0.7) * cos(p.z * 1.5 + uTime * 0.5);
    vWorld = p.xyz;
    vColor = aColor;
    vNormal = normalize(mat3(uModel) * aNormal);
    gl_Position = uMvp * vec4(p.xyz, 1.0);
}
