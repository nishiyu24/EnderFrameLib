package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxKind;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.StyleFacts;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;
import com.nishiyu.enderframelib.core.text.LineBreaker;
import com.nishiyu.enderframelib.core.text.TextShaper;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import com.nishiyu.enderframelib.core.text.model.WordBreak;

import java.util.List;

/**
 * インライン整形の入口（Engine v3 M2a・M2b-1）。ブロックコンテナの中のインライン水準の子（文字の節・インライン要素・
 * インライン水準の原子）を平らに並べて {@link LineFragment} の列を作る。純粋な関数で、状態は呼び出しの局所だけ。
 *
 * <p>行を組むのは {@link InlineLineBuilder}、内在サイズは {@link InlineMeasure}、行の揃え（{@code text-align}・両端揃え）は
 * {@link LineAlign}。ここには 3 つが共有する文字の規則（折り返しの機会・タブ・行の高さ・ベースライン）を置く。</p>
 *
 * <p>折り返しの機会（CSS Text 3 §5）: 文字の並びの中は UAX #14（{@link LineBreaker}）。節の境目は、間に畳まれた空白があれば
 * その空白の {@code white-space}、無ければ後ろの節の {@code white-space} が折り返しを許し、かつ 2 文字の間が UAX #14 の機会
 * （{@link #breaksBetween}）のときだけ。インライン水準の原子の前後と {@code <wbr>} の位置は機会になる。</p>
 */
public final class InlineFlow {
    static final float EPS = TextShaper.WRAP_TOLERANCE;
    /** インライン水準の原子を、折り返しの判定で 1 文字として扱うときの文字（U+FFFC。表意文字と同じく前後で割れる）。 */
    static final char ATOM_CHAR = '￼';

    private InlineFlow() { }

    /** 整形の結果。{@code height} は行の高さの和。{@code pending} は中の流れの外の箱の静的位置（内容ボックス基準）。 */
    record Result(LineFragment[] lines, float height, List<OutOfFlow.Pending> pending) { }

    /**
     * {@code kids} を幅 {@code availW} で行に組む。{@code container} は行の支柱（strut）と揃えの持ち主。{@code prior} は持ち主の
     * 前回の断片（行に置いた原子の候補を引く。無ければ null）。
     */
    static Result layout(List<BoxNode> kids, Style container, float availW, LayoutFragment prior, LayoutSession s) {
        return layout(kids, container, availW, prior, Floats.Space.EMPTY, 0, s);
    }

    /** float を避けて組む（{@code exc} は容器の内容ボックス基準、{@code originY} はこの行の並びの上端の容器での位置）。 */
    static Result layout(List<BoxNode> kids, Style container, float availW, LayoutFragment prior, Floats.Space exc, float originY,
                         LayoutSession s) {
        InlineLineBuilder b = new InlineLineBuilder(container, availW, prior, exc, originY, s);
        for (BoxNode k : kids) b.add(k);
        return b.finish();
    }

    /** {@code kids} の内在サイズ（内容ボックスの幅）。 */
    static MinMaxSizes minMax(List<BoxNode> kids, LayoutSession s) {
        InlineMeasure m = new InlineMeasure(s);
        for (BoxNode k : kids) m.add(k);
        return m.result();
    }

    /** 1 行の高さ（{@code line-height}。{@code normal} は書体の推奨値）。 */
    public static float lineHeight(Style st, FontFace face) {
        StyleFacts f = st.facts;
        FontStyle fs = f.fontStyle;
        return switch (f.lineHeightKind) {
            case ComputedStyle.LH_PX -> LayoutUnit.snap(f.lineHeightValue);
            case ComputedStyle.LH_PERCENT, ComputedStyle.LH_EM, ComputedStyle.LH_NUMBER -> LayoutUnit.snap(f.lineHeightValue * fs.fontSizePx());
            default -> LayoutUnit.snap(face.normalLineHeight(fs));
        };
    }

    /** {@code text-indent}（宣言が無ければ 0。% は {@code availW} が基準）。 */
    static float textIndent(Style st, float availW, LayoutSession s) {
        long v = st.inherited.textIndent;
        if (!com.nishiyu.enderframelib.core.css.CssLength.isDeclared(v) || com.nishiyu.enderframelib.core.css.CssLength.isKeyword(v)) return 0;
        return LayoutUnit.snap(st.px(v, st.inherited.exprs, Float.isFinite(availW) ? availW : 0, s.viewportW(), s.viewportH()));
    }

