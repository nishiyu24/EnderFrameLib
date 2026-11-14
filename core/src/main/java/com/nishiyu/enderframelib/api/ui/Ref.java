package com.nishiyu.enderframelib.api.ui;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * A mutable reference container, typically used to capture a mounted {@link com.nishiyu.enderframelib.api.dom.Element}.
 *
 * @param <T> the referenced object type (typically {@link com.nishiyu.enderframelib.api.dom.Element})
 */
@ApiStatus.AvailableSince("1.0")
public final class Ref<T> {

    private T current;

    public Ref() {}

    public Ref(@Nullable T initial) {
        this.current = initial;
    }

    public static <T> Ref<T> create() {
        return new Ref<>();
    }

    public static <T> Ref<T> of(@Nullable T initial) {
        return new Ref<>(initial);
    }

    @Nullable
    public T get() {
        return current;
    }

    @Nullable
    public T current() {
        return current;
    }

    public void set(@Nullable T value) {
        this.current = value;
    }

    public boolean isPresent() {
        return current != null;
    }

    public Optional<T> asOptional() {
        return Optional.ofNullable(current);
    }

    @Override
    public String toString() {
        return "Ref[" + current + "]";
    }
}
