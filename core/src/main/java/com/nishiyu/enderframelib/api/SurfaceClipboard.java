package com.nishiyu.enderframelib.api;

import org.jetbrains.annotations.ApiStatus;

/** テキスト欄の Ctrl+C / X / V が使うクリップボード。既定は面ごとのメモリ内。ホストの本物のクリップボードへつなぐときに差し込む。 */
@ApiStatus.AvailableSince("1.0")
public interface SurfaceClipboard {

    /** 現在の文字列。無ければ空文字。 */
    String get();

    void set(String text);
}
