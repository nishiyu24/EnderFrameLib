package com.nishiyu.enderframelib.core.box;

import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.WritingModes;
import com.nishiyu.enderframelib.core.css.model.Position;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;

import java.util.ArrayList;
import java.util.List;

/**
 * 直交フローの根と、その流れの中身を包む箱（Engine v3 M2b-5。旧 {@code BoxTreeBuilder.buildBox} / {@code wrapOrthogonalContent} の移植）。
 *
 * <p>根は 2 種類: 横書きの中の縦書きの根（{@code writing-mode: vertical-* / sideways-*}）と、縦書きの中の横書きへ戻す根
 * （{@code horizontal-tb}）。どちらもブロックコンテナ・flex・grid・表の要素だけ（置換要素は対象外）。根の流れの中身（位置指定・
 * {@code display: none} 以外の子と疑似要素）は {@link BoxNode.Pseudo#ORTHOGONAL} の箱 1 つに入り、位置指定の子は根の直下に残る
 * （包含ブロックを回転の内側へ入れない）。{@link BoxNode#children} は DOM と 1 対 1 のまま、包むのは {@link BoxNode#flow} だけ。</p>
 *
 * <p>根の種類の符号（{@link BoxNode#orthogonal}）: 0 は根でない。1〜4 は縦書きの根で、値は自分の書字方向。{@link #RETURN} を足した値は
 * 横書きへ戻す根で、下位は外の縦書きの書字方向。</p>
 */
public final class OrthogonalBoxes {
    /** 横書きへ戻す根の印。 */
    public static final int RETURN = 0x10;

    private OrthogonalBoxes() { }

    /** 書字方向 {@code contextFlow} の文脈に置かれた要素の根の種類の符号（根でなければ 0）。{@code computed} は軸を入れ替える前の算出スタイル。 */
    static int rootCode(HtmlNode n, Style computed, BoxKind kind, int contextFlow) {
        if (n.nodeType != NodeType.ELEMENT || n.isPseudoNode || !candidate(kind)) return 0;
        if (com.nishiyu.enderframelib.core.replaced.ReplacedElements.isReplacedTag(n.tag)
                || com.nishiyu.enderframelib.core.dom.node.ElementTraits.isEmbeddedMedia(n.tag)) return 0; // 置換要素は根にならない
        int own = WritingModes.flowOf(computed.inherited.writingMode);
        if (contextFlow == WritingModes.HORIZONTAL) return own;
        return own == WritingModes.HORIZONTAL ? RETURN | contextFlow : 0;
    }

    /** 直交フローの根になれる箱の種類（ブロックコンテナ・flex・grid・表）。 */
    private static boolean candidate(BoxKind k) {
        return switch (k) {
            case BLOCK, INLINE_BLOCK, FLEX, INLINE_FLEX, GRID, INLINE_GRID, TABLE, INLINE_TABLE -> true;
            default -> false;
        };
    }

    /** 文脈の符号: 縦書きの根の直下の子（位置指定なら包む箱の外で物理の軸のまま置く）。 */
    private static final int ESCAPE = 0x100;
    /** 文脈の符号: 縦書きの文脈にあるが軸を入れ替えない部分木（縦書きの根の直下の位置指定の子とその子孫。旧 {@code clearAxisFlowOutsideWrapper}）。 */
    private static final int NO_SWAP = 0x200;

    /** 文脈の符号から書字方向（{@link WritingModes} の定数）。 */
    static int flowOf(int context) {
        return context & 0x0F;
    }

    /** 文脈 {@code context} に置かれた算出スタイル {@code computed} の箱が軸を入れ替える書字方向（入れ替えないなら 0）。 */
    static int axisOf(int context, Style computed) {
        if ((context & NO_SWAP) != 0 || ((context & ESCAPE) != 0 && outOfFlow(computed))) return WritingModes.HORIZONTAL;
        return flowOf(context);
    }

