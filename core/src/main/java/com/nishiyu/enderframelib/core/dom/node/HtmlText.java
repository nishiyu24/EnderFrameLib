package com.nishiyu.enderframelib.core.dom.node;


import com.nishiyu.enderframelib.api.dom.DOMException;
import com.nishiyu.enderframelib.api.dom.DOMNodes.Text;

/**
 * WHATWG DOM Standard の {@link Text} インターフェースを実装したクラス。
 */
public class HtmlText extends HtmlNode implements Text {

    public HtmlText(String data) {
        super(NodeType.TEXT, "#text");
        this.text = data == null ? "" : isShortWhitespace(data) ? data.intern() : data;
    }

    /**
     * 要素の間の改行と字下げ（{@code "\n    "}）は文書の中・セッションの間で同じ文字列が大量に繰り返されるので、
     * 1 つを共有する（A16②: 空白の節 1 つあたり文字列と配列の約 53 バイト）。
     */
    private static boolean isShortWhitespace(String s) {
        int n = s.length();
        if (n == 0 || n > 64) return false;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\n' && c != '\t' && c != '\r') return false;
        }
        return true;
    }

    @Override
    public String getData() {
        return text;
    }

    @Override
    public void setData(String data) {
        String oldVal = this.text;
        this.text = data != null ? data : "";
        if (ownerDocument != null) ownerDocument.rangesReplaceData(this, 0, oldVal.length(), this.text.length());
        MutationObserverManager.queueCharacterData(this, oldVal);
        notifyDomChanged(null);
    }

    @Override
    public int getLength() {
        return text.length();
    }

    @Override
    public Text splitText(int offset) {
        if (offset < 0 || offset > text.length()) {
            throw new DOMException("Index out of bounds in splitText.", "IndexSizeError");
        }
        String secondHalf = text.substring(offset);
        String oldVal = this.text;
        this.text = text.substring(0, offset);
        MutationObserverManager.queueCharacterData(this, oldVal);
        notifyDomChanged(null);

        HtmlText newText = new HtmlText(secondHalf);
        newText.ownerDocument = this.ownerDocument;
        if (ownerDocument != null) ownerDocument.rangesSplitText(this, offset, newText);
        if (this.parent != null) {
            this.parent.insertBefore(newText, this.getNextSibling());
        }
        return newText;
    }
}
