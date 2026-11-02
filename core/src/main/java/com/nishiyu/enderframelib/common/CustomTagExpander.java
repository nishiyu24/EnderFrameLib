package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser.NodeId;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;

import java.util.List;

/**
 * {@code <mc-slot>} / {@code <mc-slot-grid>} / {@code <mc-inventory>} / {@code <mc-bar>} /
 * {@code <mc-energy>} / {@code <mc-tank>} を
 * 素の HTML 要素に展開するマクロ。
 *
 * 以前は HTML 文字列に対する 4 つの正規表現置換（約 114 行）で、パースより前に行われていた。
 * そのため属性値の中の {@code >} や入れ子で壊れ、生成した HTML を再度パースし直していた。
 * 現在はパース後の DOM に対して部分木を差し替える。
 */
public final class CustomTagExpander {

    /** マクロが入れ子を生むことはないが、万一の循環で無限に展開しないための上限。 */
    private static final int MAX_PASSES = 4;

    private CustomTagExpander() {}

    /** 文書全体のカスタムタグを展開する。 */
    public static void expand(HtmlNode root) {
        if (root == null) return;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            if (!expandOnce(root)) return;
        }
    }

    /** 展開が 1 つでも起きたら true。 */
    private static boolean expandOnce(HtmlNode parent) {
        boolean expanded = false;

        for (int i = 0; i < parent.children.size(); i++) {
            HtmlNode child = parent.children.get(i);
            if (child.nodeType != NodeType.ELEMENT) continue;

            List<HtmlNode> replacement = switch (child.tag.toLowerCase()) {
                case "mc-slot" -> List.of(buildSlot(child));
                case "mc-slot-grid" -> List.of(buildSlotGrid(child));
                case "mc-inventory" -> List.of(buildInventory(child));
                case "mc-bar" -> List.of(buildBar(child, "#89b4fa", "#181825", ""));
                case "mc-energy" -> List.of(buildBar(child, "#d62828", "#2b0a0a", unitLabel(child, "FE")));
                case "mc-tank" -> List.of(buildBar(child, fluidColor(attr(child, "fluid", "")), "#10151f", unitLabel(child, "mB")));
                default -> null;
            };

            if (replacement != null) {
                parent.children.remove(i);
                for (int k = 0; k < replacement.size(); k++) {
                    HtmlNode node = replacement.get(k);
                    node.parent = parent;
                    parent.children.add(i + k, node);
                }
                i += replacement.size() - 1;
                expanded = true;
            } else {
                expanded |= expandOnce(child);
            }
        }
        return expanded;
    }

    // ==========================================
    // 各マクロ
    // ==========================================

    /** {@code <mc-slot index="3">} → スロット 1 つ。 */
    private static HtmlNode buildSlot(HtmlNode source) {
        String index = attr(source, "index", attr(source, "slot", "0"));
        HtmlNode slot = slotDiv(index, attr(source, "class", ""), attr(source, "style", ""));
        String id = attr(source, "id", "");
        if (!id.isEmpty()) slot.setId(id);
        return slot;
    }

    /** {@code <mc-slot-grid cols rows gap start>} → 行ごとの flex コンテナ。 */
    private static HtmlNode buildSlotGrid(HtmlNode source) {
        int start = intAttr(source, "start", 0, 0);
        int cols = intAttr(source, "cols", 3, 1);
        int rows = intAttr(source, "rows", 3, 1);
        int gap = intAttr(source, "gap", 0, 0);

        HtmlNode container = element("div");
        container.attrs.put("style", "display:flex;flex-direction:column;gap:" + gap + "px;" + attr(source, "style", ""));

        int index = start;
        for (int r = 0; r < rows; r++) {
            HtmlNode row = element("div");
            row.attrs.put("style", "display:flex;flex-direction:row;gap:" + gap + "px;");
            for (int c = 0; c < cols; c++) {
                appendChild(row, slotDiv(String.valueOf(index++), "", ""));
            }
            appendChild(container, row);
        }
        return container;
    }

    /** {@code <mc-inventory>} → 3×9 のメイン領域 ＋ 9 スロットのホットバー。 */
    private static HtmlNode buildInventory(HtmlNode source) {
        int start = intAttr(source, "start", 0, Integer.MIN_VALUE);
        boolean hotbarFirst = "true".equalsIgnoreCase(attr(source, "hotbar-first", "false"));

        int mainStart = hotbarFirst ? (start + 9) : start;
        int hotbarStart = hotbarFirst ? start : (start + 27);
        String hotbarAttr = attr(source, "hotbar", "");
        if (!hotbarAttr.isEmpty()) {
            try {
                hotbarStart = Integer.parseInt(hotbarAttr.trim());
            } catch (NumberFormatException ignored) {
            }
        }

        HtmlNode container = element("div");
        container.attrs.put("class", "mc-inventory");
        container.attrs.put("style", "display:flex;flex-direction:column;gap:4px;" + attr(source, "style", ""));

        HtmlNode main = element("div");
        main.attrs.put("style", "display:flex;flex-direction:column;gap:0px;");
        int index = mainStart;
        for (int r = 0; r < 3; r++) {
            HtmlNode row = inventoryRow();
            for (int c = 0; c < 9; c++) {
                appendChild(row, slotDiv(String.valueOf(index++), "", ""));
            }
            appendChild(main, row);
        }
        appendChild(container, main);

        HtmlNode hotbar = inventoryRow();
        for (int c = 0; c < 9; c++) {
            appendChild(hotbar, slotDiv(String.valueOf(hotbarStart + c), "", ""));
        }
        appendChild(container, hotbar);

        return container;
    }

    /** {@code <mc-bar value min max color bg label>} → progress ＋ 中央ラベル。 */
    private static HtmlNode buildBar(HtmlNode source, String defaultColor, String defaultBg, String defaultLabel) {
        String value = attr(source, "value", "0");
        String min = attr(source, "min", "0");
        String max = attr(source, "max", "100");
        String color = attr(source, "color", defaultColor);
        String bg = attr(source, "bg", defaultBg);
        String label = attr(source, "label", defaultLabel);
        if ("none".equalsIgnoreCase(label)) label = "";

        HtmlNode bar = element("div");
        bar.attrs.put("class", "mc-bar");
        bar.attrs.put("style", "position:relative;overflow:hidden;background-color:" + bg + ";" + attr(source, "style", ""));

        HtmlNode progress = element("progress");
        progress.attrs.put("value", value);
        progress.attrs.put("min", min);
        progress.attrs.put("max", max);
        progress.attrs.put("style",
                "width:100%;height:100%;background-color:transparent;color:" + color + ";border-radius:0;");
        appendChild(bar, progress);

        if (!label.isEmpty()) {
            HtmlNode span = element("span");
            span.attrs.put("style", "position:absolute;left:0;top:0;width:100%;height:100%;"
                    + "text-align:center;color:#ffffff;text-shadow:true;font-size:0.75rem;");
            HtmlNode text = new HtmlText(label);
            text.setId(NodeId.generateTextId());
            appendChild(span, text);
            appendChild(bar, span);
        }
        return bar;
    }

    /**
     * {@code <mc-energy>} / {@code <mc-tank>} の既定ラベル「value / max 単位」。値が {@code {{key}}} なら、
     * ラベルの文字列補間でそのまま状態に結び付く。{@code label="none"} でラベルを消す。
     */
    private static String unitLabel(HtmlNode source, String unit) {
        String label = attr(source, "label", "");
        if ("none".equalsIgnoreCase(label)) return "";
        if (!label.isEmpty()) return label;
        return attr(source, "value", "0") + " / " + attr(source, "max", "100") + " " + unit;
    }

    /** 流体の ID（{@code minecraft:water} や {@code water}）から色。分からなければ水色がかった既定色。 */
    private static String fluidColor(String fluid) {
        String name = fluid.contains(":") ? fluid.substring(fluid.indexOf(':') + 1) : fluid;
        return switch (name.toLowerCase()) {
            case "water" -> "#3f76e4";
            case "lava" -> "#ff7a00";
            case "milk" -> "#f2f2f2";
            case "honey" -> "#e8a317";
            default -> "#6aa9c9";
        };
    }

    // ==========================================
    // 組み立ての補助
    // ==========================================

    private static HtmlNode slotDiv(String index, String extraClass, String style) {
        HtmlNode slot = element("div");
        slot.attrs.put("class", extraClass.isEmpty() ? "mc-slot" : "mc-slot " + extraClass);
        slot.attrs.put("data-slot", index);
        if (!style.isEmpty()) slot.attrs.put("style", style);
        return slot;
    }

    private static HtmlNode inventoryRow() {
        HtmlNode row = element("div");
        row.attrs.put("class", "mc-inventory-row");
        row.attrs.put("style", "display:flex;flex-direction:row;gap:0px;");
        return row;
    }

    /**
     * 要素を作る。
     * ID は同じ文書を再パースすれば同じ順で振られるため、DOM 差分の照合に使える。
     */
    private static HtmlNode element(String tag) {
        HtmlNode node = new HtmlElement(tag);
        node.setId(NodeId.generateId());
        return node;
    }

    private static void appendChild(HtmlNode parent, HtmlNode child) {
        child.parent = parent;
        parent.children.add(child);
    }

    private static String attr(HtmlNode node, String name, String defaultValue) {
        String value = node.attrs.get(name);
        return (value == null || value.isEmpty()) ? defaultValue : value.trim();
    }

    /**
     * 整数属性。
     * @param minValue 下限（{@link Integer#MIN_VALUE} を渡すと下限なし）
     */
    private static int intAttr(HtmlNode node, String name, int defaultValue, int minValue) {
        String raw = attr(node, name, String.valueOf(defaultValue));
        int parsed;
        try {
            parsed = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            parsed = defaultValue;
        }
        return minValue == Integer.MIN_VALUE ? parsed : Math.max(minValue, parsed);
    }

}
