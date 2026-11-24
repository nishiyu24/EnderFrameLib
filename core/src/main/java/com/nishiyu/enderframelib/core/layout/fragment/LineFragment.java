package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;

import java.util.Arrays;

/**
 * インライン整形の 1 行（Engine v3 M2a・M2b-1）。座標は持ち主の内容ボックス基準。不変。
 *
 * @param baseline 行の上端からベースラインまで（文字片・原子はここへ揃える）
 * @param pieces   行に載った文字の断片（左から順）
 * @param atoms    行に置いたインライン水準の原子（{@code inline-block} など。{@code x} は行の左端、{@code y} は行の上端基準の
 *                 border-box の左上。無ければ空）
 */
public record LineFragment(float x, float y, float width, float height, float baseline, Piece[] pieces, PlacedFragment[] atoms) {

    static final PlacedFragment[] NO_ATOMS = new PlacedFragment[0];

    /**
     * 文字の節 {@code text} の {@code [start, end)}（正規化済みの文字列の位置）を、行の左端から {@code x} に幅 {@code width} で置く。
     * {@code dy} は文字の並びのベースラインの、行のベースラインからのずれ（下が正。{@code vertical-align}。既定は 0）。
     */
    public record Piece(BoxNode text, int start, int end, float x, float width, float dy) {
        public Piece(BoxNode text, int start, int end, float x, float width) {
            this(text, start, end, x, width, 0f);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Piece p && p.text == text && p.start == start && p.end == end
                    && Float.compare(p.x, x) == 0 && Float.compare(p.width, width) == 0 && Float.compare(p.dy, dy) == 0;
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

    /** 位置・寸法・断片が同じか（全再計算との一致の比較用。原子は位置と大きさだけ比べ、中身は呼び出し側が辿る）。 */
    public boolean sameAs(LineFragment o) {
        if (Float.compare(x, o.x) != 0 || Float.compare(y, o.y) != 0 || Float.compare(width, o.width) != 0
                || Float.compare(height, o.height) != 0 || Float.compare(baseline, o.baseline) != 0
                || !Arrays.equals(pieces, o.pieces) || atoms.length != o.atoms.length) return false;
        for (int i = 0; i < atoms.length; i++) {
            PlacedFragment a = atoms[i], b = o.atoms[i];
            if (Float.compare(a.x(), b.x()) != 0 || Float.compare(a.y(), b.y()) != 0
                    || Float.compare(a.frag().width, b.frag().width) != 0 || Float.compare(a.frag().height, b.frag().height) != 0) return false;
        }
        return true;
    }
}
