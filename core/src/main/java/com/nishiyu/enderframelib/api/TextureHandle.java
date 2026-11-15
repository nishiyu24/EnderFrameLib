package com.nishiyu.enderframelib.api;

import org.jetbrains.annotations.ApiStatus;

/**
 * GPU で描いた結果をコピーなしでホストへ渡すハンドル。ネイティブハンドルの寿命は面（{@link WebViewSurface}）と同じで、
 * 面を閉じる・大きさを変えると無効になる。所有権は面にあるので、ホストは破棄しない。
 *
 * @param kind   {@code handle} の意味
 * @param handle ネイティブハンドル
 * @param width  画素の幅（CSS px × devicePixelRatio）
 * @param height 画素の高さ
 */
@ApiStatus.AvailableSince("1.0")
public record TextureHandle(TextureKind kind, long handle, int width, int height) {
    public TextureHandle {
        java.util.Objects.requireNonNull(kind, "kind");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("size must be positive");
    }
}
