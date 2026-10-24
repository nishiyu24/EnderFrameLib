package com.nishiyu.enderframelib.client.renderer.surface;

import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 世界内の画面の形。物理サイズ（ブロック数）から仮想解像度を決める唯一の場所。
 *
 * @param widthBlocks    画面の幅（ブロック数）
 * @param heightBlocks   画面の高さ（ブロック数）
 * @param pixelsPerBlock 1 ブロックあたりの仮想ピクセル数
 */
public record SurfaceGeometry(float widthBlocks, float heightBlocks, float pixelsPerBlock) {

    public static SurfaceGeometry ofBlocks(float widthBlocks, float heightBlocks) {
        return new SurfaceGeometry(widthBlocks, heightBlocks, DisplayItemDrawer.RESOLUTION);
    }

    /** セッションの解像度を、縦横比を保って {@code surfaceW × surfaceH} ブロックの面に収める。配置は {@link SurfaceFit}。 */
    public static SurfaceFit fit(SessionContext session, float surfaceW, float surfaceH) {
        int pw = session != null ? session.rootW() : Math.round(surfaceW * DisplayItemDrawer.RESOLUTION);
        int ph = session != null ? session.rootH() : Math.round(surfaceH * DisplayItemDrawer.RESOLUTION);
        float ppb = Math.max(pw / surfaceW, ph / surfaceH);
        SurfaceGeometry content = new SurfaceGeometry(pw / ppb, ph / ppb, ppb);
        return new SurfaceFit(content, (surfaceW - content.widthBlocks()) / 2f, (surfaceH - content.heightBlocks()) / 2f);
    }

    /** 仮想解像度（幅 px）の幅から物理サイズを決める。アイテム面のように幅（ブロック数）だけ与えられる場合に使う。 */
    public static SurfaceGeometry ofVirtual(int virtualW, int virtualH, float widthBlocks) {
        float ppb = virtualW / widthBlocks;
        return new SurfaceGeometry(widthBlocks, virtualH / ppb, ppb);
    }

    /** マルチブロック画面の中でのブロックの位置。{@code x} は画面の右向き、{@code y} は上向きに数える（master が 0,0）。 */
    public record Tile(int x, int y) {
    }

    public static Tile tileOf(BlockPos pos, BlockPos masterPos, Direction facing) {
        Direction screenRight = facing.getCounterClockWise();
        int x = switch (screenRight) {
            case WEST -> masterPos.getX() - pos.getX();
            case EAST -> pos.getX() - masterPos.getX();
            case NORTH -> masterPos.getZ() - pos.getZ();
            case SOUTH -> pos.getZ() - masterPos.getZ();
            default -> 0;
        };
        return new Tile(x, pos.getY() - masterPos.getY());
    }

    public int virtualW() {
        return Math.round(widthBlocks * pixelsPerBlock);
    }

    public int virtualH() {
        return Math.round(heightBlocks * pixelsPerBlock);
    }

    /**
     * 重なる要素を奥行きで積む 1 段の厚み（仮想ピクセル単位）。ワールド内の厚みが解像度によらず一定
     * （約 0.001 ブロック）になるよう、解像度に比例させる。32 px/ブロックで 0.032。
     */
    public float zStep() {
        return 1.0e-3f * pixelsPerBlock;
    }

    /** {@link PaintRequest} に渡す画面の大きさ。ブロック数の単位だが、描画範囲の判定は 32 px/ブロックで行うため、仮想ピクセルから逆算する。 */
    public int paintBlocksW() {
        return (int) Math.ceil(virtualW() / DisplayItemDrawer.RESOLUTION);
    }

    public int paintBlocksH() {
        return (int) Math.ceil(virtualH() / DisplayItemDrawer.RESOLUTION);
    }

    /** 仮想ピクセル 1 つ分の大きさ（ブロック単位）。 */
    public float scale() {
        return 1.0f / pixelsPerBlock;
    }
}
