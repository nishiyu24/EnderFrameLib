package com.nishiyu.enderframelib.content;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.dom.node.TagMacro;

import java.util.List;
import java.util.Map;

/**
 * Minecraft ホストのカスタムタグ {@code <mc-slot>} / {@code <mc-slot-grid>} / {@code <mc-inventory>} /
 * {@code <mc-bar>} / {@code <mc-energy>} / {@code <mc-tank>} を素の HTML 要素へ展開するマクロ群。
 * エンジン本体（core の {@code CustomTagExpander}）はこれらのタグ名を知らず、{@link McHostElements} 経由の SPI で受け取る。
 */
final class McTagMacros {

    private McTagMacros() {}

    static Map<String, TagMacro> all() {
        return Map.of(
                "mc-slot", s -> List.of(buildSlot(s)),
                "mc-slot-grid", s -> List.of(buildSlotGrid(s)),
                "mc-inventory", s -> List.of(buildInventory(s)),
                "mc-bar", s -> List.of(buildBar(s, "#89b4fa", "#181825", "")),
                "mc-energy", s -> List.of(buildBar(s, "#d62828", "#2b0a0a", unitLabel(s, "FE"))),
                "mc-tank", s -> List.of(buildBar(s, fluidColor(attr(s, "fluid", "")), "#10151f", unitLabel(s, "mB"))));
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
