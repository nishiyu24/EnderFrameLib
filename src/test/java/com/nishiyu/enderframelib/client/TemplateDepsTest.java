package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.core.dom.Bindings.TemplateDeps;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/**
 * キー単位の依存追跡（フェーズ 5-9）。
 * ここが壊れると、バインディングの更新が黙って届かなくなる。
 */
public class TemplateDepsTest {

    private static HtmlNode parse(String html) {
        return HtmlParser.parse(html);
    }

    private static HtmlNode findByTag(HtmlNode root, String tag) {
        if (tag.equalsIgnoreCase(root.tag)) return root;
        for (HtmlNode child : root.children) {
            HtmlNode found = findByTag(child, tag);
            if (found != null) return found;
        }
        return null;
    }

    private static HtmlNode findById(HtmlNode root, String id) {
        if (id.equals(root.attrs.get("id"))) return root;
        for (HtmlNode child : root.children) {
            HtmlNode found = findById(child, id);
            if (found != null) return found;
        }
        return null;
    }

    @Test
    public void placeholderKeysAreDetected() {
        HtmlNode root = parse("<div><span>Hello {{userName}}, you have {{count}} items</span></div>");
        Set<String> deps = new TemplateDeps().index(root);

        assertTrue(deps.contains("userName"), "{{userName}} が依存として検出されていない: " + deps);
        assertTrue(deps.contains("count"), "{{count}} が依存として検出されていない: " + deps);
        assertFalse(deps.contains(TemplateDeps.ALWAYS), "静的なプレースホルダに ALWAYS は不要: " + deps);
    }

    @Test
    public void dottedKeysRegisterBothFullTokenAndRootSegment() {
        HtmlNode root = parse("<div><span>{{user.profile.name}}</span></div>");
        Set<String> deps = new TemplateDeps().index(root);

        assertTrue(deps.contains("user.profile.name"), "完全なキーが必要: " + deps);
        assertTrue(deps.contains("user"), "ドットの先頭セグメントも必要: " + deps);
    }

    @Test
    public void bindingAttributesAreDetected() {
        HtmlNode root = parse("<div data-bind=\"text:title, value:amount, style.color:accent\"></div>");
        Set<String> deps = new TemplateDeps().index(root);

        assertTrue(deps.contains("title"), deps.toString());
        assertTrue(deps.contains("amount"), deps.toString());
        assertTrue(deps.contains("accent"), deps.toString());
        assertFalse(deps.contains("text"), "バインド先の名前はキーではない: " + deps);
    }

    @Test
    public void dataModelAndListAndForAreDetected() {
        HtmlNode root = parse(
                "<div>"
                        + "<input data-model=\"searchText\" />"
                        + "<ul data-list=\"results\"></ul>"
                        + "<ul data-for=\"row in tableRows\"></ul>"
                        + "</div>");
        Set<String> deps = new TemplateDeps().index(root);

        assertTrue(deps.contains("searchText"), deps.toString());
        assertTrue(deps.contains("results"), deps.toString());
        assertTrue(deps.contains("tableRows"), "data-for のリストキーが必要: " + deps);
        assertFalse(deps.contains("row"), "ループ変数名はストアキーではない: " + deps);
    }

    @Test
    public void conditionExpressionKeysAreDetected() {
        HtmlNode root = parse(
                "<div>"
                        + "<p data-if=\"isReady\"></p>"
                        + "<p data-show=\"stock > 0\"></p>"
                        + "<p data-class=\"active:tabIndex == 2\"></p>"
                        + "</div>");
        Set<String> deps = new TemplateDeps().index(root);

        assertTrue(deps.contains("isReady"), deps.toString());
        assertTrue(deps.contains("stock"), deps.toString());
        assertTrue(deps.contains("tabIndex"), deps.toString());
    }

