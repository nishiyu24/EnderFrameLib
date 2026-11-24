package com.nishiyu.enderframelib.api.ui.style;

import org.jetbrains.annotations.ApiStatus;

import java.util.regex.Pattern;

/**
 * スタイルの状態（{@code active}・{@code selected} など）。{@link Css#when} で状態ごとの宣言を定義し、
 * {@link StyleClass#is} で要素の状態に結ぶ。中身は BEM 風のクラス {@code <スタイル名>--<状態名>}。
 */
@ApiStatus.AvailableSince("1.0")
public record Modifier(String name) {

    private static final Pattern NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9-]*");

    public Modifier {
        if (name == null || !NAME.matcher(name).matches()) throw new IllegalArgumentException("状態名が不正: " + name);
    }

    public static Modifier of(String name) {
        return new Modifier(name);
    }
}
