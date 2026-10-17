package com.nishiyu.enderframelib.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.core.dom.DomUtils;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.model.ElementType;
import com.nishiyu.enderframelib.core.model.UIElement;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * F12キー押下時のDevToolsインスペクタ（UI要素・DOM属性・寸法・マージンの可視化）描画を担当するクラス。
 */
public class WebDevTools {

    public static void renderInspector(
            GuiGraphics guiGraphics, Font font, List<UIElement> elements,
            int webX, int webY, int virtualWidth, int virtualHeight,
            String sessionId
    ) {
        if (elements == null) return;

        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(0, 0, 350.0f);

        String banner = " [F12] EnderFrame DevTools | [F5] Hot Reload | [Tab/Arrows] Focus | Viewport: "
                + virtualWidth + "x" + virtualHeight + " | Mouse: (" + webX + ", " + webY + ") ";
        guiGraphics.fill(0, 0, font.width(banner) + 8, 13, 0xDD11111B);
        guiGraphics.drawString(font, banner, 4, 2, 0xFFA6E3A1, false);

        UIElement targetAnchor = null;
        for (int i = elements.size() - 1; i >= 0; i--) {
            UIElement el = elements.get(i);
            if (el.type() == ElementType.NODE && !"html_root".equals(el.id()) && el.width() > 0 && el.height() > 0) {
                if (webX >= el.x() && webX <= el.x() + el.width() && webY >= el.y() && webY <= el.y() + el.height()) {
                    targetAnchor = el;
                    break;
                }
            }
        }

        if (targetAnchor != null) {
            HtmlNode vRoot = ClientWebManager.getVirtualRoot(sessionId);
            HtmlNode domNode = vRoot != null ? DomUtils.findNodeById(vRoot, targetAnchor.id()) : null;
            int x = targetAnchor.x(), y = targetAnchor.y(), w = targetAnchor.width(), h = targetAnchor.height();

            guiGraphics.renderOutline(x, y, w, h, 0xFF89B4FA);

            List<String> infoLines = new ArrayList<>();
            String tagLabel = domNode != null ? "<" + domNode.tag + ">" : "<node>";
            String idLabel = (domNode != null && !domNode.getId().isEmpty()) ? "#" + domNode.getId() : "#" + targetAnchor.id();
            String clsLabel = (domNode != null && !domNode.getClasses().isEmpty()) ? "." + String.join(".", domNode.getClasses()) : "";
            infoLines.add("§b" + tagLabel + " §e" + idLabel + "§a" + clsLabel);
            infoLines.add("§7Rect: §f" + w + "x" + h + " §7at (" + x + ", " + y + ")");
            if (domNode != null) {
                if (domNode.attrs.containsKey("data-model")) infoLines.add("§ddata-model: §f" + domNode.attrs.get("data-model"));
                if (domNode.attrs.containsKey("data-bind")) infoLines.add("§ddata-bind: §f" + domNode.attrs.get("data-bind"));
                if (domNode.attrs.containsKey("data-slot")) infoLines.add("§6data-slot: §f#" + domNode.attrs.get("data-slot"));
                if (domNode.attrs.containsKey("onclick")) infoLines.add("§conclick: §f" + domNode.attrs.get("onclick"));
            }

            int cardW = 0;
            for (String line : infoLines) cardW = Math.max(cardW, font.width(line) + 10);
            int cardH = infoLines.size() * 11 + 6;
            int cardX = Math.clamp(x, 4, Math.max(4, virtualWidth - cardW - 4));
            int cardY = (y - cardH - 4 >= 16) ? (y - cardH - 4) : Math.min(virtualHeight - cardH - 4, y + h + 4);

            guiGraphics.fill(cardX - 1, cardY - 1, cardX + cardW + 1, cardY + cardH + 1, 0xFF89B4FA);
            guiGraphics.fill(cardX, cardY, cardX + cardW, cardY + cardH, 0xEE181825);
            for (int i = 0; i < infoLines.size(); i++) {
                guiGraphics.drawString(font, infoLines.get(i), cardX + 5, cardY + 4 + i * 11, 0xFFFFFFFF, false);
            }
        }

        pose.popPose();
    }
}
