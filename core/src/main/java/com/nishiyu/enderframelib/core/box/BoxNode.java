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
 * 再利用される（{@code DocumentArena}）ため、番号で「前回と同じ節か」を比べると別の節を取り違えるからである
 * （§3.3 M2a の知見）。描画（M3b）が要る番号は {@code node.nodeId()} で引ける。</p>
 */
public final class BoxNode {
    /** 疑似要素の種類（M2b-1）。疑似要素の箱とその中の文字の節は持ち主の要素の節を {@link #node} に持つ。 */
    public enum Pseudo { NONE, BEFORE, AFTER, MARKER }

    /** 元の DOM の節（文書の根も含む）。疑似要素の箱では持ち主の要素。 */
    public final HtmlNode node;
    /** 算出スタイル（M1 の不変 {@link Style}）。文字の節は親の継承群を共有した {@link Style#forTextChild}。 */
    public final Style style;
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
     * 疑似要素が無ければ {@link #children} と同じ参照（追加の確保なし）。
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

    public BoxNode(HtmlNode node, Style style, BoxKind kind, String text, BoxChildren children) {
        this(node, style, kind, text, children, Pseudo.NONE, null, null, null, null, MarkerBoxes.NO_ORDINAL);
    }

    /**
     * {@code before} / {@code after} は疑似要素の箱、{@code marker} は {@code ::marker} の箱（無ければ null）。{@code ordinal} はマーカーの
     * 箱の番号（{@link #ordinal}）。
     */
    BoxNode(HtmlNode node, Style style, BoxKind kind, String text, BoxChildren children, Pseudo pseudo, BoxNode before, BoxNode after,
            ReplacedInput replaced, BoxNode marker, int ordinal) {
        this.node = node;
        this.style = style;
        this.kind = kind;
        this.text = text;
        this.children = children;
        this.pseudo = pseudo;
        BoxNode lead = marker != null && MarkerBoxes.inside(style) ? marker : null;
        this.flow = before == null && after == null && lead == null ? children : BoxChildren.withPseudos(lead, before, children, after);
        this.replaced = replaced;
        this.marker = marker;
        this.ordinal = ordinal;
    }

    /** {@link #flow} に足した疑似要素の箱（{@code ::before} / {@code ::after}。{@code ::marker} は {@link #marker}。無ければ null）。 */
    public BoxNode pseudoChild(Pseudo which) {
        if (which == Pseudo.MARKER) return marker;
        if (flow == children || flow.size() == 0) return null;
        int first = flow.get(0).pseudo == Pseudo.MARKER ? 1 : 0;
        if (which == Pseudo.BEFORE) {
            if (flow.size() <= first) return null;
            BoxNode b = flow.get(first);
            return b.pseudo == which ? b : null;
        }
        BoxNode b = flow.get(flow.size() - 1);
        return b.pseudo == which ? b : null;
    }

    /** 流れに入らない（{@code list-style-position: outside} の）マーカーの箱（無ければ null）。 */
    public BoxNode outsideMarker() {
        return marker != null && (flow.size() == 0 || flow.get(0) != marker) ? marker : null;
    }

    /** マーカーだけを差し替えた箱（{@link MarkerBoxes#renumber} が番号を直すとき）。 */
    BoxNode withMarker(BoxNode m) {
        return new BoxNode(node, style, kind, text, children, pseudo, pseudoChild(Pseudo.BEFORE), pseudoChild(Pseudo.AFTER), replaced, m,
                ordinal);
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
