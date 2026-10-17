package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.text.FontMetrics;

public record LayoutContext(
        Viewport viewport,
        IntrinsicSizeResolver intrinsicResolver,
        ScrollState scrollState,
        FontMetrics fontMetrics
) {}