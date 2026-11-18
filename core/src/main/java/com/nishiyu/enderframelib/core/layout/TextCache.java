package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.kernel.CompactList;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;
import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.text.ParagraphSegments;
import com.nishiyu.enderframelib.core.css.StyleFacts;
import com.nishiyu.enderframelib.core.layout.model.*;

import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssKeywords;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutEngineState;
import com.nishiyu.enderframelib.core.text.model.FontStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.PositionTryRule;
import java.util.Map;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/** A8 で {@link LayoutBox} の入れ子のクラスから最上位のクラスへ移した。 */
final class TextCache {
    static final class Slot {
        String text;
        FontFace face;
        TextStyle shapeStyle;
        TextShaper.ShapedInterval shapedInterval;
        FontStyle segFont;
        WordBreak wordBreak;
        ParagraphSegments segments;
        TextShaper.WrappedParagraph wrapped;
        float strippedAdvance = Float.NaN;
    }

    Slot primary;
    Slot secondary;

    Slot acquire(String text, FontFace face) {
        if (primary == null) {
            primary = new Slot();
            primary.text = text;
            primary.face = face;
            return primary;
        }
        if (primary.text == text && primary.face == face) {
            return primary;
        }
        if (secondary != null && secondary.text == text && secondary.face == face) {
            Slot tmp = primary;
            primary = secondary;
            secondary = tmp;
            return primary;
        }
        Slot target = secondary != null ? secondary : new Slot();
        target.text = text;
        target.face = face;
        target.shapeStyle = null;
        target.shapedInterval = null;
        target.segFont = null;
        target.wordBreak = null;
        target.segments = null;
        target.wrapped = null;
        target.strippedAdvance = Float.NaN;
        secondary = primary;
        primary = target;
        return target;
    }

    Slot acquireForStyle(String text, FontFace face, FontStyle font, WordBreak wordBreak) {
        if (primary != null && primary.text == text && primary.face == face
                && (primary.segFont == null || ((primary.segFont == font || primary.segFont.equals(font))
                        && (wordBreak == null || primary.wordBreak == null || primary.wordBreak == wordBreak)))) {
            if (primary.segFont == null) primary.segFont = font;
            if (primary.wordBreak == null && wordBreak != null) primary.wordBreak = wordBreak;
            return primary;
        }
        if (secondary != null && secondary.text == text && secondary.face == face
                && (secondary.segFont == null || ((secondary.segFont == font || secondary.segFont.equals(font))
                        && (wordBreak == null || secondary.wordBreak == null || secondary.wordBreak == wordBreak)))) {
            Slot hit = secondary;
            secondary = primary;
            primary = hit;
            if (hit.segFont == null) hit.segFont = font;
            if (hit.wordBreak == null && wordBreak != null) hit.wordBreak = wordBreak;
            return hit;
        }
        Slot target = secondary != null ? secondary : new Slot();
        target.text = text;
        target.face = face;
        target.shapeStyle = null;
        target.shapedInterval = null;
        target.segFont = font;
        target.wordBreak = wordBreak;
        target.segments = null;
        target.wrapped = null;
        target.strippedAdvance = Float.NaN;
        secondary = primary;
        primary = target;
        return target;
    }
}
