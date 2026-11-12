package com.nishiyu.enderframelib.api.ui;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * A reactive state holder, similar to React's {@code useState()}.
 * Notifies registered listeners whenever its value is updated.
 *
 * @param <T> the state value type
 */
@ApiStatus.AvailableSince("1.0")
public final class State<T> {

    private T value;
    private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();

    public State(@Nullable T initial) {
        this.value = initial;
    }

    public static <T> State<T> of(@Nullable T initial) {
        return new State<>(initial);
    }

    @Nullable
    public T get() {
        return value;
    }

    /**
     * Sets a new value for this state. If the new value differs from the previous value,
     * all registered change listeners are notified.
     */
    public void set(@Nullable T next) {
        if (!Objects.equals(this.value, next)) {
            this.value = next;
            for (Consumer<T> l : listeners) {
                l.accept(next);
            }
        }
    }

    /**
     * Updates the current state value using an updater function.
     */
    public void update(UnaryOperator<T> updater) {
        set(updater.apply(this.value));
    }

    /**
     * Registers a listener to be called whenever this state value changes.
     *
     * @return the registered listener, suitable for passing to {@link #removeListener}
     */
    public Consumer<T> onChange(Consumer<T> listener) {
        listeners.add(listener);
        return listener;
    }

    /**
     * Removes a previously registered change listener.
     */
    public void removeListener(Consumer<T> listener) {
        listeners.remove(listener);
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
