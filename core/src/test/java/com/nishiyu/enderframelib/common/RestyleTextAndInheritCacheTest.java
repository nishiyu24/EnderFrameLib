package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Q5: {@code inheritsExplicitly} の版キャッシュと {@code LayoutBox.updateStyle} の文字正規化省略の回帰テスト。 */
public class RestyleTextAndInheritCacheTest {

    @Test
    public void containsInheritIgnoreCaseMatchesAsciiCaseVariantsWithoutFalsePositives() {
        assertFalse(InteractionState.containsInheritIgnoreCase(null));
        assertFalse(InteractionState.containsInheritIgnoreCase(""));
        assertFalse(InteractionState.containsInheritIgnoreCase("color: red"));
        assertFalse(InteractionState.containsInheritIgnoreCase("inheri"));
        assertTrue(InteractionState.containsInheritIgnoreCase("inherit"));
        assertTrue(InteractionState.containsInheritIgnoreCase("background-color: INHERIT; color: red"));
        assertTrue(InteractionState.containsInheritIgnoreCase("margin: InHeRiT"));
    }

    @Test
    public void inheritsExplicitlyTracksAttrVersionAcrossStyleAndNonStyleMutations() {
        String html = "<div id='p' style='background-color:red'><div id='c' style='background-color:INHERIT'>child</div></div>";
        StandaloneView view = new StandaloneView(html, 300, 200, FontResolver.FALLBACK);
        view.ui().flushInvalidation();

        HtmlNode parent = view.root().getNodeById("p");
        HtmlNode child = view.root().getNodeById("c");
        InteractionState state = view.ui().interactionState();
        InteractionState.StateFlags childFlags = state.get(child);
        assertTrue(childFlags.inheritsExplicitly);
        assertEquals(child.attrVersion(), childFlags.inheritsAttrVersion);

        // 無関係な属性の変更では inherit 判定を壊さない
        child.setAttribute("data-tag", "one");
        view.ui().flushInvalidation();
        assertTrue(childFlags.inheritsExplicitly);
        assertEquals(child.attrVersion(), childFlags.inheritsAttrVersion);

        // 親の非継承プロパティ変更が child の background-color に届く
        parent.setAttribute("style", "background-color:blue");
        view.ui().flushInvalidation();
        assertEquals("blue", state.get(child).computedStyle.get("background-color"));

        // inherit を外したら false へ戻る
        child.setAttribute("style", "background-color:green");
        view.ui().flushInvalidation();
        assertFalse(childFlags.inheritsExplicitly);
        assertEquals(child.attrVersion(), childFlags.inheritsAttrVersion);
        parent.setAttribute("style", "background-color:yellow");
        view.ui().flushInvalidation();
        assertEquals("green", state.get(child).computedStyle.get("background-color"));
    }

    @Test
    public void updateStyleSkipsUnchangedTextAndRecomputesWhenTextTransformOrWhiteSpaceChanges() {
        String html = "<pre id='box' style='color:red;white-space:pre;text-transform:none'>  hello   world  </pre>";
        StandaloneView view = new StandaloneView(html, 300, 200, FontResolver.FALLBACK);
        view.ui().flushInvalidation();

        HtmlNode boxNode = view.root().getNodeById("box");
        LayoutBox textBox = findFirstTextBox(view.ui().layoutRoot(), boxNode);
        assertNotNull(textBox);
        String initialText = textBox.displayText;
        assertEquals("  hello   world  ", initialText);

        // color のみ変更: displayText インスタンスごとそのまま保たれる（再正規化・大文字小文字変換を省略）
        boxNode.setAttribute("style", "color:blue;white-space:pre;text-transform:none");
        view.ui().flushInvalidation();
        LayoutBox afterColor = findFirstTextBox(view.ui().layoutRoot(), boxNode);
        assertSame(textBox, afterColor);
        assertSame(initialText, afterColor.displayText);

        // text-transform 変更: 大文字化される
        boxNode.setAttribute("style", "color:blue;white-space:pre;text-transform:uppercase");
        view.ui().flushInvalidation();
        LayoutBox afterTransform = findFirstTextBox(view.ui().layoutRoot(), boxNode);
        assertEquals("  HELLO   WORLD  ", afterTransform.displayText);

        // white-space: normal 変更: 連続空白が畳まれる
        boxNode.setAttribute("style", "color:blue;white-space:normal;text-transform:uppercase");
        view.ui().flushInvalidation();
        LayoutBox afterNormal = findFirstTextBox(view.ui().layoutRoot(), boxNode);
        assertEquals(" HELLO WORLD ", afterNormal.displayText);

        // LayoutBox.updateStyle を直接呼んで white-space 変化時にも再正規化されることを確かめる
        com.nishiyu.enderframelib.core.css.ComputedStyle preStyle =
                new com.nishiyu.enderframelib.core.css.ComputedStyle(java.util.Map.of("white-space", "pre", "text-transform", "uppercase"));
        afterNormal.updateStyle(preStyle);
        assertEquals("  HELLO   WORLD  ", afterNormal.displayText);

        // 本文テキスト変更: 新しいテキストで再計算される
        boxNode.setAttribute("style", "color:blue;white-space:pre;text-transform:uppercase");
        boxNode.setTextContent("  abc  ");
        view.ui().flushInvalidation();
        LayoutBox afterText = findFirstTextBox(view.ui().layoutRoot(), boxNode);
        assertEquals("  ABC  ", afterText.displayText);
    }

    private static LayoutBox findFirstTextBox(LayoutBox root, HtmlNode owner) {
        if (root == null) return null;
        if (root.node.nodeType == NodeType.TEXT && root.node.parent == owner) return root;
        for (LayoutBox child : List.copyOf(root.children)) {
            LayoutBox hit = findFirstTextBox(child, owner);
            if (hit != null) return hit;
        }
        return null;
    }
}
