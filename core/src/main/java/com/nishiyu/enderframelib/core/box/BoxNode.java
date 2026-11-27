package com.nishiyu.enderframelib.core.box;

import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

/**
 * 入力の箱（Engine v3 M2a）。可変の状態を一切持たない不変の記述で、{@link BoxTreeCompiler} が作る。
 *
 * <p>変わっていない部分木は前回と<b>同じインスタンス</b>が返るので、下流（レイアウトのキャッシュ）は参照同一性（{@code ==}）で
 * 「入力が同じ」を判定できる。{@code equals} / {@code hashCode} は定義しない（同一性のまま。弱参照の表の鍵にもなる）。</p>
 *
 * <p>{@link #node} は DOM の節への参照。旧計画の {@code int nodeId} ではなく参照にしたのは、DOM の番号が節の削除で解放され
 * 再利用される（{@code NodeIds}）ため、番号で「前回と同じ節か」を比べると別の節を取り違えるからである
 * （§3.3 M2a の知見）。描画（M3b）が要る番号は {@code node.nodeId()} で引ける。</p>
 *
 * <p><b>2 つの木</b>（M2c。計画書 §3.4.2-3）: {@link BoxTreeCompiler} が返す木は<b>最新の全ての群</b>を持つ。レイアウトが読むのは
 * {@link #layout} を辿った<b>レイアウトの入力の木</b>で、描画だけの変化（{@code color} など。{@code Style#sameLayout}）では前回の
 * 箱のまま（{@code ==}）なので、レイアウトのキャッシュが外れない。普段は {@code layout == this} で 2 つの木は全ての節を共有し、
 * 描画だけが変わった節とその祖先だけが、最新の群を持つ別の箱になる（その {@link #layout} は前回の箱で、描画だけの群は古いまま。
 * レイアウトは描画だけの群を読まない〔{@code ArchitectureHealthTest}〕）。最新の木とレイアウトの入力の木は同じ形（{@link #children}・
 * {@link #flow} の位置が対応する）なので、描画は 2 つを位置で並走して読む。</p>
 */
public final class BoxNode {
    /**
     * 疑似要素の種類（M2b-1）。疑似要素の箱とその中の文字の節は持ち主の要素の節を {@link #node} に持つ。{@link #ANONYMOUS} は表の匿名の箱
     * （M2b-4。{@link TableBoxes}）で、包んだ子の親の節を {@link #node} に持ち、{@link #children} は包んだ子の並び。{@link #ORTHOGONAL} は
     * 直交フローの根の流れの中身を包む箱（M2b-5。{@link OrthogonalBoxes}）で、根の節を {@link #node} に持つ。
     */
    public enum Pseudo { NONE, BEFORE, AFTER, MARKER, ANONYMOUS, ORTHOGONAL }

