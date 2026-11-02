package com.nishiyu.enderframelib.api.dom;

import org.jetbrains.annotations.ApiStatus;

/**
 * {@code window.matchMedia()} の結果。{@link #matches()} は問い合わせた時点の文書のメディア環境（ビューポートの大きさ・
 * {@code prefers-*}）での評価。変化の通知（{@code change} イベント）は持たない。
 */
@ApiStatus.AvailableSince("1.0")
public record MediaQueryList(String media, boolean matches) {
}
