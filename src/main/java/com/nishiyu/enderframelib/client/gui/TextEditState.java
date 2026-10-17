package com.nishiyu.enderframelib.client.gui;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * テキスト入力およびテキストエリアのスクロール・キャレット位置状態を管理するオブジェクト。
 * レンダリング層と入力制御層の結合を解き、エディタ所有とする。
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
