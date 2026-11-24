package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.CssKeywords;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 行の揃え（Engine v3 M2b-1。CSS Text 3 §7）。容器のスタイルから 1 回だけ作る不変の値で、行ごとに
 * 「折り返した行」か「最終行（強制改行の前の行を含む）」かで {@code text-align} / {@code text-align-last} を選ぶ。
 *
 * <ul>
 *   <li>{@code start} / {@code end} は容器の {@code direction} で左右に直す。{@code match-parent} は親のスタイルが要るので、
 *       ここでは {@code start} と同じに扱う（計画書 §3.4.1 M2b-5 の双方向で親の向きを渡す）。</li>
 *   <li>{@code justify}: 行の余りを、揃えの機会（語の後ろの空白・CJK の字の間、{@code text-justify: inter-character} なら
 *       文字の間すべて）に等しく配る。文字片を機会ごとに割って位置をずらした新しい片の列を返す（断片は不変のまま）。</li>
 * </ul>
 */
record LineAlign(TextAlign normal, boolean justifyNormal, TextAlign last, boolean justifyLast, Mode mode) {

    enum Mode { AUTO, NONE, INTER_WORD, INTER_CHARACTER }

    static LineAlign of(Style container) {
        boolean rtl = rtl(container);
        TextAlign specified = container.inherited.textAlign;
        if (specified == null) specified = TextAlign.START;
        TextAlign physical = specified.physical(rtl);
        Mode mode = mode(container.inherited.textJustify);
        boolean justify = specified == TextAlign.JUSTIFY && mode != Mode.NONE;
        String l = container.inherited.textAlignLast == null ? "auto" : container.inherited.textAlignLast.trim().toLowerCase(Locale.ROOT);
        TextAlign last;
        boolean justifyLast;
        if (l.equals("auto")) {
            last = physical;
            justifyLast = false;
        } else if (l.equals("justify")) {
            last = TextAlign.START.physical(rtl);
            justifyLast = mode != Mode.NONE;
        } else {
            TextAlign k = CssKeywords.parse(TextAlign.class, l);
            last = k == null ? physical : k.physical(rtl);
            justifyLast = false;
        }
        return new LineAlign(physical, justify, last, justifyLast, mode);
    }

    static boolean rtl(Style st) {
        String d = st.inherited.direction;
        return d != null && "rtl".equalsIgnoreCase(d.trim());
    }

    private static Mode mode(String v) {
        String m = v == null ? "auto" : v.trim().toLowerCase(Locale.ROOT);
        return switch (m) {
            case "none" -> Mode.NONE;
            case "inter-word" -> Mode.INTER_WORD;
            case "inter-character", "distribute" -> Mode.INTER_CHARACTER;
            default -> Mode.AUTO;
        };
    }

    /** 行の左端からのずれ（{@code soft} は折り返した行）。両端揃えの行は 0。 */
    float offset(boolean soft, float lineW, float width) {
        TextAlign a = soft ? normal : last;
        if (a == TextAlign.CENTER) return Math.max(0, (lineW - width) / 2f);
        if (a == TextAlign.RIGHT) return Math.max(0, lineW - width);
        return 0;
    }

    boolean justifies(boolean soft) {
        return soft ? justifyNormal : justifyLast;
    }

    /**
     * 両端揃え: {@code pieces}（左から順、{@code spaceBefore[i]} は片 {@code i} の前に畳まれた空白があったか）を揃えの機会ごとに割り、
     * 余り {@code extra} を機会の数で等分してずらした片の列を返す。原子は左端より前の機会の数だけずらす（{@code atomX} を書き換える）。
     * 機会が無ければ null（揃えない）。
     */
    LineFragment.Piece[] justify(LineFragment.Piece[] pieces, boolean[] spaceBefore, float extra, float[] atomX, LayoutSession s) {
        if (extra <= 0.01f || pieces.length == 0) return null;
        List<LineFragment.Piece> segs = new ArrayList<>(pieces.length * 2);
        List<Boolean> gap = new ArrayList<>(pieces.length * 2);
        for (int i = 0; i < pieces.length; i++) split(pieces[i], spaceBefore[i], segs, gap, s);
        int n = segs.size();
        boolean[] opp = new boolean[n]; // opp[i]: 片 i と i+1 の間が機会か
        int count = 0;
        for (int i = 0; i + 1 < n; i++) {
            if (opportunity(segs.get(i), segs.get(i + 1), gap.get(i + 1))) {
                opp[i] = true;
                count++;
            }
        }
        if (count == 0) return null;
        float each = extra / count;
        LineFragment.Piece[] out = new LineFragment.Piece[n];
        float acc = 0;
        float[] oppEnd = new float[count];
        int k = 0;
        for (int i = 0; i < n; i++) {
            LineFragment.Piece p = segs.get(i);
            out[i] = new LineFragment.Piece(p.text(), p.start(), p.end(), LayoutUnit.snap(p.x() + acc), p.width());
            if (opp[i]) {
                acc += each;
                oppEnd[k++] = p.x() + p.width();
            }
        }
        for (int a = 0; a < atomX.length; a++) {
            float shift = 0;
            for (float e : oppEnd) if (e <= atomX[a] + 0.01f) shift += each;
            atomX[a] = LayoutUnit.snap(atomX[a] + shift);
        }
        return out;
    }

    /** 片を折り返しの機会（UAX #14）ごとに割る。最後の小片は片の幅の残りで終える（{@code letter-spacing} の差し引きを保つ）。 */
    private static void split(LineFragment.Piece p, boolean spaceBefore, List<LineFragment.Piece> out, List<Boolean> gap, LayoutSession s) {
        BoxNode box = p.text();
        String t = box.text;
        Style st = box.style;
        FontFace face = s.face(st);
        FontStyle fs = st.facts.fontStyle;
        float x = p.x(), end = p.x() + p.width();
        boolean first = true;
        for (int i = p.start(); i < p.end(); ) {
            int b = InlineFlow.nextBreak(t, i, p.end(), st.facts.wordBreak);
            float w = b == p.end() ? end - x : face.advance(t.substring(i, b), fs);
            out.add(new LineFragment.Piece(box, i, b, x, w));
            gap.add(first && spaceBefore);
            first = false;
            x += w;
            i = b;
        }
    }

    private boolean opportunity(LineFragment.Piece a, LineFragment.Piece b, boolean spaceBetween) {
        if (mode == Mode.NONE) return false;
        String at = a.text().text, bt = b.text().text;
        char last = at.charAt(a.end() - 1);
        if (spaceBetween || last == ' ' || last == ' ') return true;
        if (mode == Mode.INTER_WORD) return false;
        if (mode == Mode.INTER_CHARACTER) return true;
        return cjk(last) || cjk(bt.charAt(b.start()));
    }

    private static boolean cjk(char c) {
        return (c >= 0x2E80 && c <= 0x9FFF) || (c >= 0xAC00 && c <= 0xD7AF) || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0xFF00 && c <= 0xFFEF);
    }
}
