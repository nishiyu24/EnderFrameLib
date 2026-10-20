package com.nishiyu.enderframelib.core.css.model;

import com.nishiyu.enderframelib.core.css.*;
import com.nishiyu.enderframelib.core.css.model.*;

import java.util.Map;

/**
 * キーフレームのブロック 1 つ。{@code percent} は 0〜100。{@code from} は 0、{@code to} は 100。
 * {@code declarations} は宣言順を保つ。
 */
public record KeyframeBlock(float percent, Map<String, String> declarations) {}
