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
 * @param spans    行に載ったインライン要素の箱の、この行の分（背景・枠線を描く断片。入れ子は外側が先。無ければ空）
 */
public record LineFragment(float x, float y, float width, float height, float baseline, Piece[] pieces, PlacedFragment[] atoms,
                           Span[] spans) {

    static final PlacedFragment[] NO_ATOMS = new PlacedFragment[0];
    static final Span[] NO_SPANS = new Span[0];

    /**
     * インライン要素 {@code box} の、この行の分の border-box（CSS 2.2 §10.6.1: 縦は行の高さでなく自分の書体の内容領域に上下の
     * padding と border を足した範囲）。{@code x} は行の左端・{@code y} は行の上端基準。{@code startEdge} / {@code endEdge} は
     * 箱の開始端（左の border・padding）・終了端（右）を持つか（行をまたぐ箱は最初の行だけが開始端、最後の行だけが終了端。
     * {@code box-decoration-break: slice}）。{@code baseline} は上端から自分のベースラインまで。
     */
    public record Span(BoxNode box, float x, float y, float width, float height, float baseline, boolean startEdge, boolean endEdge) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Span p && p.box == box && Float.compare(p.x, x) == 0 && Float.compare(p.y, y) == 0
                    && Float.compare(p.width, width) == 0 && Float.compare(p.height, height) == 0
                    && Float.compare(p.baseline, baseline) == 0 && p.startEdge == startEdge && p.endEdge == endEdge;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(box) * 31 + Float.floatToIntBits(x);
        }
    }

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
                || !Arrays.equals(pieces, o.pieces) || !Arrays.equals(spans, o.spans) || atoms.length != o.atoms.length) return false;
        for (int i = 0; i < atoms.length; i++) {
            PlacedFragment a = atoms[i], b = o.atoms[i];
            if (Float.compare(a.x(), b.x()) != 0 || Float.compare(a.y(), b.y()) != 0
                    || Float.compare(a.frag().width, b.frag().width) != 0 || Float.compare(a.frag().height, b.frag().height) != 0) return false;
        }
        return true;
    }
}
