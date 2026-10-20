package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.EnderFrameClientInit.EnderFrameClientAPIImpl;
import com.nishiyu.enderframelib.api.EnderFrameClientAPI;
import com.nishiyu.enderframelib.api.dom.Document;
import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.network.WebActionC2SPacket;
import com.nishiyu.enderframelib.core.event.CoreEvent;
import com.nishiyu.enderframelib.api.event.UIEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
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
 * R3: クライアント駆動 API (EnderFrameClientAPI) およびスケジューラ (rAF, setTimeout, setInterval)、
 * preventDefault によるサーバ転送抑止のテスト。
 */
public class ClientDrivenApiTest {

    private static final String SESSION_ID = "client_test_session";

    @BeforeEach
    public void setup() {
        ClientWebManager.clearSession(SESSION_ID);
        EnderFrameClientAPI.setInstance(EnderFrameClientAPIImpl.INSTANCE);
        ClientScheduler.clearAll();
    }

    @AfterEach
    public void teardown() {
        ClientWebManager.clearSession(SESSION_ID);
        ClientScheduler.clearAll();
    }

    @Test
    public void testClientDrivenDocumentCreationAndManipulation() {
        String html = "<div id='container'><h1 id='title'>Original Title</h1></div>";
        ClientWebManager.getOrCreateSession(SESSION_ID).loadLocalHtml(html, 800, 600);
        SessionContext session = ClientWebManager.getOrCreateSession(SESSION_ID);
        session.setServerManaged(false);

        Document doc = session.domDocument();
        assertNotNull(doc, "クライアント駆動の Document ハンドルが取得できること");
        assertFalse(session.isServerManaged(), "クライアント駆動セッションは serverManaged が false");

        // DOM API による直接操作
        Element title = doc.getElementById("title");
        assertNotNull(title);
        assertEquals("Original Title", title.getTextContent());

        title.setTextContent("Modified Client Title");
        assertEquals("Modified Client Title", title.getTextContent());

        // 新しい要素の生成と追加
        Element p = doc.createElement("p");
        p.setId("para1");
        p.setTextContent("Dynamically added paragraph");
        Element container = doc.getElementById("container");
        assertNotNull(container);
        container.appendChild(p);

        Element foundP = doc.getElementById("para1");
        assertNotNull(foundP);
        assertEquals("Dynamically added paragraph", foundP.getTextContent());
    }

    @Test
    public void testClientSchedulerRafAndTimers() throws InterruptedException {
        AtomicBoolean rafExecuted = new AtomicBoolean(false);
        int rafId = ClientScheduler.requestAnimationFrame(SESSION_ID, timestamp -> {
            rafExecuted.set(true);
        });
        assertTrue(rafId > 0);

        // フレーム前は未実行
        assertFalse(rafExecuted.get());
        ClientScheduler.onRenderFrame(100.0);
        assertTrue(rafExecuted.get(), "onRenderFrame で rAF コールバックが実行されること");

        // 1回実行されたら自動解除
        rafExecuted.set(false);
        ClientScheduler.onRenderFrame(150.0);
        assertFalse(rafExecuted.get(), "rAF は1回実行後に解除されること");

        // setTimeout
        AtomicBoolean timeoutExecuted = new AtomicBoolean(false);
        int timeoutId = ClientScheduler.setTimeout(SESSION_ID, () -> timeoutExecuted.set(true), 20);
        assertTrue(timeoutId > 0);

        ClientScheduler.onClientTick();
        assertFalse(timeoutExecuted.get(), "20ms 経過前は実行されない");

        Thread.sleep(30);
        ClientScheduler.onClientTick();
        assertTrue(timeoutExecuted.get(), "20ms 経過後に setTimeout コールバックが実行されること");

        // setInterval
        AtomicInteger intervalCount = new AtomicInteger(0);
        int intervalId = ClientScheduler.setInterval(SESSION_ID, intervalCount::incrementAndGet, 10);
        assertTrue(intervalId > 0);

        Thread.sleep(15);
        ClientScheduler.onClientTick();
        assertEquals(1, intervalCount.get());

        Thread.sleep(15);
        ClientScheduler.onClientTick();
        assertEquals(2, intervalCount.get());

        // キャンセル
        assertTrue(ClientScheduler.clearInterval(intervalId));
        Thread.sleep(15);
        ClientScheduler.onClientTick();
        assertEquals(2, intervalCount.get(), "キャンセル後は実行されない");
    }

    @Test
    public void testClearSessionDiscardsAllSchedulerTasks() {
        AtomicBoolean taskRun = new AtomicBoolean(false);
        ClientScheduler.requestAnimationFrame(SESSION_ID, t -> taskRun.set(true));
        ClientScheduler.setTimeout(SESSION_ID, () -> taskRun.set(true), 10);
        ClientScheduler.setInterval(SESSION_ID, () -> taskRun.set(true), 10);

        // セッション破棄
        ClientWebManager.clearSession(SESSION_ID);

        // フレーム・tick 実行
        ClientScheduler.onRenderFrame(500.0);
        try { Thread.sleep(20); } catch (InterruptedException ignored) {}
        ClientScheduler.onClientTick();

        assertFalse(taskRun.get(), "clearSession 後は紐づく全タスクが解除され実行されないこと（リーク防止）");
    }

    @Test
    public void testPreventDefaultStopsServerActionTransfer() {
        List<WebActionC2SPacket> sentPackets = new ArrayList<>();
        ClientScreenInteractionManager.setServerActionSender(sentPackets::add);
        try {
            String html = "<div id='root'><button id='btn' onclick='server:doSomething'>Click</button></div>";
            ClientWebManager.getOrCreateSession(SESSION_ID).loadLocalHtml(html, 800, 600);

            Document doc = ClientWebManager.getOrCreateSession(SESSION_ID).domDocument();
            Element btn = doc.getElementById("btn");
            assertNotNull(btn);

            AtomicBoolean clientListenerRan = new AtomicBoolean(false);
            // クライアントリスナーで preventDefault を呼ぶ
            btn.addEventListener("click", evt -> {
                clientListenerRan.set(true);
                evt.preventDefault();
            });

            UIEvents.Mouse cancelled = CoreEvent.click(10, 10, 0);
            btn.dispatchEvent(cancelled);

            assertTrue(clientListenerRan.get(), "クライアントの DOM リスナーが先に実行されること");
            assertTrue(cancelled.isDefaultPrevented(), "preventDefault() が反映されていること");
            assertTrue(sentPackets.isEmpty(), "preventDefault されたためサーバアクションは転送されないこと");

            // 取り消すリスナーが無ければ、同じ要素のクリックはサーバーへ転送される
            ClientWebManager.getOrCreateSession(SESSION_ID).loadLocalHtml(html, 800, 600);
            ClientWebManager.getOrCreateSession(SESSION_ID).domDocument().getElementById("btn").dispatchEvent(CoreEvent.click(10, 10, 0));
            assertEquals(1, sentPackets.size());
            assertEquals("server:doSomething", sentPackets.get(0).payload().getString("ActionName"));
        } finally {
            ClientScreenInteractionManager.setServerActionSender(null);
        }
    }
}