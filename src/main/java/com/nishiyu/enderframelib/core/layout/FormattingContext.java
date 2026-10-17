package com.nishiyu.enderframelib.core.layout;

public record FormattingContext(String parentDisplay, String alignItems, boolean isShrink) {
    public boolean isFlexRow() { return parentDisplay.startsWith("flex-row"); }
    public boolean isFlexColumn() { return parentDisplay.startsWith("flex-column"); }
}