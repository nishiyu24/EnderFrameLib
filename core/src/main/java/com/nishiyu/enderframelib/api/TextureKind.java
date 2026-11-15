package com.nishiyu.enderframelib.api;

import org.jetbrains.annotations.ApiStatus;

/** {@link TextureHandle} のネイティブハンドルの意味。 */
@ApiStatus.AvailableSince("1.0")
public enum TextureKind {
    /** OpenGL のテクスチャ名（{@code GLuint}）。描いたコンテキストと共有されたコンテキストで使える。 */
    OPENGL_TEXTURE,
    /** Vulkan の {@code VkImage}。 */
    VULKAN_IMAGE
}
