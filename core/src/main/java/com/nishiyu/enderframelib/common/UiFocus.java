package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.tree.PaintBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * キーボードフォーカスの移動（Minecraft 非依存）。{@code Tab} 順（{@code tabindex} の正の値が先、続いて 0 と自然に
 * フォーカスできる要素を文書順）と、十字キーの空間移動。ブラウザのフォーカス可能判定もここに置く。
 */
public final class UiFocus {
    private UiFocus() {}

    /** フォーカス移動の候補。{@code item} は位置の取れた表示項目（画面外で項目が無ければ null）。 */
    public record Candidate(HtmlNode node, DisplayItem item, int tabIndex, int order) {}

    /** 節が不活性（{@code inert} 属性の配下、またはモーダル {@code <dialog>} の外側）か。 */
    public static boolean isInert(HtmlNode node) {
        if (node == null) return false;
        HtmlNode root = node;
        while (root.parent != null) root = root.parent;
        if (root instanceof HtmlDocument doc) return doc.interactionState().isInert(node);
        for (HtmlNode cur = node; cur != null; cur = cur.parent) {
            if (cur.attrs != null && cur.attrs.containsKey("inert")) return true;
        }
        return false;
    }

    /** クリックでフォーカスできるか（{@code tabindex} が負でもクリックならできる）。 */
    public static boolean isMouseFocusable(HtmlNode node) {
        if (node == null || node.attrs.containsKey("hidden") || node.attrs.containsKey("disabled")
                || isInert(node)) return false;
        return explicitTabIndex(node) != null || isNaturallyFocusable(node);
    }

    /** クリックされた節から祖先を辿り、最初にフォーカスできる要素。無ければ null（フォーカスを外す）。 */
    public static HtmlNode nearestFocusable(HtmlNode start) {
        for (HtmlNode n = start; n != null; n = n.parent) {
            if (n.attrs.containsKey("hidden") || n.attrs.containsKey("disabled") || isInert(n)) return null;
            if (isMouseFocusable(n)) return n;
        }
        return null;
    }

