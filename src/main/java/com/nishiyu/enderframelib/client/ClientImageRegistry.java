package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.core.layout.LayoutContext.IntrinsicSizeResolver;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * クライアント側で非同期ロードされた外部画像の解像度を保持し、
 * サイズ判明時にレイアウトの自動リフローをトリガーするレジストリ。
 */
public class ClientImageRegistry {

    private static final int MAX_SIZES = 2000;
    private static final Map<String, int[]> SIZES = Collections.synchronizedMap(
            new LinkedHashMap<String, int[]>(512, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, int[]> eldest) {
                    return size() > MAX_SIZES;
                }
            }
    );

    private static final List<WeakReference<Runnable>> REFLOW_LISTENERS = new CopyOnWriteArrayList<>();

    public static final IntrinsicSizeResolver RESOLVER_ADAPTER = ClientImageRegistry::getSize;

    public static void setSize(String url, int width, int height) {
        int[] existing = SIZES.get(url);
        if (existing != null && existing[0] == width && existing[1] == height) return;

        SIZES.put(url, new int[]{width, height});

        REFLOW_LISTENERS.removeIf(ref -> {
            Runnable listener = ref.get();
            if (listener == null) {
                return true;
            } else {
                listener.run();
                return false;
            }
        });
    }

    public static int[] getSize(String url) {
        return SIZES.get(url);
    }

    public static void addReflowListener(Runnable listener) {
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

    public static void removeReflowListener(Runnable listener) {
        REFLOW_LISTENERS.removeIf(ref -> ref.get() == null || ref.get() == listener);
    }
}
