package com.nishiyu.enderframelib.common;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ServerImageRegistry {
    private static final ConcurrentHashMap<String, int[]> SIZES = new ConcurrentHashMap<>();

    // WeakReferenceを使用して、参照先が破棄されたら自動的に回収されるように変更
    private static final List<WeakReference<Runnable>> REFLOW_LISTENERS = new CopyOnWriteArrayList<>();

    // --- 追加: LayoutBoxに渡すためのアダプタ ---
    public static final com.nishiyu.enderframelib.core.layout.IntrinsicSizeResolver RESOLVER_ADAPTER = ServerImageRegistry::getSize;

    public static void setSize(String url, int width, int height) {
        int[] existing = SIZES.get(url);
        // 既に同じサイズで登録済みなら無視（無限ループ防止）
        if (existing != null && existing[0] == width && existing[1] == height) return;

        SIZES.put(url, new int[]{width, height});

        // サイズが新しく判明したら、稼働中の全ScreenAPIに再計算(リフロー)を指示
        // 同時に、既に破棄された(GCされた)リスナーをリストから取り除く
        REFLOW_LISTENERS.removeIf(ref -> {
            Runnable listener = ref.get();
            if (listener == null) {
                return true; // 破棄されているのでリストから削除
            } else {
                listener.run();
                return false; // まだ有効なので保持
            }
        });
    }

    public static int[] getSize(String url) {
        return SIZES.get(url);
    }

    // ScreenAPIから呼び出されるメソッド
    public static void addReflowListener(Runnable listener) {
        // 同じインスタンスが既に登録されていないかチェック
        boolean exists = false;
        for (WeakReference<Runnable> ref : REFLOW_LISTENERS) {
            if (ref.get() == listener) {
                exists = true;
                break;
            }
        }

        if (!exists) {
            REFLOW_LISTENERS.add(new WeakReference<>(listener));
        }
    }

    // 明示的にリスナーを削除したい場合のためのメソッド
    public static void removeReflowListener(Runnable listener) {
        REFLOW_LISTENERS.removeIf(ref -> ref.get() == null || ref.get() == listener);
    }
}