    private static Integer explicitTabIndex(HtmlNode node) {
        String raw = node.attrs.get("tabindex");
        if (raw == null) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isNaturallyFocusable(HtmlNode node) {
        return com.nishiyu.enderframelib.core.dom.node.ElementTraits.isNaturallyFocusable(node);
    }

    /** {@code Tab} で巡る順の候補。箱の無い（{@code display:none}）要素・無効・{@code hidden}・負の {@code tabindex} は含めない。 */
    public static List<Candidate> tabOrder(UiView ui) {
        Map<String, DisplayItem> items = itemsByNode(ui.renderCache());
        List<Candidate> out = new ArrayList<>();
        collect(ui, ui.boxRoot(), ui.rootNode(), items, out, new int[]{0});
        out.sort((a, b) -> {
            boolean aPos = a.tabIndex() > 0, bPos = b.tabIndex() > 0;
            if (aPos && bPos) {
                int c = Integer.compare(a.tabIndex(), b.tabIndex());
                if (c != 0) return c;
            } else if (aPos != bPos) {
                return aPos ? -1 : 1;
            }
            return Integer.compare(a.order(), b.order());
        });
        return out;
    }

    private static void collect(UiView ui, PaintBox layout, HtmlNode node, Map<String, DisplayItem> items,
                                List<Candidate> out, int[] order) {
        if (node == null || node.attrs.containsKey("hidden") || node.attrs.containsKey("inert")) return;
        PaintBox box = findBox(layout, node);
        // display:none の部分木は箱があっても描かれない。ここから先へは降りない
        if (box != null && box.style() != null && box.style().typed().facts.displayNone) return;
        Integer tab = explicitTabIndex(node);
        boolean focusable = !node.attrs.containsKey("disabled") && !ui.interactionState().isInert(node)
                && (tab != null || isNaturallyFocusable(node));
        if (box != null && focusable && (tab == null || tab >= 0) && !node.key().isEmpty()) {
            out.add(new Candidate(node, items.get(node.key()), tab != null ? tab : 0, order[0]++));
        }
        boolean open = node.attrs.containsKey("open");
        for (HtmlNode child : node.children) {
            if (com.nishiyu.enderframelib.core.dom.node.ElementTraits.isHiddenByClosedDetails(node, open, child)) continue;
            collect(ui, layout, child, items, out, order);
        }
    }

    /** {@code current} から {@code step}（+1 / -1）だけ {@code Tab} 順に進んだ要素。先頭・末尾は反対側へ回る。無ければ null。 */
    public static HtmlNode next(UiView ui, HtmlNode current, int step) {
        List<Candidate> order = tabOrder(ui);
        if (order.isEmpty()) return null;
        int at = -1;
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).node() == current) {
                at = i;
                break;
            }
        }
        int next = at < 0 ? (step > 0 ? 0 : order.size() - 1) : Math.floorMod(at + step, order.size());
        return order.get(next).node();
    }

    /**
     * 十字キー（GLFW のキーコード）による空間移動。向きの先にあり、主軸の距離 + 副軸の距離 × 2.5 が最小の候補。
     * 何も選ばれていなければ最初の候補。向きの先に候補が無ければ null。
     */
    public static HtmlNode spatial(UiView ui, HtmlNode current, int key) {
        List<Candidate> all = tabOrder(ui);
        List<Candidate> cands = new ArrayList<>();
        for (Candidate c : all) if (c.item() != null) cands.add(c);
        if (cands.isEmpty()) return null;
        Candidate cur = null;
        for (Candidate c : cands) if (c.node() == current) cur = c;
        if (cur == null) return cands.get(0).node();

        double cx = centerX(cur.item()), cy = centerY(cur.item());
        boolean vertical = key == Keys.UP || key == Keys.DOWN;
        Candidate best = null;
        double bestScore = Double.MAX_VALUE;
        for (Candidate c : cands) {
            if (c == cur) continue;
            double dx = centerX(c.item()) - cx, dy = centerY(c.item()) - cy;
            boolean ahead = switch (key) {
                case Keys.UP -> dy < -2;
                case Keys.DOWN -> dy > 2;
                case Keys.LEFT -> dx < -2;
                case Keys.RIGHT -> dx > 2;
                default -> false;
            };
            if (!ahead) continue;
            double score = (vertical ? Math.abs(dy) : Math.abs(dx)) + (vertical ? Math.abs(dx) : Math.abs(dy)) * 2.5;
            if (score < bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best != null ? best.node() : null;
    }

    private static double centerX(DisplayItem it) {
        return it.x() + it.width() / 2.0;
    }

    private static double centerY(DisplayItem it) {
        return it.y() + it.height() / 2.0;
    }

    /** 節ごとに位置を代表する項目（節自身の箱があればそれ、無ければ最初の部品）。絶対位置に解決して返す。 */
    private static Map<String, DisplayItem> itemsByNode(List<DisplayItem> items) {
        Map<String, DisplayItem> byId = new HashMap<>();
        Map<String, DisplayItem> map = new HashMap<>();
        if (items == null) return map;
        for (DisplayItem it : items) byId.put(it.id(), it);
        for (DisplayItem it : items) {
            if (it.width() <= 0 || it.height() <= 0) continue;
            if (it.type() == ElementType.NODE && it.id().equals(it.baseId())) map.put(it.baseId(), it);
            else map.putIfAbsent(it.baseId(), it);
        }
        map.replaceAll((k, v) -> DisplayItem.resolveAbsolute(byId, v));
        return map;
    }

    /** レイアウトの結果の木（旧経路の箱の木か、新経路の描画用の木の見え方）から節の箱を探す（無名の箱は除く）。 */
    public static PaintBox findBox(PaintBox from, HtmlNode node) {
        if (from == null) return null;
        if (from.node == node && !from.isAnonymous) return from;
        for (PaintBox child : from.paintChildren()) {
            PaintBox hit = findBox(child, node);
            if (hit != null) return hit;
        }
        return null;
    }
}
