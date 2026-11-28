package com.nishiyu.enderframelib.core.paint.scene;

/**
 * シーンにある動画の命令 1 つ（{@link SceneMedia#videos}。M3b-4c-2b-2b）。
 *
 * @param src   中身の指定（命令の文字）
 * @param loop  繰り返すか（{@code media-loop}）
 * @param muted 消音か（{@code media-muted}）
 */
public record SceneVideo(String src, boolean loop, boolean muted) {}
