package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.platform.NativeImage;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ソフトウェアで描いた画素（SVG・canvas）のテクスチャ。キーごとに 1 枚持ち、版が変わったときだけ上げ直す。
 * 描画スレッドでだけ触る。古いものから捨てる（SVG は寸法ごとにキーが変わるので溜まる）。
 */
final class RasterTextures {

    private static final int MAX_TEXTURES = 96;

    private static final class Slot {
        final ResourceLocation id;
        final DynamicTexture texture;
        long version;

        Slot(ResourceLocation id, DynamicTexture texture, long version) {
            this.id = id;
            this.texture = texture;
            this.version = version;
        }
    }

    private static final Map<String, Slot> SLOTS = new LinkedHashMap<>(64, 0.75f, true);
    private static int nextId;

    private RasterTextures() {}

    /**
     * キーのテクスチャを返す（画素が変わっていれば上げ直す）。{@code cssW} / {@code cssH} は
     * 画像の CSS px での大きさ（{@code object-fit} の縦横比の基準）。画素の大きさとは別。
     */
    static WebMediaManager.ImageInfo textureFor(String key, Pixmap pixmap, long version, int cssW, int cssH) {
        Slot slot = SLOTS.get(key);
        if (slot != null && (slot.texture.getPixels() == null
                || slot.texture.getPixels().getWidth() != pixmap.width()
                || slot.texture.getPixels().getHeight() != pixmap.height())) {
            release(key);
            slot = null;
        }
        if (slot == null) {
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, pixmap.width(), pixmap.height(), false);
            fill(image, pixmap);
            DynamicTexture texture = new DynamicTexture(image);
            texture.setFilter(true, false);
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("enderframelib", "raster_" + (nextId++));
            Minecraft.getInstance().getTextureManager().register(id, texture);
            slot = new Slot(id, texture, version);
            SLOTS.put(key, slot);
            trim();
        } else if (slot.version != version) {
            NativeImage image = slot.texture.getPixels();
            if (image != null) {
                fill(image, pixmap);
                slot.texture.upload();
            }
            slot.version = version;
        }
        return new WebMediaManager.ImageInfo(slot.id, cssW, cssH);
    }

    /** 非乗算の ARGB を、NativeImage の ABGR にして書く。 */
    private static void fill(NativeImage image, Pixmap pixmap) {
        int w = pixmap.width();
        int h = pixmap.height();
        int[] straight = pixmap.toStraightArgb();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = straight[y * w + x];
                int abgr = (argb & 0xFF00FF00) | ((argb & 0x00FF0000) >> 16) | ((argb & 0x000000FF) << 16);
                image.setPixelRGBA(x, y, abgr);
            }
        }
    }

    private static void trim() {
        Iterator<Map.Entry<String, Slot>> it = SLOTS.entrySet().iterator();
        while (SLOTS.size() > MAX_TEXTURES && it.hasNext()) {
            Map.Entry<String, Slot> eldest = it.next();
            it.remove();
            Minecraft.getInstance().getTextureManager().release(eldest.getValue().id);
        }
    }

    private static void release(String key) {
        Slot s = SLOTS.remove(key);
        if (s != null) Minecraft.getInstance().getTextureManager().release(s.id);
    }

    static void clear() {
        for (Slot s : SLOTS.values()) Minecraft.getInstance().getTextureManager().release(s.id);
        SLOTS.clear();
    }
}