    /** 文字の並びの上端からベースラインまで（半分の行間 + ascent）。 */
    public static float runBaseline(Style st, LayoutSession s) {
        FontFace face = s.face(st);
        FontStyle fs = st.facts.fontStyle;
        float asc = face.ascent(fs), desc = face.descent(fs);
        return TextShaper.leadingAbove(lineHeight(st, face), asc, desc) + asc;
    }

    /**
     * 断片の border-box の上端からベースラインまで（無ければ NaN）。{@code first} なら最初の行（flex のベースライン）、
     * そうでなければ最後の行（{@code inline-block} のベースライン）。
     */
    static float baselineOf(LayoutFragment f, boolean first) {
        if (f.lines != null && f.lines.length > 0) {
            LineFragment l = f.lines[first ? 0 : f.lines.length - 1];
            return f.insetTop + l.y() + l.baseline();
        }
        if (f.flow == null) return Float.NaN;
        FlowChunk[] cs = f.flow.chunks;
        for (int k = first ? 0 : cs.length - 1; first ? k < cs.length : k >= 0; k += first ? 1 : -1) {
            FlowChunk c = cs[k];
            for (int i = first ? 0 : c.frags.length - 1; first ? i < c.frags.length : i >= 0; i += first ? 1 : -1) {
                LayoutFragment ch = c.frags[i];
                if (ch.collapseThrough) continue;
                float b = baselineOf(ch, first);
                if (!Float.isNaN(b)) return f.insetTop + f.flow.y(k, i) + b;
            }
        }
        return Float.NaN;
    }

    /** {@code from} より後ろで最初の折り返し候補の位置（無ければ {@code to}）。 */
    public static int nextBreak(String t, int from, int to, WordBreak wb) {
        for (int i = t.offsetByCodePoints(from, 1); i < to; i = t.offsetByCodePoints(i, 1)) {
            if (LineBreaker.canBreakBefore(t, i, wb)) return i;
        }
        return to;
    }

    /**
     * 節の境目（前の節の最後の文字 {@code a}、次の節の最初の文字 {@code b}）が折り返しの機会か。文字の並びの中と同じ UAX #14
     * （{@link LineBreaker}。{@code word-break: keep-all} の和文・行頭禁則も効く）で決め、要素の境目そのものは機会にしない
     * （{@code h<span></span>ighway} は 1 語）。原子の後は表意文字と同じく機会。
     */
    static boolean breaksBetween(int a, int b, WordBreak wb) {
        if (a < 0 || b < 0) return false;
        if (a == ATOM_CHAR) return true;
        String pair = new StringBuilder(4).appendCodePoint(a).appendCodePoint(b).toString();
        return LineBreaker.canBreakBefore(pair, Character.charCount(a), wb);
    }

    static boolean isBreak(BoxNode n) {
        return n.kind != BoxKind.TEXT && "br".equalsIgnoreCase(n.node.tag);
    }

    /** {@code <wbr>}（折り返しの機会だけを作る要素）か。 */
    static boolean isWordBreakOpportunity(BoxNode n) {
        return n.kind != BoxKind.TEXT && "wbr".equalsIgnoreCase(n.node.tag);
    }

    /** インライン要素の左右の縁（{@code [始端, 終端]}）。 */
    static float[] edges(BoxNode n, float cbw, LayoutSession s) {
        BoxGeometry g = new BoxGeometry(n.style, cbw, s);
        return new float[]{g.mL + g.bL + g.pL, g.mR + g.bR + g.pR};
    }

    /** タブの間隔（{@code tab-size}。数なら空白の幅の倍数、長さならその px。既定は 8）。 */
    static float tabWidth(Style st, FontFace face, FontStyle fs, LayoutSession s) {
        float space = face.advance(" ", fs);
        String ts = st.inherited.tabSize;
        if (ts == null || ts.isBlank()) return 8 * space;
        String t = ts.trim();
        try {
            return Float.parseFloat(t) * space;
        } catch (NumberFormatException e) {
            return LengthResolver.compile(t).resolve(0, s.viewportW(), s.viewportH(), fs.fontSizePx(), st.inherited.rootFontSizePx);
        }
    }

    /**
     * 行の左端から {@code x} にあるタブの次の位置（CSS Text 3 §4.2: タブの間隔の倍数。残りが空白の半分未満なら次の倍数）。
     * 位置は行の始点から測る（旧経路の {@code LineBuilder.tabLayout} と同じ）。
     */
    static float tabStop(float x, float tabW, float spaceW) {
        if (!(tabW > 0)) return x;
        float next = (float) (Math.floor((x + 0.01f) / tabW) + 1) * tabW;
        if (next - x < spaceW / 2) next += tabW;
        return next;
    }
}
