package com.nishiyu.enderframelib.client.gui;

import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.common.UiFocus;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * キーボードフォーカス管理・Tab キー順次移動・十字キー空間ナビゲーション。
 *
 * <p>フォーカス可能の判定・Tab 順・空間移動の規則は core の {@link UiFocus}（窓と同じ）に任せ、
 * ここはセッションのフォーカス状態（{@link SessionContext}）への読み書きと、Minecraft の画面への橋渡しだけを持つ。
 */
public class WebFocusNavigator {

    public record FocusCandidate(HtmlNode node, DisplayItem element, int tabIndex, int domOrder) {}

    private final String sessionId;

    public WebFocusNavigator(String sessionId) {
        this.sessionId = sessionId;
    }

    public String focusedId() {
        SessionContext session = ClientWebManager.getSession(sessionId);
        return session != null ? session.focusedId() : "";
    }

    public void setFocused(String baseId) {
        setFocused(baseId, false);
    }

    public void setFocused(String baseId, boolean focusVisible) {
        String safeId = (baseId != null) ? baseId : "";
        SessionContext session = safeId.isEmpty()
                ? ClientWebManager.getSession(sessionId)
                : ClientWebManager.getOrCreateSession(sessionId);
        if (session != null) session.setFocusedElement(safeId, focusVisible);
    }

    /**
     * クリックされた節から祖先を辿り、最初にフォーカス可能な要素を返す。
     * フォーカス可能な要素が無ければ {@code null}（ブラウザと同様にフォーカスを解除する）。
     */
    public HtmlNode findFocusableTarget(HtmlNode start) {
        return UiFocus.nearestFocusable(start);
    }

    public static boolean isMouseFocusable(HtmlNode node) {
        return UiFocus.isMouseFocusable(node);
    }

    public List<FocusCandidate> collectFocusableCandidates() {
        SessionContext session = ClientWebManager.getSession(sessionId);
        if (session == null || session.rootNode() == null) return Collections.emptyList();
        List<FocusCandidate> out = new ArrayList<>();
        for (UiFocus.Candidate c : UiFocus.tabOrder(session)) {
            out.add(new FocusCandidate(c.node(), c.item(), c.tabIndex(), c.order()));
        }
        return out;
    }

    private HtmlNode currentFocusTarget(SessionContext session) {
        String id = focusedId();
        return id.isEmpty() ? null : session.findNode(id);
    }

    public boolean cycleSequentialFocus(Screen screen, boolean reverse, Consumer<FocusCandidate> applyCallback) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        if (session == null) return false;
        return moveTo(session, UiFocus.next(session, currentFocusTarget(session), reverse ? -1 : 1), applyCallback);
    }

    public boolean navigateSpatialFocus(Screen screen, int keyCode, Consumer<FocusCandidate> applyCallback) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        if (session == null) return false;
        return moveTo(session, UiFocus.spatial(session, currentFocusTarget(session), keyCode), applyCallback);
    }

    private boolean moveTo(SessionContext session, HtmlNode target, Consumer<FocusCandidate> applyCallback) {
        if (target == null) return false;
        setFocused(target.getId(), true);
        if (applyCallback != null) {
            FocusCandidate chosen = null;
            for (FocusCandidate c : collectFocusableCandidates()) {
                if (c.node() == target) {
                    chosen = c;
                    break;
                }
            }
            applyCallback.accept(chosen != null ? chosen : new FocusCandidate(target, null, 0, 0));
        }
        return true;
    }
}
