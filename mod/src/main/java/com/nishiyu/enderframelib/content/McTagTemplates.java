package com.nishiyu.enderframelib.content;

import com.nishiyu.enderframelib.api.spi.TagTemplate;
import java.util.Map;

/**
 * Minecraft ホストのカスタムタグ {@code <mc-slot>} / {@code <mc-slot-grid>} / {@code <mc-inventory>} /
 * {@code <mc-bar>} / {@code <mc-energy>} / {@code <mc-tank>} を素の HTML の断片へ展開する規則群。
 * 公開 SPI（{@code api.spi.TagTemplate}）だけで書かれ、エンジン内部の DOM 型には触れない。
 * エンジン本体（core）はこれらのタグ名を知らず、{@link McHostElements} 経由の SPI で受け取る。
 */
final class McTagTemplates {

    private McTagTemplates() {}

    static Map<String, TagTemplate> all() {
        return Map.of(
                "mc-slot", (a, inner) -> buildSlot(a),
                "mc-slot-grid", (a, inner) -> buildSlotGrid(a),
                "mc-inventory", (a, inner) -> buildInventory(a),
                "mc-bar", (a, inner) -> buildBar(a, "#89b4fa", "#181825", ""),
                "mc-energy", (a, inner) -> buildBar(a, "#d62828", "#2b0a0a", unitLabel(a, "FE")),
                "mc-tank", (a, inner) -> buildBar(a, fluidColor(attr(a, "fluid", "")), "#10151f", unitLabel(a, "mB")));
    }

    // ==========================================
    // 各テンプレート
    // ==========================================

    /** {@code <mc-slot index="3">} → スロット 1 つ。 */
    private static String buildSlot(Map<String, String> a) {
        String index = attr(a, "index", attr(a, "slot", "0"));
        StringBuilder sb = new StringBuilder();
        slotDiv(sb, index, attr(a, "class", ""), attr(a, "style", ""), attr(a, "id", ""));
        return sb.toString();
    }

