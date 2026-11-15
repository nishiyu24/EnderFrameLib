package com.nishiyu.enderframelib.core.paint.gl;

/**
 * OpenGL 3.2 Core / GLSL 150 向けの 3D インスタンスシェーダ文字列定義。
 *
 * <p>GLSL 330 の {@code layout(location=...)} は使わず、プログラムリンク前に
 * {@code glBindAttribLocation} で頂点属性を割り当てる。また {@code org.lwjgl} への依存を持たない。</p>
 */
public final class Scene3DShaders {
    public static final int ATTR_POS = 0;
    public static final int ATTR_NORM = 1;
    public static final int ATTR_UV = 2;
    public static final int ATTR_TANGENT = 3;

    public static final int UNIT_INSTANCE_TBO_F = 8;
    public static final int UNIT_INSTANCE_TBO_I = 9;

    private Scene3DShaders() {}

    public static final String VERTEX_GLSL_150 = """
            #version 150
            in vec3 aPos;
            in vec3 aNorm;
            in vec2 aUv;
            in vec4 aTangent;

            uniform samplerBuffer uInstanceTboF;
            uniform isamplerBuffer uInstanceTboI;
            uniform mat4 uViewProj;
            uniform int uBaseInstance;

            out vec3 vWorldPos;
            out vec3 vWorldNorm;
            out vec2 vUv;
            out vec4 vBaseColor;
            out vec4 vPbrFlags;
            out vec3 vEmissive;

            void main() {
                int baseTexel = (gl_InstanceID + uBaseInstance) * 8;
                vec4 c0 = texelFetch(uInstanceTboF, baseTexel + 0);
                vec4 c1 = texelFetch(uInstanceTboF, baseTexel + 1);
                vec4 c2 = texelFetch(uInstanceTboF, baseTexel + 2);
                vec4 c3 = texelFetch(uInstanceTboF, baseTexel + 3);
                mat4 model = mat4(c0, c1, c2, c3);

                vec4 t4 = texelFetch(uInstanceTboF, baseTexel + 4);
                vec4 t5 = texelFetch(uInstanceTboF, baseTexel + 5);
                vec4 t6f = texelFetch(uInstanceTboF, baseTexel + 6);
                mat3 normalMat = mat3(
                    t4.x, t4.y, t4.z,
                    t4.w, t5.x, t5.y,
                    t5.z, t5.w, t6f.x
                );

                ivec4 t6i = texelFetch(uInstanceTboI, baseTexel + 6);
                ivec4 t7i = texelFetch(uInstanceTboI, baseTexel + 7);

                vec4 worldPos = model * vec4(aPos, 1.0);
                vWorldPos = worldPos.xyz;
                vWorldNorm = normalize(normalMat * aNorm);
                vUv = aUv;

                int argb = t7i.x;
                float ca = float((argb >> 24) & 0xFF) / 255.0;
                float cr = float((argb >> 16) & 0xFF) / 255.0;
                float cg = float((argb >> 8) & 0xFF) / 255.0;
                float cb = float(argb & 0xFF) / 255.0;
                vBaseColor = vec4(cr, cg, cb, ca);

                int pbr = t7i.y;
                float metallic = float(pbr & 0xFF) / 255.0;
                float roughness = float((pbr >> 8) & 0xFF) / 255.0;
                float blendCode = float((pbr >> 16) & 0xFF);
                float flags = float(t6i.w);
                vPbrFlags = vec4(metallic, roughness, blendCode, flags);

                int em = t7i.z;
                float er = float((em >> 16) & 0xFF) / 255.0;
                float eg = float((em >> 8) & 0xFF) / 255.0;
                float eb = float(em & 0xFF) / 255.0;
                float eStrength = (float((em >> 24) & 0xFF) / 255.0) * 4.0;
                vEmissive = vec3(er, eg, eb) * eStrength;

                gl_Position = uViewProj * worldPos;
            }
            """;

    public static final String FRAGMENT_GLSL_150 = """
            #version 150
            in vec3 vWorldPos;
            in vec3 vWorldNorm;
            in vec2 vUv;
            in vec4 vBaseColor;
            in vec4 vPbrFlags;
            in vec3 vEmissive;

            uniform vec3 uCameraPos;
            uniform vec3 uLightDir;
            uniform vec3 uLightColor;
            uniform vec3 uAmbientColor;

            out vec4 fragColor;

            void main() {
                float alpha = vBaseColor.a;
                int blendCode = int(vPbrFlags.z + 0.5);
                if (blendCode == 1 && alpha < 0.5) {
                    discard;
                }
                int flags = int(vPbrFlags.w + 0.5);
                bool unlit = (flags & 2) != 0;

                vec3 rgb;
                if (unlit) {
                    rgb = vBaseColor.rgb + vEmissive;
                } else {
                    vec3 N = normalize(vWorldNorm);
                    if (!gl_FrontFacing) N = -N;
                    vec3 L = normalize(-uLightDir);
                    float ndl = max(dot(N, L), 0.0);
                    vec3 V = normalize(uCameraPos - vWorldPos);
                    vec3 H = normalize(L + V);

                    float metallic = vPbrFlags.x;
                    float roughness = clamp(vPbrFlags.y, 0.05, 1.0);
                    float shininess = mix(128.0, 4.0, roughness);
                    float specFactor = pow(max(dot(N, H), 0.0), shininess) * mix(0.08, 0.65, 1.0 - roughness);

                    vec3 diffuse = vBaseColor.rgb * (uAmbientColor + uLightColor * ndl) * (1.0 - metallic * 0.45);
                    vec3 specColor = mix(vec3(1.0), vBaseColor.rgb, metallic);
                    vec3 spec = specColor * uLightColor * specFactor * ndl;
                    rgb = diffuse + spec + vEmissive;
                }
                rgb = clamp(rgb, 0.0, 1.0);
                fragColor = vec4(rgb * alpha, alpha);
            }
            """;
}
