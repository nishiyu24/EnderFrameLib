package com.nishiyu.enderframelib.core.dom.node;

import com.nishiyu.enderframelib.core.dom.*;
import com.nishiyu.enderframelib.core.dom.node.*;
import com.nishiyu.enderframelib.core.dom.parser.*;
import com.nishiyu.enderframelib.core.dom.parser.*;

import com.nishiyu.enderframelib.api.dom.DOMException;
import com.nishiyu.enderframelib.api.dom.DOMNodes.Text;

/**
 * WHATWG DOM Standard の {@link Text} インターフェースを実装したクラス。
 */
public class HtmlText extends HtmlNode implements Text {

    public HtmlText(String data) {
        super(NodeType.TEXT, "#text");
        this.text = data != null ? data : "";
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