    /** {@code <mc-slot-grid cols rows gap start>} → 行ごとの flex コンテナ。 */
    private static String buildSlotGrid(Map<String, String> a) {
        int start = intAttr(a, "start", 0, 0);
        int cols = intAttr(a, "cols", 3, 1);
        int rows = intAttr(a, "rows", 3, 1);
        int gap = intAttr(a, "gap", 0, 0);

        StringBuilder sb = new StringBuilder();
        open(sb, "div", null, "display:flex;flex-direction:column;gap:" + gap + "px;" + attr(a, "style", ""));
        int index = start;
        for (int r = 0; r < rows; r++) {
            open(sb, "div", null, "display:flex;flex-direction:row;gap:" + gap + "px;");
            for (int c = 0; c < cols; c++) slotDiv(sb, String.valueOf(index++), "", "", "");
            sb.append("</div>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    /** {@code <mc-inventory>} → 3×9 のメイン領域 ＋ 9 スロットのホットバー。 */
    private static String buildInventory(Map<String, String> a) {
        int start = intAttr(a, "start", 0, Integer.MIN_VALUE);
        boolean hotbarFirst = "true".equalsIgnoreCase(attr(a, "hotbar-first", "false"));

        int mainStart = hotbarFirst ? (start + 9) : start;
        int hotbarStart = hotbarFirst ? start : (start + 27);
        String hotbarAttr = attr(a, "hotbar", "");
        if (!hotbarAttr.isEmpty()) {
            try {
                hotbarStart = Integer.parseInt(hotbarAttr.trim());
            } catch (NumberFormatException ignored) {
            }
        }

        StringBuilder sb = new StringBuilder();
        open(sb, "div", "mc-inventory", "display:flex;flex-direction:column;gap:4px;" + attr(a, "style", ""));
        open(sb, "div", null, "display:flex;flex-direction:column;gap:0px;");
        int index = mainStart;
        for (int r = 0; r < 3; r++) {
            inventoryRow(sb);
            for (int c = 0; c < 9; c++) slotDiv(sb, String.valueOf(index++), "", "", "");
            sb.append("</div>");
        }
        sb.append("</div>");
        inventoryRow(sb);
        for (int c = 0; c < 9; c++) slotDiv(sb, String.valueOf(hotbarStart + c), "", "", "");
        sb.append("</div>");
        sb.append("</div>");
        return sb.toString();
    }

    /** {@code <mc-bar value min max color bg label>} → progress ＋ 中央ラベル。 */
    private static String buildBar(Map<String, String> a, String defaultColor, String defaultBg, String defaultLabel) {
        String value = attr(a, "value", "0");
        String min = attr(a, "min", "0");
        String max = attr(a, "max", "100");
        String color = attr(a, "color", defaultColor);
        String bg = attr(a, "bg", defaultBg);
        String label = attr(a, "label", defaultLabel);
        if ("none".equalsIgnoreCase(label)) label = "";

        StringBuilder sb = new StringBuilder();
        open(sb, "div", "mc-bar", "position:relative;overflow:hidden;background-color:" + bg + ";" + attr(a, "style", ""));
        sb.append("<progress value=\"").append(TagTemplate.escape(value))
                .append("\" min=\"").append(TagTemplate.escape(min))
                .append("\" max=\"").append(TagTemplate.escape(max))
                .append("\" style=\"").append(TagTemplate.escape(
                        "width:100%;height:100%;background-color:transparent;color:" + color + ";border-radius:0;"))
                .append("\"></progress>");
        if (!label.isEmpty()) {
            sb.append("<span style=\"").append(TagTemplate.escape("position:absolute;left:0;top:0;width:100%;height:100%;"
                            + "text-align:center;color:#ffffff;text-shadow:true;font-size:0.75rem;"))
                    .append("\">").append(TagTemplate.escape(label)).append("</span>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    /**
     * {@code <mc-energy>} / {@code <mc-tank>} の既定ラベル「value / max 単位」。値が {@code {{key}}} なら、
     * ラベルの文字列補間でそのまま状態に結び付く。{@code label="none"} でラベルを消す。
     */
    private static String unitLabel(Map<String, String> a, String unit) {
        String label = attr(a, "label", "");
        if ("none".equalsIgnoreCase(label)) return "";
        if (!label.isEmpty()) return label;
        return attr(a, "value", "0") + " / " + attr(a, "max", "100") + " " + unit;
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

    /** スロット 1 つ（開いて閉じる）。{@code id} が空なら振らない。 */
    private static void slotDiv(StringBuilder sb, String index, String extraClass, String style, String id) {
        sb.append("<div");
        if (!id.isEmpty()) sb.append(" id=\"").append(TagTemplate.escape(id)).append('"');
        sb.append(" class=\"").append(TagTemplate.escape(extraClass.isEmpty() ? "mc-slot" : "mc-slot " + extraClass)).append('"')
                .append(" data-slot=\"").append(TagTemplate.escape(index)).append('"');
        if (!style.isEmpty()) sb.append(" style=\"").append(TagTemplate.escape(style)).append('"');
        sb.append("></div>");
    }

    private static void inventoryRow(StringBuilder sb) {
        open(sb, "div", "mc-inventory-row", "display:flex;flex-direction:row;gap:0px;");
    }

    /** 開始タグ（{@code cssClass} は null なら振らない）。閉じるのは呼び出し側。 */
    private static void open(StringBuilder sb, String tag, String cssClass, String style) {
        sb.append('<').append(tag);
        if (cssClass != null) sb.append(" class=\"").append(TagTemplate.escape(cssClass)).append('"');
        sb.append(" style=\"").append(TagTemplate.escape(style)).append("\">");
    }

    private static String attr(Map<String, String> a, String name, String defaultValue) {
        String value = a.get(name);
        return (value == null || value.isEmpty()) ? defaultValue : value.trim();
    }

    /**
     * 整数属性。
     * @param minValue 下限（{@link Integer#MIN_VALUE} を渡すと下限なし）
     */
    private static int intAttr(Map<String, String> a, String name, int defaultValue, int minValue) {
        String raw = attr(a, name, String.valueOf(defaultValue));
        int parsed;
        try {
            parsed = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            parsed = defaultValue;
        }
        return minValue == Integer.MIN_VALUE ? parsed : Math.max(minValue, parsed);
    }
}
