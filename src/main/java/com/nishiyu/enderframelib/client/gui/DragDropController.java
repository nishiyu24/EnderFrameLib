package com.nishiyu.enderframelib.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nishiyu.enderframelib.client.ClientWebManager;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * UI要素のドラッグ＆ドロップ操作およびゴースト描画を管理するコントローラ。
 */
public class DragDropController {

    @FunctionalInterface
    public interface DropCallback {
        void onDrop(String draggedId, String dropTargetId, String dropAction);
    }

    private String draggingElementId = "";
    private String draggingLabel = "";

    public boolean isDragging() {
        return !draggingElementId.isEmpty();
    }

    public String draggingElementId() {
        return draggingElementId;
    }

    public void startDrag(String elementId, String label) {
        this.draggingElementId = elementId != null ? elementId : "";
        this.draggingLabel = label != null ? label : "";
    }

    public void clear() {
        this.draggingElementId = "";
        this.draggingLabel = "";
    }

    public void renderGhost(GuiGraphics guiGraphics, Font font, int webX, int webY) {
        if (!isDragging()) return;

        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(0, 0, 320.0f);
        int w = Math.max(60, font.width(draggingLabel) + 12);
        guiGraphics.fill(webX + 6, webY + 6, webX + 6 + w, webY + 24, 0xAA89B4FA);
        guiGraphics.drawString(font, draggingLabel, webX + 12, webY + 11, 0xFF11111B, false);
        pose.popPose();
    }

    public void handleRelease(String sessionId, double webX, double webY, DropCallback onWebDrop) {
        if (!isDragging()) return;

        ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, webX, webY);
        if (hit != null && hit.element() != null) {
            String targetBaseId = hit.element().baseId();
            String dropAction = hit.element().events() != null ? hit.element().events().getOrDefault("ondrop", "") : "";
            if (!targetBaseId.equals(draggingElementId) && onWebDrop != null) {
                onWebDrop.onDrop(draggingElementId, targetBaseId, dropAction);
            }
        }
        clear();
    }
}
