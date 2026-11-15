package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.api.WindowHost;

/** GLFW の窓へ表示する {@link WindowHost}（{@code META-INF/services} に登録）。 */
public final class GlfwWindowHost implements WindowHost {
    @Override
    public void show(String html, int width, int height) {
        StandaloneViewer.show(html, width, height, v -> {});
    }
}
