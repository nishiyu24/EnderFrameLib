package com.nishiyu.enderframelib.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * テキスト入力・テキストエリアのキャレット位置とスクロール状態。
 *
 * 何が壊れていたか: 同じ状態が WebRenderCore 側に 3 本の static マップとして
 * 二重に存在し、編集側 (WebTextareaEditor / WebScreenDelegate) が書くのは
 * こちら、描画側が読むのはあちらだったため、textarea のキャレットは常に
 * 文末に描かれ、矢印キー・Home / End・クリックでの移動もホイールスクロールも
 * 画面に反映されなかった。ここが唯一の出所。
 *
 * {@code client/gui} ではなく {@code client} に置いてあるのは、描画層
 * ({@code client/renderer}) を GUI 層に依存させないため。
 */
public class TextEditState {

    public static final Map<String, Integer> INPUT_SCROLL_OFFSETS = new ConcurrentHashMap<>();
    public static final Map<String, Integer> TEXTAREA_CURSOR_INDEX = new ConcurrentHashMap<>();
    public static final Map<String, Integer> TEXTAREA_SCROLL_ROW = new ConcurrentHashMap<>();

    public static int getInputScrollOffset(String renderId) {
        return INPUT_SCROLL_OFFSETS.getOrDefault(renderId, 0);
    }

    public static void setInputScrollOffset(String renderId, int offset) {
        INPUT_SCROLL_OFFSETS.put(renderId, offset);
    }

    public static int getTextareaCursor(String renderId, int fallback) {
        return TEXTAREA_CURSOR_INDEX.getOrDefault(renderId, fallback);
    }

    public static void setTextareaCursor(String renderId, int cursor) {
        TEXTAREA_CURSOR_INDEX.put(renderId, cursor);
    }

    public static int getTextareaScrollRow(String renderId) {
        return TEXTAREA_SCROLL_ROW.getOrDefault(renderId, 0);
    }

    public static void setTextareaScrollRow(String renderId, int row) {
        TEXTAREA_SCROLL_ROW.put(renderId, row);
    }

    public static void clearForElement(String renderId) {
        INPUT_SCROLL_OFFSETS.remove(renderId);
        TEXTAREA_CURSOR_INDEX.remove(renderId);
        TEXTAREA_SCROLL_ROW.remove(renderId);
    }
}
