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
    /** 元の DOM の節（文書の根も含む）。 */
    public final HtmlNode node;
    /** 算出スタイル（M1 の不変 {@link Style}）。文字の節は親の継承群を共有した {@link Style#forTextChild}。 */
    public final Style style;
    public final BoxKind kind;
    /** 文字の節だけ非 null。親の {@code white-space} で正規化し {@code text-transform} を掛けた中身（{@link TextContent}）。 */
    public final String text;
    /** 子の並び（32 子のチャンク列）。葉と {@link BoxKind#NONE} は {@link BoxChunk#EMPTY}。 */
    public final BoxChildren children;

    public BoxNode(HtmlNode node, Style style, BoxKind kind, String text, BoxChildren children) {
        this.node = node;
        this.style = style;
        this.kind = kind;
        this.text = text;
        this.children = children;
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
