package com.nishiyu.enderframelib.api.ui;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * A reactive state holder that notifies registered listeners whenever its value changes.
 *
 * @param <T> the state value type
 */
@ApiStatus.AvailableSince("1.0")
public final class State<T> {

    private T value;
    private CopyOnWriteArrayList<Consumer<T>> listeners;

    public State(@Nullable T initial) {
        this.value = initial;
    }

    public static <T> State<T> of(@Nullable T initial) {
        return new State<>(initial);
    }

    /**
     * Creates a derived reactive state from {@code compute} and updates it whenever
     * any dependency in {@code deps} changes.
     */
    public static <T> State<T> derive(Supplier<? extends T> compute, State<?>... deps) {
        Objects.requireNonNull(compute, "compute");
        State<T> derived = new State<>(compute.get());
        if (deps != null) {
            for (State<?> dep : deps) {
                if (dep != null) {
                    dep.onChange(v -> derived.set(compute.get()));
                }
            }
        }
        return derived;
    }

    @Nullable
    public T get() {
        return value;
    }

    /**
     * Alias for {@link #get()}, matching JS/signal-style accessors.
     */
    @Nullable
    public T value() {
        return value;
    }

    /**
     * Sets a new value for this state. If the new value differs from the previous value,
     * all registered change listeners are notified.
     */
    public void set(@Nullable T next) {
        if (!Objects.equals(this.value, next)) {
            this.value = next;
            if (listeners != null) {
                for (Consumer<T> l : listeners) {
                    l.accept(next);
                }
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
     * Creates a derived reactive state by applying {@code mapper} whenever this state changes.
     */
    public <R> State<R> map(Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        State<R> derived = new State<>(mapper.apply(this.value));
        onChange(v -> derived.set(mapper.apply(v)));
        return derived;
    }

    /**
     * Creates a derived reactive state returning {@code whenTrue} if this state equals {@link Boolean#TRUE},
     * or {@code whenFalse} otherwise.
     */
    public <R> State<R> when(@Nullable R whenTrue, @Nullable R whenFalse) {
        return map(v -> Boolean.TRUE.equals(v) ? whenTrue : whenFalse);
    }

    /**
     * Creates a derived reactive state returning {@code whenTrue} if this state equals {@link Boolean#TRUE},
     * or {@code null} otherwise.
     */
    public <R> State<R> when(@Nullable R whenTrue) {
        return when(whenTrue, null);
    }

    /**
     * Registers a listener to be called whenever this state value changes.
     *
     * @return the registered listener, suitable for passing to {@link #removeListener}
     */
    public Consumer<T> onChange(Consumer<T> listener) {
        if (listeners == null) {
            listeners = new CopyOnWriteArrayList<>();
        }
        listeners.add(listener);
        return listener;
    }

    /**
     * Removes a previously registered change listener.
     */
    public void removeListener(Consumer<T> listener) {
        if (listeners != null) {
            listeners.remove(listener);
        }
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
