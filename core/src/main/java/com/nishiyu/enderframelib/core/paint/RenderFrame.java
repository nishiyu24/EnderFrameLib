package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.FrozenItemList;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;

import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;

/**
 * 描画器へ渡す 1 つのフレームの値（M3b-4c-2a。計画書 §1.6 決定 V）。ホストはフレームごとにこれを 1 つ作り、描画器は
 * {@link RenderBackend#prepare}・{@link RenderBackend#render} でこれだけを読む。
 *
 * @param items    シーンから組んだ表示項目の列（シーンの無いフレームは描く列そのもの。M3b-4c-2b で走査器がシーンを直接たどるまでの仮）
 * @param overlays シーンの外の重ね描き（キャレット・選択の一覧・インスペクタ。列の後ろに描く。前のフレームと今の範囲を描き直す）
 * @param offsets  節ごとの描画上の平行移動（スクロールの中身・つまみ・sticky と利用者の移動。{@code UiView#paintOffsets}）
 * @param scene    列を組んだシーンと動的な値（無ければ null。描き直す範囲を求めず全面を描く）
 * @param nowMs    描く時刻（ミリ秒。補間の時刻）
 */
public record RenderFrame(List<DisplayItem> items, List<DisplayItem> overlays, PaintOffsets offsets, SceneSource scene, long nowMs) {
    public RenderFrame {
        items = items != null ? items : List.of();
        overlays = overlays != null ? overlays : List.of();
        offsets = offsets != null ? offsets : PaintOffsets.EMPTY;
    }

    /** シーンも重ね描きも無い、列だけのフレーム（全面を描く）。 */
    public static RenderFrame of(List<DisplayItem> items, long nowMs) {
        return new RenderFrame(items, List.of(), PaintOffsets.EMPTY, null, nowMs);
    }

    /** シーンも重ね描きも無い、平行移動つきの列だけのフレーム（全面を描く）。 */
    public static RenderFrame of(List<DisplayItem> items, PaintOffsets offsets, long nowMs) {
        return new RenderFrame(items, List.of(), offsets, null, nowMs);
    }

    /** 描く列（シーンの列の後ろに重ね描き）。重ね描きがあれば 2 つの列をつないだ見え方を作る（写さない）。 */
    public List<DisplayItem> drawList() {
        return overlays.isEmpty() ? items : new Joined(items, overlays);
    }

    /** 列 {@link #items} の後ろに {@code more} をつないだ見え方（写さない）。 */
    public List<DisplayItem> withOverlays(List<DisplayItem> more) {
        return more == null || more.isEmpty() ? items : new Joined(items, more);
    }

    /** 描く列のうちシーンから組んだ部分の長さ（シーンが無ければ 0。後ろは重ね描き）。 */
    public int sceneItemCount() {
        return scene != null ? items.size() : 0;
    }

    /** 描く項目が無いか。 */
    public boolean isEmpty() {
        return items.isEmpty() && overlays.isEmpty();
    }

    /**
     * 別のスレッドへ渡せる写し（列は不変の列に、平行移動は不変の写しにする。不変の列はそのまま共有する）。シーンと動的な値は不変の値なので共有する。
     */
    public RenderFrame snapshot() {
        List<DisplayItem> it = items instanceof FrozenItemList ? items : List.copyOf(items);
        List<DisplayItem> ov = overlays.isEmpty() ? List.of() : List.copyOf(overlays);
        PaintOffsets off = offsets.snapshot();
        if (it == items && ov == overlays && off == offsets) return this;
        return new RenderFrame(it, ov, off, scene, nowMs);
    }

    /** 2 つの列をつないだ読み取りだけの見え方。 */
    private static final class Joined extends AbstractList<DisplayItem> implements RandomAccess {
        private final List<DisplayItem> a, b;
        private final int na;

        Joined(List<DisplayItem> a, List<DisplayItem> b) {
            this.a = a;
            this.b = b;
            this.na = a.size();
        }

        @Override
        public DisplayItem get(int i) {
            return i < na ? a.get(i) : b.get(i - na);
        }

        @Override
        public int size() {
            return na + b.size();
        }
    }
}