    /** 元の DOM の節（文書の根も含む）。疑似要素の箱では持ち主の要素。 */
    public final HtmlNode node;
    /**
     * レイアウトが読むスタイル（M1 の不変 {@link Style}）。文字の節は親の継承群を共有した {@link Style#forTextChild}。縦書きの文脈
     * （{@link #context}）の箱では物理のプロパティを論理の軸へ入れ替えたもの（{@code OrthogonalStyles#axisSwapped}）。
     */
    public final Style style;
    /**
     * カスケードの算出スタイル（軸を入れ替える前。子の継承元・アニメーションの前回の値・前回との照合に使う）。縦書きの文脈の外では
     * {@link #style} と同じ参照。
     */
    public final Style computed;
    /** この箱をコンパイルした縦書きの文脈の符号（{@link OrthogonalBoxes#childContext}。横書きの文脈なら 0。M2b-5）。 */
    final short context;
    /** 直交フローの根の種類の符号（{@link OrthogonalBoxes}。根でなければ 0。M2b-5）。 */
    public final byte orthogonal;
    public final BoxKind kind;
    /** 文字の節だけ非 null。親の {@code white-space} で正規化し {@code text-transform} を掛けた中身（{@link TextContent}）。 */
    public final String text;
    /**
     * DOM の子の箱の並び（32 子のチャンク列。DOM の子と 1 対 1 で、{@link BoxTreeCompiler} が前回の木と照合する）。
     * 葉と {@link BoxKind#NONE} は {@link BoxChunk#EMPTY}。
     */
    public final BoxChildren children;
    /** 疑似要素なら種類（それ以外は {@link Pseudo#NONE}）。 */
    public final Pseudo pseudo;
    /**
     * レイアウトが並べる子: {@link #children} の前後に {@code ::before} / {@code ::after} の箱を 1 個ずつのチャンクで足した並び。
     * 疑似要素が無ければ {@link #children} と同じ参照（追加の確保なし）。表の構造を補う箱が要る親では、匿名の表・行・セルで包み
     * 表・行グループ・行の子の空白だけの文字を除いた並び（{@link TableBoxes}。要らなければ同じ参照）。
     */
    public final BoxChildren flow;
    /** 置換要素・部品なら大きさを決める入力の写し（{@link ReplacedInput}）。それ以外は null。 */
    public final ReplacedInput replaced;
    /**
     * リスト項目の {@code ::marker} の箱（{@link MarkerBoxes}。無ければ null）。{@code list-style-position: inside} なら {@link #flow} の
     * 先頭にも入る（行の中の原子）。{@code outside} なら流れに入らず、レイアウトが項目の左外に置く（{@link #outsideMarker}）。
     */
    public final BoxNode marker;
    /** マーカーの箱なら組んだ番号（番号に依らなければ {@link MarkerBoxes#NO_ORDINAL}）。それ以外は {@link MarkerBoxes#NO_ORDINAL}。 */
    public final int ordinal;
    /**
     * 表のセルの {@code colspan} / {@code rowspan}・列の {@code span} の属性の写し（M2b-4。{@link TableBoxes#spanOf}）。属性はスタイルに
     * 現れないので入力側が写し、レイアウトは生きた DOM を読まない（属性だけの変化も箱の作り直しとして届く）。それ以外は 0。
     */
    public final int span;
    /** ポップオーバーの呼び出し元・ポップオーバー自身なら属性の写し（暗黙のアンカー。{@link PopoverLink}。M2b-5）。それ以外は null。 */
    public final PopoverLink popover;
    /**
     * ブロックコンテナの {@code ::first-line} のスタイル（無ければ null）。直交フローの根の中身を包む箱は根のものを持つ（1 行目は包む箱の
     * 行）。書体の指定が 1 行目の折り返しに効くので、レイアウトの入力の照合は {@link Style#sameLayout} で比べる。
     */
    public final Style firstLine;
    /**
     * Top Layer に入れる要素（{@code dialog}・{@code popover}）の {@code ::backdrop} のスタイル（規則が無い・他の要素は null。G5）。
     * 描画だけが読む（レイアウトの入力の照合に入れない）。
     */
    public final Style backdrop;
    /**
     * 流れの外の箱のアンカーの覚えたスクロール量（{@link RememberedScroll}。全て 0・アンカーを使わない箱は null。G8）。レイアウトの入力。
     */
    public final RememberedScroll anchorScroll;
    /**
     * 描画が読む DOM の値の写し（{@link DomValues}。M3b-1・決定 F。写すものが無ければ {@link DomValues#NONE}。null にならない）。
     * 描画だけが読む（レイアウトの入力の照合に入れない）。描画は生きた DOM を読まず、属性だけの変化も最新の箱の変化として届く。
     */
    public final DomValues values;
    /**
     * レイアウトの入力の箱（M2c。クラスの説明の「2 つの木」）。自分（{@code this}）か、描画だけが違う前回の箱か、子孫に描画だけが
     * 違う箱がある最新の箱のために作った、子を全て {@link #layout} に替えた箱。{@code layout.layout == layout} が常に成り立つ。
     */
    public final BoxNode layout;

    public BoxNode(HtmlNode node, Style style, BoxKind kind, String text, BoxChildren children) {
        this(node, style, kind, text, children, Pseudo.NONE, null, null, null, null, MarkerBoxes.NO_ORDINAL, (BoxNode) null);
    }

    /**
     * {@code before} / {@code after} は疑似要素の箱、{@code marker} は {@code ::marker} の箱（無ければ null）。{@code ordinal} はマーカーの
     * 箱の番号（{@link #ordinal}）。{@code prevLayout} は同じ箱の前回のレイアウトの入力の箱（{@link #layout}。無ければ null）。
     */
    BoxNode(HtmlNode node, Style style, BoxKind kind, String text, BoxChildren children, Pseudo pseudo, BoxNode before, BoxNode after,
            ReplacedInput replaced, BoxNode marker, int ordinal, BoxNode prevLayout) {
        this(node, style, kind, text, children, pseudo, before, after, replaced, marker, ordinal, prevLayout, DomValues.NONE);
    }

    /** {@code values} は描画が読む DOM の値の写し（{@link #values}。DOM に無い使い捨ての節の箱で、その節の属性を描画へ渡すとき）。 */
    BoxNode(HtmlNode node, Style style, BoxKind kind, String text, BoxChildren children, Pseudo pseudo, BoxNode before, BoxNode after,
            ReplacedInput replaced, BoxNode marker, int ordinal, BoxNode prevLayout, DomValues values) {
        this(node, style, kind, text, children, pseudo, before, after, replaced, marker, ordinal, null, style, 0, 0, null, null, null, null,
                null, values, prevLayout);
    }

