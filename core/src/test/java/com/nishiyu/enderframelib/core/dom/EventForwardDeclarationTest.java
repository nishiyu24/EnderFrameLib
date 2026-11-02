package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.dom.node.*;
import com.nishiyu.enderframelib.core.dom.parser.*;

import com.nishiyu.enderframelib.api.event.Event;
import com.nishiyu.enderframelib.core.event.CoreEvent;
import com.nishiyu.enderframelib.core.event.CoreEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
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
 * R3: サーバー転送の宣言（{@code data-forward}）と、所有権（{@code data-owner}）の解決、
 * そして「リスナーが先に走り、取り消されなければ転送する」配送の順序。
 */
public class EventForwardDeclarationTest {

    private static HtmlElement element(String tag, String id) {
        HtmlElement node = new HtmlElement(tag);
        node.setId(id);
        return node;
    }

    private static List<String> summarize(List<EventForwardDeclaration> declarations) {
        return declarations.stream()
                .map(d -> d.eventType() + ":" + d.payload() + "->" + d.action())
                .toList();
    }

    @Test
    @DisplayName("data-forward は 種別[:値] の列で、値の既定は DEFAULT、アクション名は data-forward-action")
    void parsesEventTypesPayloadsAndAction() {
        HtmlElement pad = element("div", "pad");
        pad.attrs.put("data-forward", "click wheel:point, MouseDown:target keydown:key");
        pad.attrs.put("data-forward-action", "pan:main");

        assertEquals(List.of(
                "click:DEFAULT->pan:main",
                "wheel:POINT->pan:main",
                "mousedown:TARGET->pan:main",
                "keydown:KEY->pan:main"), summarize(EventForwardDeclaration.declaredOn(pad)));
    }

    @Test
    @DisplayName("未知の送る値の種別と、宛先（アクション名）の無い宣言は捨てる")
    void ignoresUnknownPayloadsAndActionlessDeclarations() {
        HtmlElement button = element("button", "b");
        button.attrs.put("data-forward", "click:bogus dblclick");
        assertTrue(EventForwardDeclaration.declaredOn(button).isEmpty());

        button.attrs.put("data-forward-action", "go");
        assertEquals(List.of("dblclick:DEFAULT->go"), summarize(EventForwardDeclaration.declaredOn(button)));
    }

    @Test
    @DisplayName("アクション名の無い input/change の宣言は、入力コントロールの値の書き戻しとして残る")
    void valueControlsKeepActionlessInputDeclarations() {
        HtmlElement input = element("input", "i");
        input.attrs.put("data-forward", "input:value change:value click");

        assertEquals(List.of("input:VALUE->", "change:VALUE->"), summarize(EventForwardDeclaration.declaredOn(input)));
    }

    @Test
    @DisplayName("サーバー所有の入力コントロールだけが、値の書き戻しを暗黙に宣言する")
    void serverOwnedValueControlsDeclareValueSyncImplicitly() {
        HtmlDocument document = new HtmlDocument();
        HtmlElement host = element("div", "host");
        HtmlElement serverInput = element("input", "server-input");
        HtmlElement clientInput = element("input", "client-input");
        HtmlElement serverButton = element("button", "server-button");
        HtmlElement island = element("div", "island");
        island.attrs.put(Ownership.ATTRIBUTE, "client");
        island.appendChild(clientInput);
        document.appendChild(host);
        host.appendChild(serverInput);
        host.appendChild(serverButton);
        host.appendChild(island);

        document.setDefaultOwnership(Ownership.CLIENT);
        assertTrue(EventForwardDeclaration.declaredOn(serverInput).isEmpty(), "クライアント所有の文書では何も宣言しない");

        document.setDefaultOwnership(Ownership.SERVER);
        assertEquals(List.of("input:VALUE->", "change:VALUE->"), summarize(EventForwardDeclaration.declaredOn(serverInput)));
        assertTrue(EventForwardDeclaration.declaredOn(serverButton).isEmpty(), "入力コントロールでなければ暗黙の宣言は無い");
        assertTrue(EventForwardDeclaration.declaredOn(clientInput).isEmpty(), "クライアント所有の島の内側は暗黙の宣言を持たない");
    }

    @Test
    @DisplayName("明示した宣言は、同じ種別の暗黙の宣言に勝つ")
    void explicitDeclarationWinsOverImplicitOne() {
        HtmlDocument document = new HtmlDocument();
        document.setDefaultOwnership(Ownership.SERVER);
        HtmlElement input = element("input", "name");
        input.attrs.put("data-forward", "change:key");
        input.attrs.put("data-forward-action", "rename");
        document.appendChild(input);

        assertEquals(List.of("change:KEY->rename", "input:VALUE->"), summarize(EventForwardDeclaration.declaredOn(input)));
    }

    @Test
    @DisplayName("送出アクション名は data-forward-action と糖衣の on*=\"server:…\" の頭だけを数える")
    void declaredActionNamesCoverForwardAttributeAndServerSugar() {
        HtmlElement node = element("button", "b");
        node.attrs.put("data-forward-action", "buy:sword");
        node.attrs.put("onclick", "server:sell:3");
        node.attrs.put("onmouseover", "local_only");
        node.attrs.put("title", "server:not-an-handler");

        assertEquals(Set.of("buy", "sell"), EventForwardDeclaration.declaredActionNames(node));
    }

