package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;

import java.util.Arrays;

/**
 * インライン整形の 1 行（Engine v3 M2a）。座標は持ち主の内容ボックス基準。不変。
 *
 * @param pieces 行に載った文字の断片（左から順）
 */
public record LineFragment(float x, float y, float width, float height, Piece[] pieces) {

    /** 文字の節 {@code text} の {@code [start, end)}（正規化済みの文字列の位置）を、行の左端から {@code x} に幅 {@code width} で置く。 */
    public record Piece(BoxNode text, int start, int end, float x, float width) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Piece p && p.text == text && p.start == start && p.end == end
                    && Float.compare(p.x, x) == 0 && Float.compare(p.width, width) == 0;
        }

        @Override
        public int hashCode() {
            return (System.identityHashCode(text) * 31 + start) * 31 + end;
        }

        /** 載っている文字列。 */
        public String string() {
            return text.text.substring(start, end);
        }
    }

    /** 位置・寸法・断片が同じか（全再計算との一致の比較用）。 */
    public boolean sameAs(LineFragment o) {
        return Float.compare(x, o.x) == 0 && Float.compare(y, o.y) == 0 && Float.compare(width, o.width) == 0
                && Float.compare(height, o.height) == 0 && Arrays.equals(pieces, o.pieces);
    }
}
