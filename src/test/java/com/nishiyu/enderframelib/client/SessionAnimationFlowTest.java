package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.PaintBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * セッションの描画キュー（{@code flushInvalidation}）を通した、トランジションの実時間での進み方。
 * 動いている間はフレームごとに値が変わり、次のフレームが予約され続け、終わると目標値で止まる。
 */
public class SessionAnimationFlowTest {

    private static final String SESSION_ID = "session_animation_flow";

    @BeforeEach
    public void setup() {
        ClientWebManager.clearSession(SESSION_ID);
    }

    @AfterEach
    public void teardown() {
        ClientWebManager.clearSession(SESSION_ID);
    }

    private static float widthOf(SessionContext session, String id) {
        HtmlNode node = session.rootNode().getNodeById(id);
        assertNotNull(node);
        PaintBox box = find(session.boxRoot(), node);
        assertNotNull(box);
        return box.w();
    }

    private static PaintBox find(PaintBox b, HtmlNode node) {
        if (b == null || b.node == node) return b;
        for (PaintBox c : b.paintChildren()) {
            PaintBox r = find(c, node);
            if (r != null) return r;
        }
        return null;
    }

    @Test
    public void transitionRunsFrameByFrameAndSettles() throws InterruptedException {
        String html = "<style>#box{width:100px;height:20px;background-color:#345;transition:width 400ms linear}"
                + " #box.wide{width:300px}</style><div id=\"box\">x</div>";
        SessionContext session = ClientWebManager.getOrCreateSession(SESSION_ID);
        session.loadLocalHtml(html, 640, 480);
        session.flushInvalidation();
        assertEquals(100f, widthOf(session, "box"));

        session.rootNode().getNodeById("box").setAttribute("class", "wide");
        session.requestRestyle();
        session.flushInvalidation();
        float started = widthOf(session, "box");
        assertTrue(started >= 100f && started < 300f, "始まった直後は途中の値: " + started);

        float previous = started;
        boolean grew = false;
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline && widthOf(session, "box") < 300f) {
            assertTrue(ClientWebManager.hasPendingRenders(), "動いている間は次のフレームが予約されている");
            Thread.sleep(25);
            session.flushInvalidation();
            float w = widthOf(session, "box");
            assertTrue(w >= previous, "幅が戻った: " + previous + " → " + w);
            grew |= w > previous;
            previous = w;
        }
        assertTrue(grew, "フレームごとに値が進んでいない");
        assertEquals(300f, widthOf(session, "box"), "終わると目標値");

        session.flushInvalidation();
        assertEquals(300f, widthOf(session, "box"));
        assertFalse(session.interactionState().animator().isRunning(System.currentTimeMillis()));
    }
}
