package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.model.CssKeywords;
import com.nishiyu.enderframelib.core.css.model.TextAlign;

/**
 * 行の揃え（text-align / text-align-last / text-justify）。{@code justify} の行は開始端から置いて余りを配る。
 * 最終行（強制改行の前の行を含む）は {@code text-align-last}（auto なら justify は start）に従う。
 */
record LineJustify(TextAlign normal, boolean justifyNormal, TextAlign last, boolean justifyLast, String mode) {
    static LineJustify of(ComputedStyle style, TextAlign specified, TextAlign physical, boolean rtl) {
        String mode = style.typed().inherited.textJustify;
        mode = mode == null ? "auto" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (mode.equals("distribute")) mode = "inter-character";
        boolean justify = specified == TextAlign.JUSTIFY && !mode.equals("none");
        String lastRaw = style.typed().inherited.textAlignLast;
        String l = lastRaw == null ? "auto" : lastRaw.trim().toLowerCase(java.util.Locale.ROOT);
        TextAlign last;
        boolean justifyLast;
        if (l.equals("auto")) {
            last = physical;
            justifyLast = false;
        } else if (l.equals("justify")) {
            last = TextAlign.START.physical(rtl);
            justifyLast = !mode.equals("none");
        } else {
            TextAlign k = CssKeywords.parse(TextAlign.class, l);
            last = k == null ? physical : k.physical(rtl);
            justifyLast = false;
        }
        return new LineJustify(physical, justify, last, justifyLast, mode);
    }

    TextAlign align(InlineLine line) {
        return line.softWrapped ? normal : last;
    }

    boolean justifies(InlineLine line) {
        return line.softWrapped ? justifyNormal : justifyLast;
    }
}
