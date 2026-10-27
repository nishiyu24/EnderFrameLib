package com.nishiyu.enderframelib.client.gui;

import com.nishiyu.enderframelib.core.paint.model.*;

import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.function.Consumer;

/**
 * キーボードフォーカス管理・Tabキー順次移動・十字キー空間ナビゲーション。
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
        for (HtmlNode cur = start; cur != null; cur = cur.parent) {
            if (cur.attrs.containsKey("hidden") || cur.attrs.containsKey("disabled")) return null;
            String styleAttr = cur.attrs.getOrDefault("style", "").replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            if (styleAttr.contains("display:none")) return null;
            if (isMouseFocusable(cur)) return cur;
        }
        return null;
    }

    public static boolean isMouseFocusable(HtmlNode node) {
        if (node == null || node.attrs.containsKey("hidden") || node.attrs.containsKey("disabled")) return false;
        if (hasExplicitTabIndex(node)) return true;
        return isNaturallyFocusable(node);
    }

    private static boolean hasExplicitTabIndex(HtmlNode node) {
        if (!node.attrs.containsKey("tabindex")) return false;
        try {
            Integer.parseInt(node.attrs.get("tabindex").trim());
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean isNaturallyFocusable(HtmlNode node) {
        String tag = node.tag != null ? node.tag.toLowerCase(Locale.ROOT) : "";
        if ("input".equals(tag)) {
            return !"hidden".equalsIgnoreCase(node.attrs.getOrDefault("type", ""));
        }
        return "button".equals(tag) || "select".equals(tag)
                || "textarea".equals(tag) || "summary".equals(tag)
                || ("a".equals(tag) && node.attrs.containsKey("href"));
    }

    public List<FocusCandidate> collectFocusableCandidates() {
        SessionContext session = ClientWebManager.getSession(sessionId);
        HtmlNode vRoot = session != null ? session.rootNode() : null;
        List<DisplayItem> elements = session != null ? session.elements() : Collections.emptyList();
        if (vRoot == null || elements == null || elements.isEmpty()) return Collections.emptyList();

        Map<String, DisplayItem> boxByNodeId = new HashMap<>();
        for (DisplayItem el : elements) {
            String baseId = el.baseId();
            if (el.type() == ElementType.NODE) {
                boxByNodeId.put(el.id(), el);
            } else {
                boxByNodeId.putIfAbsent(baseId, el);
            }
        }

        List<FocusCandidate> list = new ArrayList<>();
        int[] orderCounter = new int[]{0};
        collectFocusablesRecursive(vRoot, boxByNodeId, list, orderCounter);

        list.sort((a, b) -> {
            boolean aPos = a.tabIndex() > 0;
            boolean bPos = b.tabIndex() > 0;
            if (aPos && bPos) {
                int cmp = Integer.compare(a.tabIndex(), b.tabIndex());
                if (cmp != 0) return cmp;
            } else if (aPos != bPos) {
                return aPos ? -1 : 1;
            }
            return Integer.compare(a.domOrder(), b.domOrder());
        });
        return list;
    }

    private void collectFocusablesRecursive(HtmlNode node, Map<String, DisplayItem> boxByNodeId, List<FocusCandidate> out, int[] orderCounter) {
        if (node == null || node.attrs.containsKey("hidden") || node.attrs.containsKey("disabled")) return;

        String styleAttr = node.attrs.getOrDefault("style", "").replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (styleAttr.contains("display:none")) return;

        int tabIndex = 0;
        boolean hasExplicitTabIndex = false;
        if (node.attrs.containsKey("tabindex")) {
            try {
                tabIndex = Integer.parseInt(node.attrs.get("tabindex").trim());
                hasExplicitTabIndex = true;
            } catch (NumberFormatException ignored) {}
        }

        String tag = node.tag != null ? node.tag.toLowerCase(Locale.ROOT) : "";
        boolean naturallyFocusable = "button".equals(tag) || "input".equals(tag) || "select".equals(tag)
                || "textarea".equals(tag) || "summary".equals(tag)
                || ("a".equals(tag) && (node.attrs.containsKey("href") || node.attrs.containsKey("onclick")))
                || node.attrs.containsKey("onclick");

        if (tabIndex >= 0 && (hasExplicitTabIndex || naturallyFocusable)) {
            DisplayItem el = boxByNodeId.get(node.getId());
            if (el != null && el.width() > 0 && el.height() > 0) {
                out.add(new FocusCandidate(node, el, tabIndex, orderCounter[0]++));
            }
        }

        boolean isClosedDetails = "details".equals(tag) && !node.attrs.containsKey("open");
        for (HtmlNode child : node.children) {
            if (isClosedDetails && !"summary".equalsIgnoreCase(child.tag)) continue;
            collectFocusablesRecursive(child, boxByNodeId, out, orderCounter);
        }
    }

    public boolean cycleSequentialFocus(Screen screen, boolean reverse, Consumer<FocusCandidate> applyCallback) {
        List<FocusCandidate> candidates = collectFocusableCandidates();
        if (candidates.isEmpty()) return false;

        int curIdx = -1;
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).node().getId().equals(focusedId())) {
                curIdx = i;
                break;
            }
        }

        int nextIdx;
        if (curIdx == -1) {
            nextIdx = reverse ? candidates.size() - 1 : 0;
        } else {
            nextIdx = (curIdx + (reverse ? -1 : 1) + candidates.size()) % candidates.size();
        }

        FocusCandidate chosen = candidates.get(nextIdx);
        setFocused(chosen.node().getId(), true);
        if (applyCallback != null) applyCallback.accept(chosen);
        return true;
    }

    public boolean navigateSpatialFocus(Screen screen, int keyCode, Consumer<FocusCandidate> applyCallback) {
        List<FocusCandidate> candidates = collectFocusableCandidates();
        if (candidates.isEmpty()) return false;

        FocusCandidate current = null;
        for (FocusCandidate c : candidates) {
            if (c.node().getId().equals(focusedId())) {
                current = c;
                break;
            }
        }
        if (current == null) {
            FocusCandidate first = candidates.getFirst();
            setFocused(first.node().getId(), true);
            if (applyCallback != null) applyCallback.accept(first);
            return true;
        }

        double curCx = current.element().x() + current.element().width() / 2.0;
        double curCy = current.element().y() + current.element().height() / 2.0;

        FocusCandidate best = null;
        double bestScore = Double.MAX_VALUE;

        for (FocusCandidate cand : candidates) {
            if (cand == current) continue;
            double cx = cand.element().x() + cand.element().width() / 2.0;
            double cy = cand.element().y() + cand.element().height() / 2.0;
            double dx = cx - curCx;
            double dy = cy - curCy;

            boolean inDirection = switch (keyCode) {
                case GLFW.GLFW_KEY_UP -> dy < -2;
                case GLFW.GLFW_KEY_DOWN -> dy > 2;
                case GLFW.GLFW_KEY_LEFT -> dx < -2;
                case GLFW.GLFW_KEY_RIGHT -> dx > 2;
                default -> false;
            };
            if (!inDirection) continue;

            double primaryDist = (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) ? Math.abs(dy) : Math.abs(dx);
            double orthoDist = (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) ? Math.abs(dx) : Math.abs(dy);
            double score = primaryDist + orthoDist * 2.5;
            if (score < bestScore) {
                bestScore = score;
                best = cand;
            }
        }

        if (best != null) {
            setFocused(best.node().getId(), true);
            if (applyCallback != null) applyCallback.accept(best);
            return true;
        }
        return false;
    }
}