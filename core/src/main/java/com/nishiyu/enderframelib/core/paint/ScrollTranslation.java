package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo;

import java.util.List;

/**
 * 中身を 1 つの節にまとめたスクロールコンテナの、今のスクロール量での描画上の平行移動（M3b-4c-2b-2b。以前はビューが
 * {@code UiView#tryScrollTranslate} で平行移動の表に書いていた）。列はコンテナを組んだときのスクロール量 {@code sx0} で組んであり、
 * 今との差を中身の節・つまみ・中の sticky の箱の移動で足す。
 */
public final class ScrollTranslation {
    private ScrollTranslation() {}

    /**
     * 利用者の平行移動 {@code user} に、{@code contents} のコンテナの今のスクロール量 {@code scroll} での移動を足した不変の表
     * （まとめたコンテナが無ければ {@code user} そのもの）。
     */
    public static PaintOffsets apply(PaintOffsets user, List<ScrollContentInfo> contents, ScrollState scroll) {
        PaintOffsets base = user != null ? user : PaintOffsets.EMPTY;
        if (contents.isEmpty() || scroll == null) return base;
        PaintOffsets out = new PaintOffsets();
        for (int i = 0, n = base.size(); i < n; i++) out.set(base.idAt(i), base.dxAt(i), base.dyAt(i));
        for (int i = 0, n = contents.size(); i < n; i++) {
            ScrollContentInfo info = contents.get(i);
            float dx = scroll.getScrollX(info.node()) - info.sx0();
            float dy = scroll.getScrollY(info.node()) - info.sy0();
            out.setIfActive(info.contentId(), -dx, -dy);
            // つまみは組んだときと同じ整数の位置へ（小数のまま動かすと角の縁の画素が組み直しと違う。A6b ⑤）
            if (info.thumbVId() != null) out.setIfActive(info.thumbVId(), 0f, Math.round(info.fy0() + info.ky() * dy));
            if (info.thumbHId() != null) out.setIfActive(info.thumbHId(), Math.round(info.fx0() + info.kx() * dx), 0f);
            // 中身の中の sticky の箱: 中身と一緒に動いた分に、貼り付きのずれの増減を足す（組んだときの式と同じ。B12）
            for (int k = 0, sn = info.stickies().size(); k < sn; k++) {
                var st = info.stickies().get(k);
                out.setIfActive(st.nodeId(), 0f, st.extraAt(dy));
            }
        }
        return out.snapshot();
    }
}
