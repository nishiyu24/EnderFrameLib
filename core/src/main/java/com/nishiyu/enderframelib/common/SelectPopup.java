package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code <select>} のポップアップ（選択肢の一覧）。Minecraft の {@code Screen} に依存せず、選択肢の取り出し・
 * ハイライトの移動・当たり判定・重ねて描く表示項目の生成までを持つ。値の反映（{@code input} / {@code change} の発火）は
 * 呼び出し側（{@link StandaloneView}）が行う。
 */
public final class SelectPopup {
    /** 1 行の高さ（CSS px）。 */
    public static final int ROW_HEIGHT = 18;
    private static final int MIN_WIDTH = 90;
    private static final int FONT_PX = 13;
    private static final int BORDER = 0xFF89B4FA, BACKGROUND = 0xFF181825, HIGHLIGHT = 0xFF45475A,
            CURRENT = 0xFF313244, TEXT = 0xFFCDD6F4, TEXT_ACTIVE = 0xFF89B4FA;

    public record Option(String value, String label, boolean disabled) {}

    private HtmlNode select;
    private final List<Option> options = new ArrayList<>();
    private int highlighted = -1;
    private int x, y, width;

    public boolean isOpen() {
        return select != null;
    }

    public HtmlNode select() {
        return select;
    }

    public List<Option> options() {
        return options;
    }

    public int highlightedIndex() {
        return highlighted;
    }

    /** ハイライトを直接指定する（範囲外・無効な項目は無視）。 */
    public void setHighlightedIndex(int index) {
        if (index >= 0 && index < options.size() && !options.get(index).disabled()) highlighted = index;
    }

    /** ポップアップの左上と幅（CSS px。{@link #open} のあと）。 */
    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int width() {
        return width;
    }

    /**
     * {@code box}（select の表示項目）の直下に開く。下に収まらず上に余裕があれば上に開く。選択肢が無ければ開かない。
     *
     * @return 開いたか
     */
    public boolean open(HtmlNode node, DisplayItem box, int viewportHeight) {
        close();
        if (node == null || box == null || node.attrs.containsKey("disabled")) return false;
        collect(node, options);
        if (options.isEmpty()) return false;
        select = node;
        width = Math.max(box.width(), MIN_WIDTH);
        x = box.x();
        int total = totalHeight();
        int below = box.y() + box.height() + 2;
        y = below + total > viewportHeight && box.y() - 2 - total >= 0 ? box.y() - 2 - total : below;
        String current = node.attrs.getOrDefault("value", "");
        highlighted = indexOfValue(current);
        if (highlighted < 0) highlighted = firstEnabled();
        return true;
    }

    public void close() {
        select = null;
        options.clear();
        highlighted = -1;
    }

    private static void collect(HtmlNode select, List<Option> out) {
        for (HtmlNode child : select.children) {
            if ("option".equalsIgnoreCase(child.tag)) {
                addOption(child, out, false);
            } else if ("optgroup".equalsIgnoreCase(child.tag)) {
                boolean groupOff = child.attrs.containsKey("disabled");
                for (HtmlNode o : child.children) if ("option".equalsIgnoreCase(o.tag)) addOption(o, out, groupOff);
            }
        }
    }

    private static void addOption(HtmlNode option, List<Option> out, boolean groupDisabled) {
        String label = option.getTextContent().trim();
        String value = option.attrs.getOrDefault("value", label);
        out.add(new Option(value, label.isEmpty() ? value : label, groupDisabled || option.attrs.containsKey("disabled")));
    }

    private int indexOfValue(String value) {
        for (int i = 0; i < options.size(); i++) if (options.get(i).value().equals(value)) return i;
        return -1;
    }

    private int firstEnabled() {
        for (int i = 0; i < options.size(); i++) if (!options.get(i).disabled()) return i;
        return -1;
    }

    /** ハイライトを {@code delta} だけ動かす（無効な項目は飛ばし、端は反対側へ回る）。 */
    public void moveHighlight(int delta) {
        int n = options.size();
        if (n == 0) return;
        int i = highlighted;
        for (int tries = 0; tries < n; tries++) {
            i = i < 0 ? (delta > 0 ? 0 : n - 1) : Math.floorMod(i + delta, n);
            if (!options.get(i).disabled()) {
                highlighted = i;
                return;
            }
        }
    }

    public int totalHeight() {
        return options.size() * ROW_HEIGHT + 2;
    }

    public boolean contains(double px, double py) {
        return isOpen() && px >= x && px <= x + width && py >= y && py <= y + totalHeight();
    }

    /** 点の下の選択肢の番号。ポップアップの外や枠なら -1。 */
    public int indexAt(double px, double py) {
        if (!contains(px, py)) return -1;
        int idx = (int) ((py - y - 1) / ROW_HEIGHT);
        return idx >= 0 && idx < options.size() ? idx : -1;
    }

    /** ポインタが乗った行をハイライトにする（無効な項目には乗らない）。 */
    public void hover(double px, double py) {
        int idx = indexAt(px, py);
        if (idx >= 0 && !options.get(idx).disabled()) highlighted = idx;
    }

    /** ハイライト中の選択肢の値。無ければ null。 */
    public String highlightedValue() {
        return highlighted >= 0 && highlighted < options.size() && !options.get(highlighted).disabled()
                ? options.get(highlighted).value() : null;
    }

    /** 点の下の選択できる値。無ければ null。 */
    public String valueAt(double px, double py) {
        int idx = indexAt(px, py);
        return idx >= 0 && !options.get(idx).disabled() ? options.get(idx).value() : null;
    }

    /** 画面の最前面に重ねる表示項目（枠・背景・行・文字）。閉じていれば空。 */
    public List<DisplayItem> overlay() {
        if (!isOpen()) return List.of();
        String base = select.key();
        String current = select.attrs.getOrDefault("value", "");
        List<DisplayItem> out = new ArrayList<>();
        int total = totalHeight();
        out.add(rect(base, "_pop_border", x, y, width, total, BORDER));
        out.add(rect(base, "_pop_bg", x + 1, y + 1, width - 2, total - 2, BACKGROUND));
        RenderMeta meta = new RenderMeta(null, null, null, FONT_PX, 0f, 0f, 0f, 0f, 0, false);
        for (int i = 0; i < options.size(); i++) {
            Option o = options.get(i);
            int top = y + 1 + i * ROW_HEIGHT;
            boolean active = i == highlighted;
            boolean selected = o.value().equals(current);
            if (active) out.add(rect(base, "_pop_hl" + i, x + 1, top, width - 2, ROW_HEIGHT, HIGHLIGHT));
            else if (selected) out.add(rect(base, "_pop_cur" + i, x + 1, top, width - 2, ROW_HEIGHT, CURRENT));
            int color = o.disabled() ? 0xFF6C7086 : (active || selected) ? TEXT_ACTIVE : TEXT;
            out.add(new DisplayItem(base + "_pop_t" + i, base, ElementType.TEXT, x + 6, top, width - 12, ROW_HEIGHT,
                    o.label(), color, 0, Map.of(), 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, ImageFitMode.FILL, meta));
        }
        return out;
    }

    private static DisplayItem rect(String base, String part, int x, int y, int w, int h, int argb) {
        return new DisplayItem(base + part, base, ElementType.RECT, x, y, w, h, "", 0, argb, Map.of(), 0, 1f,
                0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, ImageFitMode.FILL, null);
    }
}
