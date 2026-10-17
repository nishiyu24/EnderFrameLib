package com.nishiyu.enderframelib.common;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @deprecated サーバーではなくクライアント専用のクラスです。{@link com.nishiyu.enderframelib.client.ClientImageRegistry} を使用してください。
 */
@Deprecated
public class ServerImageRegistry {
    public static final com.nishiyu.enderframelib.core.layout.IntrinsicSizeResolver RESOLVER_ADAPTER = com.nishiyu.enderframelib.client.ClientImageRegistry.RESOLVER_ADAPTER;

    public static void setSize(String url, int width, int height) {
        com.nishiyu.enderframelib.client.ClientImageRegistry.setSize(url, width, height);
    }

    public static int[] getSize(String url) {
        return com.nishiyu.enderframelib.client.ClientImageRegistry.getSize(url);
    }

    public static void addReflowListener(Runnable listener) {
        com.nishiyu.enderframelib.client.ClientImageRegistry.addReflowListener(listener);
    }

    public static void removeReflowListener(Runnable listener) {
        com.nishiyu.enderframelib.client.ClientImageRegistry.removeReflowListener(listener);
    }
}