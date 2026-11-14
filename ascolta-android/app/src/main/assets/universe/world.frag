precision mediump float;
uniform vec3 uEye;
uniform float uTime;
uniform float uSea;
varying vec3 vColor;
varying vec3 vNormal;
varying vec3 vWorld;
void main() {
    float light = 0.58 + 0.42 * max(dot(normalize(vNormal), normalize(vec3(-0.55, 1.0, 0.7))), 0.0);
    vec3 color = vColor * light;
    if (uSea > 0.5) color *= 0.94 + 0.06 * sin(vWorld.x * 3.0 + vWorld.z * 2.0 + uTime * 0.6);
    float fog = smoothstep(13.0, 42.0, distance(uEye, vWorld));
    gl_FragColor = vec4(mix(color, vec3(0.066, 0.19, 0.24), fog * 0.7), 1.0);
}
