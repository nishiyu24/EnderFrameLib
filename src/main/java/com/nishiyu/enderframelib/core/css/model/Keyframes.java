package com.nishiyu.enderframelib.core.css.model;

import com.nishiyu.enderframelib.core.css.*;
import com.nishiyu.enderframelib.core.css.model.*;

import java.util.List;

/**
 * {@code @keyframes} 1 つ。ブロックは出現順（進行度の昇順とは限らない）。
 */
public record Keyframes(String name, List<KeyframeBlock> blocks) {}
