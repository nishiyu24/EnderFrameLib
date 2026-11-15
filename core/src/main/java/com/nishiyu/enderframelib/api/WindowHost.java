package com.nishiyu.enderframelib.api;

import org.jetbrains.annotations.ApiStatus;

/**
 * 窓を開いて HTML を表示する実装の SPI（{@link java.util.ServiceLoader}。{@code core-glfw} が提供する）。
 * {@link EnderEngine.Builder#openWindow} が使う。利用者が直接触ることは無い。
 */
@ApiStatus.Internal
public interface WindowHost {
    /** 窓に表示する。閉じるまで戻らない。 */
    void show(String html, int width, int height);
}