    /** {@code prevFlow} は同じ節の前回の {@link #flow}（匿名の表の箱を前回の参照のまま使うための候補。無ければ null）。 */
    BoxNode(HtmlNode node, Style style, BoxKind kind, String text, BoxChildren children, Pseudo pseudo, BoxNode before, BoxNode after,
            ReplacedInput replaced, BoxNode marker, int ordinal, BoxChildren prevFlow) {
        this(node, style, kind, text, children, pseudo, before, after, replaced, marker, ordinal, prevFlow, style, 0, 0, null, null, null, null,
                null, DomValues.NONE, null);
    }

    /**
     * 縦書きの文脈の箱（M2b-5）。{@code computed} は軸を入れ替える前の算出スタイル、{@code context} は文脈の符号、
     * {@code orthogonal} は直交フローの根の符号で、根なら {@code wrapperStyle} の箱で流れの中身を包む（{@link OrthogonalBoxes#wrap}）。
     * {@code anchorScroll} は覚えたスクロール量（G8。無ければ null）、{@code values} は描画が読む DOM の値の写し（M3b-1）、
     * {@code prevLayout} は同じ箱の前回のレイアウトの入力の箱（M2c。無ければ null）。
     */
    BoxNode(HtmlNode node, Style style, BoxKind kind, String text, BoxChildren children, Pseudo pseudo, BoxNode before, BoxNode after,
            ReplacedInput replaced, BoxNode marker, int ordinal, BoxChildren prevFlow, Style computed, int context, int orthogonal,
            Style wrapperStyle, PopoverLink popover, Style firstLine, Style backdrop, RememberedScroll anchorScroll, DomValues values,
            BoxNode prevLayout) {
        this.popover = popover;
        this.values = values != null ? values : DomValues.NONE;
        this.anchorScroll = anchorScroll;
        this.firstLine = firstLine;
        this.backdrop = backdrop;
        this.node = node;
        this.style = style;
        this.computed = computed;
        this.context = (short) context;
        this.orthogonal = (byte) orthogonal;
        this.kind = kind;
        this.text = text;
        this.children = children;
        this.pseudo = pseudo;
        BoxNode lead = marker != null && MarkerBoxes.inside(style) ? marker : null;
        BoxChildren raw = before == null && after == null && lead == null ? children : BoxChildren.withPseudos(lead, before, children, after);
        this.flow = wrapperStyle != null ? OrthogonalBoxes.wrap(node, wrapperStyle, raw, prevFlow, firstLine) : TableBoxes.fixup(node, style, kind, raw, prevFlow);
        this.replaced = replaced;
        this.marker = marker;
        this.ordinal = ordinal;
        this.span = TableBoxes.spanOf(node, kind, pseudo);
        this.layout = layoutOf(prevLayout, before, after, wrapperStyle);
    }

    /**
     * レイアウトの入力の箱を決める（M2c）。レイアウトに効く入力（{@link Style#sameLayout} の群・種類・文字・子のレイアウトの入力の箱
     * ほか）が前回の {@code prevL} と同じなら {@code prevL}、子が全て自分自身をレイアウトの入力とするなら自分、どちらでもなければ子を
     * レイアウトの入力の箱に替えて作る。
     */
    private BoxNode layoutOf(BoxNode prevL, BoxNode before, BoxNode after, Style wrapperStyle) {
        BoxChildren kidsL = children.layoutView(prevL != null ? prevL.children : null);
        BoxNode bL = before != null ? before.layout : null, aL = after != null ? after.layout : null;
        BoxNode mL = marker != null ? marker.layout : null;
        if (prevL != null && prevL.sameLayoutInput(this, kidsL, bL, aL, mL, wrapperStyle)) return prevL;
        if (kidsL == children && bL == before && aL == after && mL == marker) return this;
        return new BoxNode(node, style, kind, text, kidsL, pseudo, bL, aL, replaced, mL, ordinal, prevL != null ? prevL.flow : null,
                computed, context, orthogonal, wrapperStyle, popover, firstLine, backdrop, anchorScroll, values, null);
    }