    @Test
    @DisplayName("所有者は祖先の data-owner が決め、無ければ文書の既定、文書の外の木はクライアント所有")
    void ownershipResolvesFromAncestorsThenDocumentDefault() {
        HtmlDocument document = new HtmlDocument();
        HtmlElement outer = element("div", "outer");
        HtmlElement island = element("div", "island");
        island.attrs.put(Ownership.ATTRIBUTE, " Client ");
        HtmlElement inner = element("span", "inner");
        HtmlElement serverAgain = element("em", "again");
        serverAgain.attrs.put(Ownership.ATTRIBUTE, "server");
        document.appendChild(outer);
        outer.appendChild(island);
        island.appendChild(inner);
        inner.appendChild(serverAgain);

        document.setDefaultOwnership(Ownership.SERVER);
        assertEquals(Ownership.SERVER, outer.ownership());
        assertEquals(Ownership.CLIENT, island.ownership());
        assertEquals(Ownership.CLIENT, inner.ownership(), "宣言は子孫へ継承される");
        assertEquals(Ownership.SERVER, serverAgain.ownership(), "内側の宣言が外側に勝つ");

        document.setDefaultOwnership(Ownership.CLIENT);
        assertEquals(Ownership.CLIENT, outer.ownership());

        assertEquals(Ownership.CLIENT, element("div", "detached").ownership());
        document.setDefaultOwnership(Ownership.SERVER);
        island.attrs.put(Ownership.ATTRIBUTE, "bogus");
        assertEquals(Ownership.SERVER, island.ownership(), "未知の値は宣言と見なさず継承に落ちる");
    }

    /** 文書の送り先に届いた順序つきの記録。 */
    private static final class RecordingSink implements HtmlDocument.InlineActionSink {
        private final List<String> log;

        RecordingSink(List<String> log) {
            this.log = log;
        }

        @Override
        public void run(HtmlNode node, Event event, String action) {
            log.add("action:" + node.getId() + "=" + action);
        }

        @Override
        public void forward(HtmlNode node, Event event, EventForwardDeclaration declaration) {
            log.add("forward:" + node.getId() + "=" + declaration.action());
        }
    }

    /** {@code parent > child} の木。どちらも click の転送を宣言し、child は on* も持つ。 */
    private static HtmlElement[] buildTree(HtmlDocument document) {
        HtmlElement parent = element("div", "parent");
        parent.attrs.put("data-forward", "click");
        parent.attrs.put("data-forward-action", "parentAction");
        HtmlElement child = element("button", "child");
        child.attrs.put("onclick", "childLocal");
        child.attrs.put("data-forward", "click");
        child.attrs.put("data-forward-action", "childAction");
        document.appendChild(parent);
        parent.appendChild(child);
        return new HtmlElement[]{parent, child};
    }

    @Test
    @DisplayName("リスナーが先に走り、その後に on* と転送が経路の順（対象 → 祖先）で渡る")
    void listenersRunBeforeOutboundActionsInPathOrder() {
        List<String> log = new ArrayList<>();
        HtmlDocument document = new HtmlDocument();
        document.setInlineActionSink(new RecordingSink(log));
        HtmlElement[] tree = buildTree(document);
        tree[1].addEventListener("click", (Event e) -> log.add("listener:child"));
        tree[0].addEventListener("click", (Event e) -> log.add("listener:parent"));

        assertTrue(tree[1].dispatchEvent(CoreEvent.click(1, 2, 0)));

        assertEquals(List.of(
                "listener:child", "listener:parent",
                "action:child=childLocal", "forward:child=childAction", "forward:parent=parentAction"), log);
    }

    @Test
    @DisplayName("どこかのリスナーが preventDefault すれば、on* も転送も渡らない")
    void preventDefaultSuppressesEveryOutboundAction() {
        List<String> log = new ArrayList<>();
        HtmlDocument document = new HtmlDocument();
        document.setInlineActionSink(new RecordingSink(log));
        HtmlElement[] tree = buildTree(document);
        tree[0].addEventListener("click", (Event e) -> e.preventDefault());

        assertFalse(tree[1].dispatchEvent(CoreEvent.click(1, 2, 0)));

        assertEquals(List.of(), log);
    }

    @Test
    @DisplayName("stopPropagation すれば祖先の宣言には届かない（対象自身の分は渡る）")
    void stopPropagationKeepsDeclarationsOfAncestorsQuiet() {
        List<String> log = new ArrayList<>();
        HtmlDocument document = new HtmlDocument();
        document.setInlineActionSink(new RecordingSink(log));
        HtmlElement[] tree = buildTree(document);
        tree[1].addEventListener("click", (Event e) -> e.stopPropagation());

        tree[1].dispatchEvent(CoreEvent.click(1, 2, 0));

        assertEquals(List.of("action:child=childLocal", "forward:child=childAction"), log);
    }

    @Test
    @DisplayName("バブリングしないイベントは、祖先の宣言には届かない")
    void nonBubblingEventsDoNotReachAncestorDeclarations() {
        List<String> log = new ArrayList<>();
        HtmlDocument document = new HtmlDocument();
        document.setInlineActionSink(new RecordingSink(log));
        HtmlElement[] tree = buildTree(document);
        tree[0].attrs.put("data-forward", "focus");
        tree[1].attrs.put("data-forward", "focus");

        tree[1].dispatchEvent(new CoreEvent.CoreFocusEvent("focus", false, false, null));

        assertEquals(List.of("forward:child=childAction"), log);
    }
}
