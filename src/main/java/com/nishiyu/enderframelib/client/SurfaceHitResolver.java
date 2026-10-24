package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.api.WebScreenBlockEntity;
import com.nishiyu.enderframelib.client.renderer.surface.SurfaceFit;
import com.nishiyu.enderframelib.client.renderer.surface.SurfaceGeometry;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * ブロック面の「どこを指しているか」を、画面の仮想ピクセル座標へ直す。
 *
 * <p>入口は 2 つ。{@link #fromCrosshair} は照準（{@code mc.hitResult}）から、{@link #fromCursor} は GUI を開いて
 * カーソルが自由になっているとき（{@code InvisibleInputScreen}）のカーソル位置から求める。後者は
 * {@code mc.hitResult} が動かないので、カメラからのレイと画面の平面の交差で求める。</p>
 */
public final class SurfaceHitResolver {

    private SurfaceHitResolver() {}

    /** 照準が画面の正面の面に当たっているときだけ返す。 */
    public static SurfaceHit fromCrosshair(Minecraft mc) {
        if (mc.level == null || mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) return null;
        BlockHitResult hitResult = (BlockHitResult) mc.hitResult;
        BlockPos pos = hitResult.getBlockPos();
        if (!(mc.level.getBlockEntity(pos) instanceof WebScreenBlockEntity screen) || !screen.isActive()) return null;

        Direction facing = screen.getFacing();
        if (facing == null || hitResult.getDirection() != facing) return null;

        double hitX = hitResult.getLocation().x - pos.getX();
        double hitY = hitResult.getLocation().y - pos.getY();
        double hitZ = hitResult.getLocation().z - pos.getZ();

        double blockU = switch (facing) {
            case NORTH -> 1.0 - hitX;
            case SOUTH -> hitX;
            case WEST -> hitZ;
            case EAST -> 1.0 - hitZ;
            default -> 0.0;
        };
        double blockV = 1.0 - hitY;

        WebScreenBlockEntity master = screen.isMaster() ? screen :
                (screen.getScreenMasterPos() != null && mc.level.getBlockEntity(screen.getScreenMasterPos()) instanceof WebScreenBlockEntity m ? m : screen);
        BlockPos masterPos = master.getScreenMasterPos() != null ? master.getScreenMasterPos() : pos;

        SurfaceFit fit = SurfaceGeometry.fit(ClientWebManager.getSession(master.getSessionId()),
                master.getScreenWidth(), master.getScreenHeight());
        SurfaceGeometry.Tile tile = SurfaceGeometry.tileOf(pos, masterPos, facing);
        double pixelX = (tile.x() + blockU - fit.offsetX()) * fit.content().pixelsPerBlock();
        double pixelY = ((master.getScreenHeight() - 1 - tile.y()) + blockV - fit.offsetY()) * fit.content().pixelsPerBlock();
        return new SurfaceHit(master.getSessionId(), pixelX, pixelY, masterPos,
                mc.player != null ? mc.player.getEyePosition().distanceTo(hitResult.getLocation()) : 0.0);
    }

    /**
     * GUI スケールのカーソル座標が指す、{@code masterPos} の画面上の点を返す。画面の外でも座標は返す
     * （どの要素にも当たらないだけ）。正面から見ていない／画面が無いときは {@code null}。
     */
    public static SurfaceHit fromCursor(Minecraft mc, BlockPos masterPos, double guiX, double guiY) {
        if (mc.level == null || masterPos == null) return null;
        if (!(mc.level.getBlockEntity(masterPos) instanceof WebScreenBlockEntity master)) return null;
        Direction facing = master.getFacing();
        if (facing == null || facing.getAxis().isVertical()) return null;

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 origin = camera.getPosition();
        Vec3 dir = cursorRay(mc, camera, guiX, guiY);

        // 画面の左下の角（master ブロックの正面の面の、向かって左下）
        Direction right = facing.getCounterClockWise();
        Vec3 bottomLeft = Vec3.atCenterOf(masterPos).add(
                (facing.getStepX() - right.getStepX()) * 0.5, -0.5, (facing.getStepZ() - right.getStepZ()) * 0.5);

        double denom = dir.x * facing.getStepX() + dir.z * facing.getStepZ();
        if (denom > -1e-6) return null;
        double t = ((bottomLeft.x - origin.x) * facing.getStepX() + (bottomLeft.z - origin.z) * facing.getStepZ()) / denom;
        if (t <= 0) return null;

        double relX = origin.x + dir.x * t - bottomLeft.x;
        double relY = origin.y + dir.y * t - bottomLeft.y;
        double relZ = origin.z + dir.z * t - bottomLeft.z;
        double u = relX * right.getStepX() + relZ * right.getStepZ();

        SurfaceFit fit = SurfaceGeometry.fit(ClientWebManager.getSession(master.getSessionId()),
                master.getScreenWidth(), master.getScreenHeight());
        double pixelX = (u - fit.offsetX()) * fit.content().pixelsPerBlock();
        double pixelY = (master.getScreenHeight() - relY - fit.offsetY()) * fit.content().pixelsPerBlock();
        return new SurfaceHit(master.getSessionId(), pixelX, pixelY, masterPos, t);
    }

    /** カメラからカーソルへ向かう方向（ワールド座標。長さは不定）。FOV は設定値（走行による変化は無視）。 */
    public static Vec3 cursorRay(Minecraft mc, Camera camera, double guiX, double guiY) {
        double tanHalfFov = Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
        double aspect = (double) mc.getWindow().getWidth() / mc.getWindow().getHeight();
        double ndcX = guiX / mc.getWindow().getGuiScaledWidth() * 2.0 - 1.0;
        double ndcY = 1.0 - guiY / mc.getWindow().getGuiScaledHeight() * 2.0;

        Vector3f look = camera.getLookVector();
        Vector3f up = camera.getUpVector();
        Vector3f left = camera.getLeftVector();
        double sx = -ndcX * tanHalfFov * aspect;
        double sy = ndcY * tanHalfFov;
        return new Vec3(
                look.x() + left.x() * sx + up.x() * sy,
                look.y() + left.y() * sx + up.y() * sy,
                look.z() + left.z() * sx + up.z() * sy);
    }
}
