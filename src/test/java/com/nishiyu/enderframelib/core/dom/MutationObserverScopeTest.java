package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.api.dom.MutationObserver;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MutationObserver が Document 単位に閉じ、static 状態を持たないことの検証。 */
public class MutationObserverScopeTest {

    private static MutationObserver.Init attrs() {
        return MutationObserver.Init.builder().attributes(true).subtree(true).build();
    }

    @Test
    public void observerOnlySeesItsOwnDocument() {
        HtmlDocument a = new HtmlDocument();
        HtmlDocument b = new HtmlDocument();
        HtmlElement ea = (HtmlElement) a.createElement("div");
        HtmlElement eb = (HtmlElement) b.createElement("div");
        a.appendChild(ea);
        b.appendChild(eb);

        MutationObserver obsA = MutationObserver.create((r, o) -> {});
        obsA.observe(a, attrs());

        eb.setAttribute("x", "1");
        assertTrue(obsA.takeRecords().isEmpty(), "別 Document の変異は届かないこと");
        ea.setAttribute("x", "1");
        assertEquals(1, obsA.takeRecords().size());
    }

    @Test
    public void disconnectAndClearAllReleaseDocumentRegistration() {
        HtmlDocument doc = new HtmlDocument();
        HtmlElement e = (HtmlElement) doc.createElement("div");
        doc.appendChild(e);
        List<MutationObserver.Record> got = new ArrayList<>();
        MutationObserver obs = MutationObserver.create((r, o) -> got.addAll(r));
        obs.observe(doc, attrs());
        assertEquals(1, doc.mutationObservers().size());

        obs.disconnect();
        assertTrue(doc.mutationObservers().isEmpty());

        MutationObserver obs2 = MutationObserver.create((r, o) -> {});
        obs2.observe(doc, attrs());
        MutationObserverManager.clearAll(doc);
        assertTrue(doc.mutationObservers().isEmpty());
        e.setAttribute("y", "1");
        MutationObserverManager.flush(doc);
        assertTrue(got.isEmpty());
    }

    @Test
    public void unobservedObserverIsNotRegisteredAnywhere() {
        HtmlDocument doc = new HtmlDocument();
        MutationObserver.create((r, o) -> {});
        assertTrue(doc.mutationObservers().isEmpty());
    }
}
