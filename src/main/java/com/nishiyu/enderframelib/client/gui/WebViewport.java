package com.nishiyu.enderframelib.client.gui;

import com.nishiyu.enderframelib.client.ClientWebManager;
import net.minecraft.client.renderer.Rect2i;

/**
 * 画面サイズ・仮想解像度・スケーリング・オフセットおよび座標変換を管理するビューポートクラス。
 */
public class WebViewport {

    private int virtualWidth = 854;
    private int virtualHeight = 480;
    private float guiScaleFactor = 1.0f;
    private float offsetX = 0.0f;
    private float offsetY = 0.0f;

    public WebViewport() {}

    public WebViewport(int initialVirtualW, int initialVirtualH) {
        this.virtualWidth = initialVirtualW;
        this.virtualHeight = initialVirtualH;
    }

    public void update(int screenW, int screenH, int currentVirtualW, int currentVirtualH,
                       boolean fullscreenResponsive, float marginRatio, String sessionId) {
        int targetW = currentVirtualW;
        int targetH = currentVirtualH;

        if (fullscreenResponsive) {
            float aspect = (screenH > 0) ? ((float) screenW / (float) screenH) : (16.0f / 9.0f);
            targetW = Math.max(854, Math.round(480 * aspect));
            targetH = Math.max(480, Math.round(targetW / aspect));

            if (currentVirtualW != targetW || currentVirtualH != targetH) {
                if (sessionId != null && ClientWebManager.hasDocument(sessionId)) {
                    ClientWebManager.recomputeLayout(sessionId, targetW, targetH);
                }
            }
        }

        this.virtualWidth = targetW;
        this.virtualHeight = targetH;
        float scaleX = (screenW * marginRatio) / Math.max(1, targetW);
        float scaleY = (screenH * marginRatio) / Math.max(1, targetH);
        this.guiScaleFactor = Math.min(scaleX, scaleY);
        this.offsetX = Math.round((screenW - targetW * this.guiScaleFactor) / 2.0f);
        this.offsetY = Math.round((screenH - targetH * this.guiScaleFactor) / 2.0f);
    }

    public int virtualWidth() { return virtualWidth; }
    public int virtualHeight() { return virtualHeight; }
    public float guiScaleFactor() { return guiScaleFactor; }
    public float offsetX() { return offsetX; }
    public float offsetY() { return offsetY; }

    public double toWebX(double mouseX) {
        return (mouseX - offsetX) / guiScaleFactor;
    }

    public double toWebY(double mouseY) {
        return (mouseY - offsetY) / guiScaleFactor;
    }

    public Rect2i toScreenRect(int webX, int webY, int webW, int webH) {
        return new Rect2i(
                Math.round(this.offsetX + webX * this.guiScaleFactor),
                Math.round(this.offsetY + webY * this.guiScaleFactor),
                Math.round(webW * this.guiScaleFactor),
                Math.round(webH * this.guiScaleFactor)
        );
    }

    public Rect2i getViewportBounds() {
        return new Rect2i(
                Math.round(this.offsetX),
                Math.round(this.offsetY),
                Math.round(this.virtualWidth * this.guiScaleFactor),
                Math.round(this.virtualHeight * this.guiScaleFactor)
        );
    }
}
