package com.nishiyu.enderframelib.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nishiyu.enderframelib.core.dom.DomUtils;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.model.UIElement;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * select要素のドロップダウンポップアップと選択状態をカプセル化するウィジェット。
 */
public class SelectDropdownWidget {

    public record SelectOption(String value, String label) {}

    private String openSelectBaseId = "";
    private int popupX;
    private int popupY;
    private int popupW;
    private final List<SelectOption> options = new ArrayList<>();

    public boolean isOpen() {
        return !openSelectBaseId.isEmpty() && !options.isEmpty();
    }

    public String openSelectBaseId() {
        return openSelectBaseId;
    }

    public void open(String baseId, UIElement selectEl, HtmlNode virtualRoot) {
        if (baseId.equals(openSelectBaseId)) {
            close();
            return;
        }
        HtmlNode selectNode = DomUtils.findNodeById(virtualRoot, baseId);
        if (selectNode == null) return;

        options.clear();
        for (HtmlNode child : selectNode.children) {
            if ("option".equalsIgnoreCase(child.tag)) {
                String val = child.attrs.getOrDefault("value", child.getTextContent().trim());
                String label = child.getTextContent().trim();
                options.add(new SelectOption(val, label.isEmpty() ? val : label));
            }
        }

        if (!options.isEmpty()) {
            this.openSelectBaseId = baseId;
            this.popupX = selectEl.x();
            this.popupY = selectEl.y() + selectEl.height() + 2;
            this.popupW = Math.max(selectEl.width(), 90);
        }
    }

    public void close() {
        this.openSelectBaseId = "";
        this.options.clear();
    }

    public boolean handleClick(double webX, double webY, BiConsumer<String, String> onSelected) {
        if (!isOpen()) return false;

        int itemH = 18;
        int totalH = options.size() * itemH + 2;
        boolean withinBounds = webX >= popupX && webX <= popupX + popupW && webY >= popupY && webY <= popupY + totalH;

        if (withinBounds) {
            int idx = (int) ((webY - popupY - 1) / itemH);
            if (idx >= 0 && idx < options.size()) {
                String chosenVal = options.get(idx).value();
                String baseId = openSelectBaseId;
                close();
                if (onSelected != null) {
                    onSelected.accept(baseId, chosenVal);
                }
                return true;
            }
        }
        close();
        return withinBounds;
    }

    public void renderOverlay(GuiGraphics guiGraphics, Font font, int webX, int webY, HtmlNode virtualRoot) {
        if (!isOpen()) return;

        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(0, 0, com.nishiyu.enderframelib.client.renderer.WebRenderCore.ZLayers.DROPDOWN_POPUP_OVERLAY_Z);

        int itemH = 18;
        int totalH = options.size() * itemH + 2;
        guiGraphics.fill(popupX, popupY, popupX + popupW, popupY + totalH, 0xFF89B4FA);
        guiGraphics.fill(popupX + 1, popupY + 1, popupX + popupW - 1, popupY + totalH - 1, 0xFF181825);

        String currentVal = getCurrentValue(virtualRoot);
        for (int i = 0; i < options.size(); i++) {
            SelectOption opt = options.get(i);
            int iy = popupY + 1 + i * itemH;
            boolean hov = webX >= popupX && webX <= popupX + popupW && webY >= iy && webY < iy + itemH;
            boolean sel = opt.value().equals(currentVal);
            if (hov) guiGraphics.fill(popupX + 1, iy, popupX + popupW - 1, iy + itemH, 0xFF45475A);
            else if (sel) guiGraphics.fill(popupX + 1, iy, popupX + popupW - 1, iy + itemH, 0xFF313244);
            guiGraphics.drawString(font, opt.label(), popupX + 6, iy + 5, sel ? 0xFF89B4FA : 0xFFCDD6F4, false);
        }
        pose.popPose();
    }

    private String getCurrentValue(HtmlNode virtualRoot) {
        HtmlNode node = DomUtils.findNodeById(virtualRoot, openSelectBaseId);
        if (node == null) return "";
        if (node.attrs.containsKey("value") && !node.attrs.get("value").isEmpty()) {
            return node.attrs.get("value");
        }
        return !options.isEmpty() ? options.getFirst().value() : "";
    }
}
