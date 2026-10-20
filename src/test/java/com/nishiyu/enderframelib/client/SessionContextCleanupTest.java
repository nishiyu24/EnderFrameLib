package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.gui.InvisibleInputScreen.WebTextInputHost;
import com.nishiyu.enderframelib.client.renderer.Canvas2DContext;
import com.nishiyu.enderframelib.client.renderer.CssAnimator;
import com.nishiyu.enderframelib.client.renderer.WebHudRenderer;
import com.nishiyu.enderframelib.client.renderer.WebSceneContext;
import com.nishiyu.enderframelib.core.css.Selector.ElementState;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.layout.AnimationSpec;
import com.nishiyu.enderframelib.core.layout.DisplayItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1: セッションごとの状態は {@link SessionContext} が所有し、{@link SessionContext#close()} で
 * すべて片付くことを守る。片付け漏れはそのままセッションのリークになる。
 */
public class SessionContextCleanupTest {

    @Test
    public void testCloseClearsAllSessionOwnedState() {
        String sessionId = "test_session_context_close";
        SessionContext session = new SessionContext(sessionId);

        HtmlNode target = new HtmlNode("button");
        target.attrs.put("id", "target");
        session.interactionState().setHoveredNode(target);
        session.interactionState().setActiveNode(target);
        session.interactionState().setFocusedNode(target, true);

        session.setActiveInputId("target");
        session.inputState().draggingId = "target";
        session.inputState().lastRatio = 0.5f;
        session.inputState().lastRangeText = "range";
        session.inputState().lastSentX = 1.0;
        session.inputState().lastSentY = 2.0;
        session.inputState().lastEventTime = 3L;
        session.inputState().hoverX = 4.0;
        session.inputState().hoverY = 5.0;
        session.inputState().pressedButton = 1;
        session.inputState().lastSubmittedId = "target";
        session.inputState().lastSubmittedText = "submitted";
        session.inputState().lastSubmitTime = 6L;
        session.inputState().localInputCache.put("target", "cached");
        session.inputState().lastClickNode = target;
        session.inputState().lastClickTime = 7L;

        DisplayItem item = new DisplayItem("target", "target", DisplayItem.ElementType.NODE,
                0, 0, 10, 10, null, 0, 0, Map.of(), 0, 1f, 0, 0, 1f, 1f, 0, 0,
                AnimationSpec.NONE, DisplayItem.ImageFitMode.FILL, DisplayItem.RenderMeta.EMPTY);
        session.setRenderCache(List.of(item));
        session.cssAnimations().put("target", new CssAnimator.AnimState(item));
        session.canvases().put("target", new Canvas2DContext());
        session.scenes().put("target", new WebSceneContext());

        session.inputScrollOffsets().put("target", 1);
        session.textareaCursorIndex().put("target", 2);
        session.textareaScrollRow().put("target", 3);
        session.setActiveTextInputHost(new WebTextInputHost(sessionId, null, null));

        session.env().put("key", "value");
        session.setEnvLastUpdate(42L);
        session.setEnvUsedKeys(Set.of("key"));
        session.setEnvApplied(Map.of("key", "value"));

        session.subscriptions().add("channel");
        session.dataBindings().add(new ClientPubSubManager.DataBinding("target", "channel", "path", "textContent"));
        session.virtualListBindings().add(
                new ClientPubSubManager.VirtualListBinding("target", "channel", null, 10, 20));

        session.setHudConfig(new WebHudRenderer.HudTemplateConfig());
        session.setLastHudData(Map.of("key", "value"));
        session.setTrackerEntry(new WebHudRenderer.TrackerEntry(new WebHudRenderer.TrackerTemplateConfig(), null));

        session.rafCallbacks().put(1, new ClientScheduler.RafCallback(1, sessionId, timestamp -> {}));
        session.timers().put(2, new ClientScheduler.TimerTask(2, sessionId, () -> {}, 0, 0, false));

        session.markInvalidation(SessionContext.InvalidationLevel.RELAYOUT);

        session.close();

        assertNull(session.interactionState().hoveredNode(), "ホバー中のノードが残っている");
        assertNull(session.interactionState().activeNode(), "押下中のノードが残っている");
        assertNull(session.interactionState().focusedNode(), "フォーカス中のノードが残っている");
        assertEquals(ElementState.NONE, session.interactionState().stateOf(target), "対話状態フラグが残っている");
        assertEquals("", session.focusedId());
        assertEquals("", session.activePressedId());
        assertEquals("", session.activeInputId());

        assertEquals("", session.inputState().draggingId);
        assertEquals(-1.0f, session.inputState().lastRatio);
        assertEquals("", session.inputState().lastRangeText);
        assertEquals(-1.0, session.inputState().lastSentX);
        assertEquals(-1.0, session.inputState().lastSentY);
        assertEquals(0L, session.inputState().lastEventTime);
        assertEquals(-1.0, session.inputState().hoverX);
        assertEquals(-1.0, session.inputState().hoverY);
        assertEquals(0, session.inputState().pressedButton);
        assertEquals("", session.inputState().lastSubmittedId);
        assertEquals("", session.inputState().lastSubmittedText);
        assertEquals(0L, session.inputState().lastSubmitTime);
        assertTrue(session.inputState().localInputCache.isEmpty());
        assertNull(session.inputState().lastClickNode);
        assertEquals(0L, session.inputState().lastClickTime);

        assertTrue(session.renderCache().isEmpty());
        assertEquals(SessionContext.InvalidationLevel.NONE, session.invalidationLevel());
        assertTrue(session.cssAnimations().isEmpty());
        assertTrue(session.canvases().isEmpty());
        assertTrue(session.scenes().isEmpty());
        assertTrue(session.inputScrollOffsets().isEmpty());
        assertTrue(session.textareaCursorIndex().isEmpty());
        assertTrue(session.textareaScrollRow().isEmpty());
        assertNull(session.activeTextInputHost());

        assertTrue(session.env().isEmpty());
        assertEquals(0L, session.envLastUpdate());
        assertNull(session.envUsedKeys());
        assertNull(session.envApplied());

        assertTrue(session.subscriptions().isEmpty());
        assertTrue(session.dataBindings().isEmpty());
        assertTrue(session.virtualListBindings().isEmpty());

        assertNull(session.hudConfig());
        assertNull(session.lastHudData());
        assertNull(session.trackerEntry());

        assertTrue(session.rafCallbacks().isEmpty());
        assertTrue(session.timers().isEmpty());
    }
}
