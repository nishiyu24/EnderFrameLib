package com.nishiyu.enderframelib.api.event;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Options that may be supplied to {@code addEventListener}.
 *
 * <p>Conforms to WHATWG DOM Standard §2.7 Dictionary AddEventListenerOptions.</p>
 */
@ApiStatus.AvailableSince("1.0")
public class AddEventListenerOptions extends EventListenerOptions {

    private final boolean passive;
    private final boolean once;
    private final AbortSignal signal;

    public AddEventListenerOptions(boolean capture, boolean passive, boolean once, @Nullable AbortSignal signal) {
        super(capture);
        this.passive = passive;
        this.once = once;
        this.signal = signal;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static AddEventListenerOptions of(boolean capture) {
        return new AddEventListenerOptions(capture, false, false, null);
    }

    public static AddEventListenerOptions of(boolean capture, boolean once) {
        return new AddEventListenerOptions(capture, false, once, null);
    }

    public boolean isPassive() {
        return passive;
    }

    public boolean isOnce() {
        return once;
    }

    @Nullable
    public AbortSignal getSignal() {
        return signal;
    }

    public static final class Builder {
        private boolean capture = false;
        private boolean passive = false;
        private boolean once = false;
        private AbortSignal signal = null;

        public Builder capture(boolean capture) {
            this.capture = capture;
            return this;
        }

        public Builder passive(boolean passive) {
            this.passive = passive;
            return this;
        }

        public Builder once(boolean once) {
            this.once = once;
            return this;
        }

        public Builder signal(@Nullable AbortSignal signal) {
            this.signal = signal;
            return this;
        }

        public AddEventListenerOptions build() {
            return new AddEventListenerOptions(capture, passive, once, signal);
        }
    }
}