    /** この（前回のレイアウトの入力の）箱が、新しい箱 {@code b}（子のレイアウトの入力は {@code kidsL} ほか）とレイアウトに効く入力で同じか。 */
    private boolean sameLayoutInput(BoxNode b, BoxChildren kidsL, BoxNode bL, BoxNode aL, BoxNode mL, Style wrapperStyle) {
        if (node != b.node || kind != b.kind || pseudo != b.pseudo || children != kidsL || marker != mL || ordinal != b.ordinal
                || context != b.context || orthogonal != b.orthogonal || span != b.span || !java.util.Objects.equals(text, b.text)
                || !java.util.Objects.equals(replaced, b.replaced) || !java.util.Objects.equals(popover, b.popover)
                || !java.util.Objects.equals(anchorScroll, b.anchorScroll)
                || pseudoChild(Pseudo.BEFORE) != bL || pseudoChild(Pseudo.AFTER) != aL
                || (firstLine == null ? b.firstLine != null : b.firstLine == null || !firstLine.sameLayout(b.firstLine))
                || !style.sameLayout(b.style) || !computed.sameLayout(b.computed)) return false;
        if (orthogonal == 0) return true;
        BoxNode w = OrthogonalBoxes.wrapperOf(this);
        return w != null && wrapperStyle != null && w.style.sameLayout(wrapperStyle);
    }

    /** {@link #flow} に足した疑似要素の箱（{@code ::before} / {@code ::after}。{@code ::marker} は {@link #marker}。無ければ null）。 */
    public BoxNode pseudoChild(Pseudo which) {
        if (which == Pseudo.MARKER) return marker;
        if (flow == children) return null;
        if (orthogonal != 0) { // 直交フローの根: 包む箱の中の並び（位置指定の疑似要素は根の直下）
            BoxNode w = OrthogonalBoxes.wrapperOf(this);
            BoxNode in = w != null && w.flow != w.children ? pseudoIn(w.flow, which) : w != null ? pseudoIn(w.children, which) : null;
            return in != null ? in : pseudoIn(flow, which);
        }
        return pseudoIn(flow, which);
    }

    /** 並び {@code kids} の先頭（末尾）の疑似要素の箱。表の匿名の箱に包まれていれば中を辿る（{@link TableBoxes}）。 */
    private static BoxNode pseudoIn(BoxChildren kids, Pseudo which) {
        if (kids.size() == 0) return null;
        if (which == Pseudo.BEFORE) {
            int first = kids.get(0).pseudo == Pseudo.MARKER ? 1 : 0;
            if (kids.size() <= first) return null;
            BoxNode b = kids.get(first);
            return wraps(b) ? pseudoIn(b.children, which) : b.pseudo == which ? b : null;
        }
        BoxNode b = kids.get(kids.size() - 1);
        return wraps(b) ? pseudoIn(b.children, which) : b.pseudo == which ? b : null;
    }

    /** 子を包む匿名の箱（表の匿名の箱・直交フローの包む箱）か。 */
    private static boolean wraps(BoxNode b) {
        return b.pseudo == Pseudo.ANONYMOUS || b.pseudo == Pseudo.ORTHOGONAL;
    }

    /** DOM に節を持たない箱（疑似要素・表の匿名の箱・直交フローの包む箱）か（{@link #pseudo} が {@link Pseudo#NONE} でない）。 */
    public boolean generated() {
        return pseudo != Pseudo.NONE;
    }

    /** 表の匿名の箱か（{@link Pseudo#ANONYMOUS}）。 */
    public boolean anonymousTableBox() {
        return pseudo == Pseudo.ANONYMOUS;
    }

    /** 直交フローの根の中身を包む箱か（{@link Pseudo#ORTHOGONAL}）。 */
    public boolean orthogonalWrapper() {
        return pseudo == Pseudo.ORTHOGONAL;
    }

    /** 流れに入らない（{@code list-style-position: outside} の）マーカーの箱（無ければ null）。 */
    public BoxNode outsideMarker() {
        return marker != null && (flow.size() == 0 || flow.get(0) != marker) ? marker : null;
    }

    /** マーカーだけを差し替えた箱（{@link MarkerBoxes#renumber} が番号を直すとき）。 */
    BoxNode withMarker(BoxNode m) {
        BoxNode w = orthogonal != 0 ? OrthogonalBoxes.wrapperOf(this) : null;
        return new BoxNode(node, style, kind, text, children, pseudo, pseudoChild(Pseudo.BEFORE), pseudoChild(Pseudo.AFTER), replaced, m,
                ordinal, flow, computed, context, orthogonal, w != null ? w.style : null, popover, firstLine, backdrop, anchorScroll, values,
                layout);
    }

    /** 中身が無い・空白だけで畳まれる文字の節か（ブロックの流れでは箱を作らない）。 */
    public boolean collapsibleWhitespace() {
        if (kind != BoxKind.TEXT) return false;
        if (!style.facts.whiteSpace.collapses()) return text.isEmpty();
        for (int i = 0, n = text.length(); i < n; i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\n' && c != '\t' && c != '\r' && c != '\f') return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return kind == BoxKind.TEXT ? "#text\"" + text + "\"" : kind + "<" + node.tag + ">[" + children.size() + "]";
    }
}