    /**
     * 根の符号 {@code code}・自分の文脈 {@code context}・算出スタイル {@code computed} の箱の子の文脈。縦書きの根の子は自分の書字方向
     * （位置指定の子は包む箱の外なので印を付ける）、横書きへ戻す根の子は横書き、それ以外は自分の文脈（軸を入れ替えない部分木は印を引き継ぐ）。
     */
    static int childContext(int code, int context, Style computed) {
        if (code != 0) return (code & RETURN) != 0 ? WritingModes.HORIZONTAL : code | ESCAPE;
        int flow = flowOf(context);
        if (flow == WritingModes.HORIZONTAL) return flow;
        return axisOf(context, computed) == WritingModes.HORIZONTAL ? flow | NO_SWAP : flow;
    }

    private static boolean outOfFlow(Style st) {
        Position p = st.box.position;
        return p == Position.ABSOLUTE || p == Position.FIXED;
    }

    /** 横書きへ戻す根か。 */
    public static boolean returnsToHorizontal(int code) {
        return (code & RETURN) != 0;
    }

    /** 縦書きの側の書字方向（縦書きの根は自分、横書きへ戻す根は外の縦書き）。 */
    public static int verticalFlow(int code) {
        return code & 0x0F;
    }

    /** 包む箱の {@code display}。 */
    static String wrapperDisplay(BoxKind rootKind, int code) {
        return switch (rootKind) {
            case FLEX, INLINE_FLEX -> "flex";
            case GRID, INLINE_GRID -> "grid";
            case TABLE, INLINE_TABLE -> "table";
            case INLINE_BLOCK -> returnsToHorizontal(code) ? "inline-block" : "block";
            default -> "block";
        };
    }

    /**
     * 根の流れ: 包む箱 1 つと、根の直下に残る位置指定の子。{@code raw} は疑似要素を足した子の並び、{@code prevFlow} は前回の流れ
     * （包む箱が同じスタイルで同じ子なら前回の参照を使う）。
     */
    static BoxChildren wrap(HtmlNode owner, Style wrapperStyle, BoxChildren raw, BoxChildren prevFlow, Style firstLine) {
        List<BoxNode> inner = null, outside = new ArrayList<>();
        for (int i = 0, n = raw.size(); i < n; i++) {
            BoxNode k = raw.get(i);
            if (outsideOfWrapper(k)) {
                if (inner == null) {
                    inner = new ArrayList<>(n);
                    for (int j = 0; j < i; j++) inner.add(raw.get(j));
                }
                outside.add(k);
            } else if (inner != null) {
                inner.add(k);
            }
        }
        BoxChildren kids = inner == null ? raw : BoxChildren.of(inner);
        BoxNode prevWrapper = prevFlow != null && prevFlow.size() > 0 && prevFlow.get(0).pseudo == BoxNode.Pseudo.ORTHOGONAL ? prevFlow.get(0) : null;
        BoxNode wrapper;
        if (prevWrapper != null && prevWrapper.style == wrapperStyle && prevWrapper.children == kids && prevWrapper.firstLine == firstLine) {
            wrapper = prevWrapper;
        } else {
            BoxKind kind = BoxKind.of(owner, wrapperStyle);
            wrapper = new BoxNode(owner, wrapperStyle, kind, null, kids, BoxNode.Pseudo.ORTHOGONAL, null, null, null, null,
                    MarkerBoxes.NO_ORDINAL, prevWrapper != null ? prevWrapper.flow : null, wrapperStyle, 0, 0, null, null, firstLine, null, null);
        }
        if (outside.isEmpty() && prevFlow != null && prevFlow.size() == 1 && prevFlow.get(0) == wrapper) return prevFlow;
        List<BoxNode> all = new ArrayList<>(1 + outside.size());
        all.add(wrapper);
        all.addAll(outside);
        return BoxChildren.of(all);
    }

    /** 根の直下に残す子（位置指定。包含ブロックを回転の内側へ入れない）。 */
    private static boolean outsideOfWrapper(BoxNode k) {
        if (k.kind == BoxKind.NONE || k.kind == BoxKind.TEXT) return false;
        Position p = k.style.box.position;
        return p == Position.ABSOLUTE || p == Position.FIXED;
    }

    /** 根の流れの中身を包む箱（無ければ null）。 */
    public static BoxNode wrapperOf(BoxNode root) {
        BoxChildren f = root.flow;
        return f.size() > 0 && f.get(0).pseudo == BoxNode.Pseudo.ORTHOGONAL ? f.get(0) : null;
    }
}
