package com.nishiyu.enderframelib.core.event;

import com.nishiyu.enderframelib.api.event.UIEvents;

public class CoreUIEvent extends CoreEvent implements UIEvents.UI {
    private final int detail;

    public CoreUIEvent(String type, boolean bubbles, boolean cancelable, int detail) {
        super(type, bubbles, cancelable);
        this.detail = detail;
    }

    @Override
    public int getDetail() {
        return detail;
    }
}
