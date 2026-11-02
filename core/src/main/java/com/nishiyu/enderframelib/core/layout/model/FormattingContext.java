package com.nishiyu.enderframelib.core.layout.model;

import com.nishiyu.enderframelib.core.layout.*;
import com.nishiyu.enderframelib.core.layout.model.*;

/**
 * 子の測定に親から降りてくる整形コンテキスト。
 */
public record FormattingContext(String parentDisplay, String alignItems, boolean isShrink) {
    /** 通常のブロックフロー（幅いっぱいに広がる）。 */
    public static final FormattingContext BLOCK = new FormattingContext("block", "stretch", false);

    public boolean isFlexRow() { return parentDisplay.startsWith("row"); }
    public boolean isFlexColumn() { return parentDisplay.startsWith("column"); }
}
