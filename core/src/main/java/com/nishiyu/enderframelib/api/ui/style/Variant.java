package com.nishiyu.enderframelib.api.ui.style;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * {@link StyleClass} の中の、条件つきの宣言のまとまり（{@link Css#when}・{@link Css#hover}・{@link Css#pressed}）。
 */
@ApiStatus.AvailableSince("1.0")
public final class Variant {

    private final @Nullable Modifier modifier;
    private final @Nullable String pseudo;
    private final List<Declaration> declarations;

    Variant(@Nullable Modifier modifier, @Nullable String pseudo, Declaration... declarations) {
        this.modifier = modifier;
        this.pseudo = pseudo;
        this.declarations = List.of(declarations);
    }

    /** {@code .name} を元にしたこの条件のセレクタ。 */
    String selector(String className) {
        String base = "." + className;
        if (modifier != null) base += "." + StyleClass.modifierClass(className, modifier);
        if (pseudo != null) base += ":" + pseudo;
        return base;
    }

    List<Declaration> declarations() {
        return declarations;
    }
}
