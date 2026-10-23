package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.paint.model.*;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;

/**
 * Web仮想座標空間(localPos)とスクリーン/ワールド変換行列(poseMatrix)を分離し、
 * 2D GUI・3Dブロック表面・手持ちアイテム表面の全てにおいて、
 * 単色およびグラデーションの角丸・親角丸クリッピングを 1-Quad で描画するSDFシェーダー。
 */
public class WebSdfShader {

    private static int programId = -1;
    private static boolean initialized = false;
    private static boolean available = false;

    private static int uModelViewMat = -1;
    private static int uProjMat = -1;
    private static int uPoseMat = -1;
    private static int uRect = -1;
    private static int uRadii = -1;
    private static int uClipRect = -1;
    private static int uClipRadii = -1;
    private static int uSegment = -1;
    private static int uSegWidth = -1;

    private static final String VERTEX_GLSL = """
            #version 150
            in vec3 Position;
            in vec2 UV0;
            in vec4 Color;

            uniform mat4 ModelViewMat;
            uniform mat4 ProjMat;
            uniform mat4 uPoseMat;

            out vec2 vLocalPos;
            out vec4 vColor;

            void main() {
                vLocalPos = Position.xy;
                vColor = Color;
                gl_Position = ProjMat * ModelViewMat * uPoseMat * vec4(Position, 1.0);
            }
            """;

    private static final String FRAGMENT_GLSL = """
            #version 150
            in vec2 vLocalPos;
            in vec4 vColor;

            uniform vec4 uRect;      // (minX, minY, maxX, maxY)
            uniform vec4 uRadii;     // (rTL, rTR, rBR, rBL)
            uniform vec4 uClipRect;  // (cx0, cy0, cx1, cy1)
            uniform vec4 uClipRadii; // (cRTL, cRTR, cRBR, cRBL)
            uniform vec4 uSegment;   // 線分の両端 (ax, ay, bx, by)。uSegWidth > 0 のときだけ使う
            uniform float uSegWidth; // 線分の太さ。0 なら角丸矩形

            out vec4 fragColor;

            // 両端で直角に切った太い線分（向きのある矩形）までの距離
            float sdOrientedBox(vec2 p, vec2 a, vec2 b, float th) {
                float l = max(length(b - a), 0.001);
                vec2 d = (b - a) / l;
                vec2 q = p - (a + b) * 0.5;
                q = mat2(d.x, -d.y, d.y, d.x) * q;
                q = abs(q) - vec2(l, th) * 0.5;
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0);
            }

            float sdRoundBox(vec2 p, vec2 b, vec4 r) {
                vec2 rSel = (p.x > 0.0) ? vec2(r.y, r.z) : vec2(r.x, r.w);
                float rad = (p.y > 0.0) ? rSel.y : rSel.x;
                rad = min(rad, min(b.x, b.y));
                vec2 q = abs(p) - b + rad;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - rad;
            }

            void main() {
                vec2 center = (uRect.xy + uRect.zw) * 0.5;
                vec2 halfSize = max(vec2(0.001), (uRect.zw - uRect.xy) * 0.5);
                float distBox = uSegWidth > 0.0
                        ? sdOrientedBox(vLocalPos, uSegment.xy, uSegment.zw, uSegWidth)
                        : sdRoundBox(vLocalPos - center, halfSize, uRadii);

                vec2 clipCenter = (uClipRect.xy + uClipRect.zw) * 0.5;
                vec2 clipHalf = max(vec2(0.001), (uClipRect.zw - uClipRect.xy) * 0.5);
                float distClip = sdRoundBox(vLocalPos - clipCenter, clipHalf, uClipRadii);

                float dist = max(distBox, distClip);
                float aa = clamp(fwidth(dist), 0.35, 1.25);
                float alphaMask = 1.0 - smoothstep(-aa * 0.5, aa * 0.5, dist);
                if (alphaMask <= 0.003) discard;

                vec4 col = vColor;
                col.a *= alphaMask;
                if (col.a <= 0.003) discard;
                fragColor = col;
            }
            """;

    private static boolean passActive = false;
    private static Matrix4f currentPoseMatrix = null;

    public static boolean isAvailable() {
        if (!RenderSystem.isOnRenderThread()) return false;
        if (!initialized) {
            initShader();
        }
        return available;
    }

    public static synchronized void deleteProgram() {
        endPass();
        if (programId != -1) {
            GL20.glDeleteProgram(programId);
            programId = -1;
        }
        initialized = false;
        available = false;
    }