    @Test
    public void i18nAndEnvPlaceholdersForceAlways() {
        HtmlNode i18n = parse("<div><span data-i18n=\"gui.title\"></span></div>");
        assertTrue(new TemplateDeps().index(i18n).contains(TemplateDeps.ALWAYS),
                "i18n 属性は ALWAYS を立てる必要がある");

        HtmlNode env = parse("<div><span>FPS: {{@fps}}</span></div>");
        assertTrue(new TemplateDeps().index(env).contains(TemplateDeps.ALWAYS),
                "{{@env}} プレースホルダは ALWAYS を立てる必要がある");
    }

    @Test
    public void depsPropagateToAncestorsButAlwaysDoesNotEraseSiblingPruning() {
        HtmlNode root = parse(
                "<div>"
                        + "<section id=\"live\"><span>{{@fps}}</span></section>"
                        + "<section id=\"static\"><span>{{count}}</span></section>"
                        + "</div>");
        TemplateDeps index = new TemplateDeps();
        Set<String> rootDeps = index.index(root);

        // ALWAYS はルートまで伝播するが、他のキーを潰さない。
        assertTrue(rootDeps.contains(TemplateDeps.ALWAYS), rootDeps.toString());
        assertTrue(rootDeps.contains("count"), "ALWAYS が他のキーを潰してはいけない: " + rootDeps);

        HtmlNode staticSection = findById(root, "static");
        assertNotNull(staticSection, "テスト対象のノードが見つからない");

        // count だけが変わったとき、ALWAYS を含む兄弟とは独立に判定できる。
        assertFalse(TemplateDeps.canSkip(index.lookup(staticSection), Set.of("count")),
                "count に依存する部分木は飛ばせない");
        assertTrue(TemplateDeps.canSkip(index.lookup(staticSection), Set.of("unrelated")),
                "無関係なキーなら飛ばせる");
    }

    @Test
    public void unrelatedKeyChangeSkipsEntireDocument() {
        HtmlNode root = parse("<div><span>{{count}}</span></div>");
        TemplateDeps index = new TemplateDeps();

        assertTrue(TemplateDeps.canSkip(index.index(root), Set.of("somethingElse")),
                "文書が参照しないキーの変更では走査不要");
        assertFalse(TemplateDeps.canSkip(index.index(root), Set.of("count")),
                "参照されているキーの変更では走査が必要");
    }

    @Test
    public void lookupReturnsNullForNodesOutsideTheIndex() {
        HtmlNode root = parse("<div><span>{{count}}</span></div>");
        TemplateDeps index = new TemplateDeps();
        index.index(root);

        // リスト行のクローンのように索引外のノードは null を返し、
        // 呼び出し側は枝刈りせず全走査する。
        HtmlNode clone = root.cloneNode();
        assertNull(index.lookup(clone), "索引外のノードは null であるべき");
        assertFalse(TemplateDeps.canSkip(null, Set.of("count")),
                "依存が不明なら飛ばしてはいけない");
    }

    @Test
    public void reindexingAfterRootSwapDiscardsOldEntries() {
        TemplateDeps index = new TemplateDeps();
        HtmlNode first = parse("<div><span>{{alpha}}</span></div>");
        assertTrue(index.index(first).contains("alpha"));

        HtmlNode second = parse("<div><span>{{beta}}</span></div>");
        Set<String> deps = index.index(second);
        assertTrue(deps.contains("beta"), deps.toString());
        assertFalse(deps.contains("alpha"), "差し替え後に古い依存が残っている: " + deps);
        assertNull(index.lookup(first), "古いルートの索引は破棄されるべき");
    }

    @Test
    public void indexIsStableAcrossRepeatedCallsWithSameRoot() {
        HtmlNode root = parse("<div><span>{{count}}</span></div>");
        TemplateDeps index = new TemplateDeps();

        Set<String> first = index.index(root);
        HtmlNode span = findByTag(root, "span");
        assertNotNull(index.lookup(span));

        Set<String> second = index.index(root);
        assertEquals(first, second);
        assertNotNull(index.lookup(span), "同じルートで再呼び出ししても索引は保たれる");
    }
}