package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.client.SurfaceHit;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.client.SurfaceHitResolver;
import com.nishiyu.enderframelib.client.renderer.surface.SurfaceGeometry;
import com.nishiyu.enderframelib.client.Hologram;
import net.minecraft.client.Camera;
import org.jetbrains.annotations.ApiStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 空中に浮かぶ画面（ホログラム）の描画。登録は {@link com.nishiyu.enderframelib.client.Surfaces#hologram}。クライアントだけの状態で、保存・同期はしない。 */
@ApiStatus.Internal
public final class HologramRenderer {

    private static final Map<String, Hologram> HOLOGRAMS = new ConcurrentHashMap<>();
    private static boolean registered;

    private HologramRenderer() {}

    /** {@code id} のホログラムを出す（同じ {@code id} は置き換える）。クライアントでだけ呼ぶこと。 */
    public static synchronized void show(String id, Hologram spec) {
        if (!registered) {
            NeoForge.EVENT_BUS.addListener(HologramRenderer::onRenderLevelStage);
            registered = true;
        }
        HOLOGRAMS.put(id, spec);
    }

    public static void hide(String id) {
        HOLOGRAMS.remove(id);
    }

    public static void clear() {
        HOLOGRAMS.clear();
    }

    private static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || HOLOGRAMS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Camera camera = mc.gameRenderer.getMainCamera();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        boolean drew = false;
        for (Hologram spec : HOLOGRAMS.values()) {
            drew |= render(spec, event.getPoseStack(), buffer, camera, mc);
        }
        if (drew) buffer.endBatch();
    }

    /**
     * 照準が指す、インタラクティブなホログラムの点（一番手前のもの）。当たらなければ {@code null}。
     * 届く距離はブロックと同じ（{@code blockInteractionRange}）。
     */
    public static SurfaceHit pick(Minecraft mc) {
        if (HOLOGRAMS.isEmpty() || mc.player == null || mc.level == null) return null;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 origin = camera.getPosition();
        var look = camera.getLookVector();
        Vec3 dir = new Vec3(look.x(), look.y(), look.z());
        SurfaceHit best = null;
        for (Hologram spec : HOLOGRAMS.values()) {
            if (!spec.interactive() || spec.sessionId() == null) continue;
            SessionContext session = ClientWebManager.getSession(spec.sessionId());
            if (session == null) continue;
            SurfaceHit hit = pick(spec, origin, dir, camera.getYRot(), session,
                    mc.player.blockInteractionRange());
            if (hit != null && (best == null || hit.distance() < best.distance())) best = hit;
        }
        return best;
    }

    /** {@code sessionId} を映すインタラクティブなホログラム。無ければ {@code null}。 */
    public static Hologram find(String sessionId) {
        for (Hologram spec : HOLOGRAMS.values()) {
            if (spec.interactive() && sessionId.equals(spec.sessionId())) return spec;
        }
        return null;
    }

    /** GUI を開いてカーソルが自由なときの、{@code sessionId} のホログラム上の点（{@link #pick(Minecraft)} のカーソル版）。 */
    public static SurfaceHit pickAtCursor(Minecraft mc, String sessionId, double guiX, double guiY) {
        Hologram spec = find(sessionId);
        SessionContext session = ClientWebManager.getSession(sessionId);
        if (spec == null || session == null || mc.player == null) return null;
        Camera camera = mc.gameRenderer.getMainCamera();
        // 入力中は画面から多少離れてもよいので、届く距離は maxDistance まで
        return pick(spec, camera.getPosition(), SurfaceHitResolver.cursorRay(mc, camera, guiX, guiY), camera.getYRot(),
                session, spec.maxDistance());
    }

    /**
     * 視線（{@code origin} から {@code look} 向きの直線）とホログラムの面の交点を、セッションのピクセル座標で返す。
     * 裏側から・面の外・{@code reach} より遠くは {@code null}。{@link #render} の変換（Y 軸回り → 中心を原点）の逆。
     */
    static SurfaceHit pick(Hologram spec, Vec3 origin, Vec3 look, float cameraYaw,
                                              SessionContext session, double reach) {
        look = look.normalize();
        double theta = Math.toRadians(spec.billboard() ? -cameraYaw - 180f : -spec.yawDegrees());
        // 面の正面（局所の +Z）と右（局所の +X）を、回した後のワールドの向きにしたもの
        double nx = Math.sin(theta), nz = Math.cos(theta);
        double ex = Math.cos(theta), ez = -Math.sin(theta);
        double denom = look.x * nx + look.z * nz;
        if (denom > -1e-6) return null;
        Vec3 toCenter = spec.center().subtract(origin);
        double t = (toCenter.x * nx + toCenter.z * nz) / denom;
        if (t <= 0 || t > Math.min(reach, spec.maxDistance())) return null;

        double relX = origin.x + look.x * t - spec.center().x;
        double relY = origin.y + look.y * t - spec.center().y;
        double relZ = origin.z + look.z * t - spec.center().z;
        SurfaceGeometry geometry = SurfaceGeometry.fit(session, spec.widthBlocks(), spec.heightBlocks()).content();
        double u = relX * ex + relZ * ez;
        double pixelX = u * geometry.pixelsPerBlock() + geometry.virtualW() / 2.0;
        double pixelY = geometry.virtualH() / 2.0 - relY * geometry.pixelsPerBlock();
        if (pixelX < 0 || pixelY < 0 || pixelX > geometry.virtualW() || pixelY > geometry.virtualH()) return null;
        return new SurfaceHit(spec.sessionId(), pixelX, pixelY, null, t * look.length());
    }

    private static boolean render(Hologram spec, PoseStack poseStack, MultiBufferSource buffer, Camera camera, Minecraft mc) {
        String sessionId = spec.sessionId();
        if (sessionId == null || sessionId.isEmpty()) return false;

        Vec3 offset = spec.center().subtract(camera.getPosition());
        double distSqr = offset.lengthSqr();
        if (distSqr > spec.maxDistance() * spec.maxDistance()) return false;

        SessionContext session = SurfaceRenderer.open(sessionId);
        // レイアウトの画面サイズはセッションを読み込んだときの解像度。幅 × 高さは、それを収める面の大きさ
        SurfaceGeometry geometry = SurfaceGeometry.fit(session, spec.widthBlocks(), spec.heightBlocks()).content();
        if (session == null || session.elements().isEmpty()) return false;

        poseStack.pushPose();
        poseStack.translate(offset.x, offset.y, offset.z);
        float scale = geometry.scale();
        if (spec.billboard()) {
            // 正面（+Z）がカメラの方を向くように、カメラの向きの逆へ回す（x を反転すると文字が裏向きになって消える）
            poseStack.mulPose(Axis.YP.rotationDegrees(-camera.getYRot() - 180f));
        } else {
            poseStack.mulPose(Axis.YP.rotationDegrees(-spec.yawDegrees()));
        }
        poseStack.scale(scale, -scale, scale);
        poseStack.translate(-geometry.virtualW() / 2.0, -geometry.virtualH() / 2.0, 0);

        // 発光で描く
        SurfaceStyle style = SurfaceStyle.world(0xF000F0, OverlayTexture.NO_OVERLAY, distSqr)
                .withDepth(SurfaceStyle.Depth.geometry());
        // 表示専用ならホバーは無し。インタラクティブなら、照準から求めたセッションの現在値に任せる
        if (!spec.interactive()) style = style.withHovered("");
        SurfaceRenderer.draw(sessionId, geometry, style, poseStack, buffer);
        poseStack.popPose();
        return true;
    }
}