    public static synchronized void reload() {
        if (RenderSystem.isOnRenderThread()) {
            deleteProgram();
        } else {
            RenderSystem.recordRenderCall(WebSdfShader::deleteProgram);
        }
    }

    public static void beginPass(Matrix4f poseMatrix) {
        if (!isAvailable()) return;
        if (!passActive) {
            Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
            GL20.glUseProgram(programId);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer mvBuf = stack.mallocFloat(16);
                RenderSystem.getModelViewMatrix().get(mvBuf);
                GL20.glUniformMatrix4fv(uModelViewMat, false, mvBuf);

                FloatBuffer projBuf = stack.mallocFloat(16);
                RenderSystem.getProjectionMatrix().get(projBuf);
                GL20.glUniformMatrix4fv(uProjMat, false, projBuf);

                FloatBuffer poseBuf = stack.mallocFloat(16);
                poseMatrix.get(poseBuf);
                GL20.glUniformMatrix4fv(uPoseMat, false, poseBuf);
            }
            currentPoseMatrix = poseMatrix;
            passActive = true;
        } else if (currentPoseMatrix != poseMatrix && !java.util.Objects.equals(currentPoseMatrix, poseMatrix)) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer poseBuf = stack.mallocFloat(16);
                poseMatrix.get(poseBuf);
                GL20.glUniformMatrix4fv(uPoseMat, false, poseBuf);
            }
            currentPoseMatrix = poseMatrix;
        }
    }

    public static void endPass() {
        if (passActive) {
            GL20.glUseProgram(0);
            RenderSystem.defaultBlendFunc();
            passActive = false;
            currentPoseMatrix = null;
        }
    }

    private static void initShader() {
        initialized = true;
        int vShader = -1;
        int fShader = -1;
        try {
            vShader = compileShader(GL20.GL_VERTEX_SHADER, VERTEX_GLSL);
            if (vShader == -1) return;

            fShader = compileShader(GL20.GL_FRAGMENT_SHADER, FRAGMENT_GLSL);
            if (fShader == -1) return;

            programId = GL20.glCreateProgram();
            GL20.glBindAttribLocation(programId, 0, "Position");
            GL20.glBindAttribLocation(programId, 1, "UV0");
            GL20.glBindAttribLocation(programId, 2, "Color");

            GL20.glAttachShader(programId, vShader);
            GL20.glAttachShader(programId, fShader);
            GL20.glLinkProgram(programId);

            if (GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                GL20.glDeleteProgram(programId);
                programId = -1;
                return;
            }

            uModelViewMat = GL20.glGetUniformLocation(programId, "ModelViewMat");
            uProjMat = GL20.glGetUniformLocation(programId, "ProjMat");
            uPoseMat = GL20.glGetUniformLocation(programId, "uPoseMat");
            uRect = GL20.glGetUniformLocation(programId, "uRect");
            uRadii = GL20.glGetUniformLocation(programId, "uRadii");
            uClipRect = GL20.glGetUniformLocation(programId, "uClipRect");
            uClipRadii = GL20.glGetUniformLocation(programId, "uClipRadii");
            uSegment = GL20.glGetUniformLocation(programId, "uSegment");
            uSegWidth = GL20.glGetUniformLocation(programId, "uSegWidth");

            available = true;
        } catch (Throwable t) {
            available = false;
        } finally {
            if (vShader != -1) GL20.glDeleteShader(vShader);
            if (fShader != -1) GL20.glDeleteShader(fShader);
        }
    }

    private static int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            GL20.glDeleteShader(shader);
            return -1;
        }
        return shader;
    }

    public static boolean drawSdfRect(
            Matrix4f poseMatrix,
            float minX, float minY, float maxX, float maxY,
            float rTL, float rTR, float rBR, float rBL,
            float r, float g, float b, float a, float zOffset,
            float cx0, float cy0, float cx1, float cy1,
            float cRTL, float cRTR, float cRBR, float cRBL
    ) {
        return drawSdfGradientRect(
                poseMatrix, minX, minY, maxX, maxY,
                rTL, rTR, rBR, rBL,
                r, g, b, a,
                r, g, b, a,
                r, g, b, a,
                r, g, b, a,
                zOffset, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL
        );
    }

    /**
     * 太さ {@code width} の線分（両端は直角に切る）を、アンチエイリアスと親の角丸クリップ付きで描く。
     * チェックボックスの印のような斜めの線に使う。シェーダーが使えなければ false。
     */
    public static boolean drawSdfSegment(
            Matrix4f poseMatrix, float ax, float ay, float bx, float by, float width,
            float r, float g, float b, float a, float zOffset,
            float cx0, float cy0, float cx1, float cy1,
            float cRTL, float cRTR, float cRBR, float cRBL
    ) {
        if (!isAvailable()) return false;
        float pad = width + 1f;
        float minX = Math.min(ax, bx) - pad, maxX = Math.max(ax, bx) + pad;
        float minY = Math.min(ay, by) - pad, maxY = Math.max(ay, by) + pad;
        if (Math.max(minX, cx0) >= Math.min(maxX, cx1) || Math.max(minY, cy0) >= Math.min(maxY, cy1)) return true;

        boolean localPass = !passActive;
        if (localPass || (currentPoseMatrix != poseMatrix && !java.util.Objects.equals(currentPoseMatrix, poseMatrix))) {
            beginPass(poseMatrix);
        }
        try {
            GL20.glUniform4f(uRect, minX, minY, maxX, maxY);
            GL20.glUniform4f(uRadii, 0f, 0f, 0f, 0f);
            GL20.glUniform4f(uClipRect, cx0, cy0, cx1, cy1);
            GL20.glUniform4f(uClipRadii, cRTL, cRTR, cRBR, cRBL);
            GL20.glUniform4f(uSegment, ax, ay, bx, by);
            GL20.glUniform1f(uSegWidth, width);

            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            builder.addVertex(minX, maxY, zOffset).setUv(0f, 1f).setColor(r, g, b, a);
            builder.addVertex(maxX, maxY, zOffset).setUv(1f, 1f).setColor(r, g, b, a);
            builder.addVertex(maxX, minY, zOffset).setUv(1f, 0f).setColor(r, g, b, a);
            builder.addVertex(minX, minY, zOffset).setUv(0f, 0f).setColor(r, g, b, a);
            MeshData mesh = builder.build();
            if (mesh != null) BufferUploader.draw(mesh);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            GL20.glUniform1f(uSegWidth, 0f);
            if (localPass) endPass();
        }
    }

    /**
     * 4頂点それぞれの色（グラデーション対応）と4隅角丸・親角丸クリップを 1-Quad で描画する
     */
    public static boolean drawSdfGradientRect(
            Matrix4f poseMatrix,
            float minX, float minY, float maxX, float maxY,
            float rTL, float rTR, float rBR, float rBL,
            float rTLCol, float gTLCol, float bTLCol, float aTLCol,
            float rTRCol, float gTRCol, float bTRCol, float aTRCol,
            float rBRCol, float gBRCol, float bBRCol, float aBRCol,
            float rBLCol, float gBLCol, float bBLCol, float aBLCol,
            float zOffset,
            float cx0, float cy0, float cx1, float cy1,
            float cRTL, float cRTR, float cRBR, float cRBL
    ) {
        if (!isAvailable()) return false;
        if (Math.max(minX, cx0) >= Math.min(maxX, cx1) || Math.max(minY, cy0) >= Math.min(maxY, cy1)) {
            return true;
        }

        boolean localPass = !passActive;
        if (localPass) {
            beginPass(poseMatrix);
        } else if (currentPoseMatrix != poseMatrix && !java.util.Objects.equals(currentPoseMatrix, poseMatrix)) {
            beginPass(poseMatrix);
        }

        try {
            GL20.glUniform4f(uRect, minX, minY, maxX, maxY);
            GL20.glUniform4f(uRadii, rTL, rTR, rBR, rBL);
            GL20.glUniform4f(uClipRect, cx0, cy0, cx1, cy1);
            GL20.glUniform4f(uClipRadii, cRTL, cRTR, cRBR, cRBL);
            GL20.glUniform1f(uSegWidth, 0f);

            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            builder.addVertex(minX, maxY, zOffset).setUv(0f, 1f).setColor(rBLCol, gBLCol, bBLCol, aBLCol);
            builder.addVertex(maxX, maxY, zOffset).setUv(1f, 1f).setColor(rBRCol, gBRCol, bBRCol, aBRCol);
            builder.addVertex(maxX, minY, zOffset).setUv(1f, 0f).setColor(rTRCol, gTRCol, bTRCol, aTRCol);
            builder.addVertex(minX, minY, zOffset).setUv(0f, 0f).setColor(rTLCol, gTLCol, bTLCol, aTLCol);

            MeshData mesh = builder.build();
            if (mesh != null) {
                BufferUploader.draw(mesh);
            }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (localPass) {
                endPass();
            }
        }
    }
}