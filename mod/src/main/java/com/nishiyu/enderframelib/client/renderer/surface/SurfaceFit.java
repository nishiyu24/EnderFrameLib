package com.nishiyu.enderframelib.client.renderer.surface;

/**
 * セッションを読み込んだときの解像度（{@code rootW} × {@code rootH}）で組んだレイアウトを、縦横比を保って
 * ブロック単位の面に収めたときの配置。画面の大きさ（メニューモードと同じ）はセッションが決め、
 * 面の大きさは貼る先の物理的な大きさでしかない。縦横比が違うときは中央に置き、余白は何も描かない。
 *
 * @param content 面に収めた内容（ブロック単位の大きさと、1 ブロックあたりの仮想ピクセル数）
 * @param offsetX 面の左端から内容の左端まで（ブロック）
 * @param offsetY 面の上端から内容の上端まで（ブロック）
 * @see SurfaceGeometry#fit
 */
public record SurfaceFit(SurfaceGeometry content, float offsetX, float offsetY) {
}
