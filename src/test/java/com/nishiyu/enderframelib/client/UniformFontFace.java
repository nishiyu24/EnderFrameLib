package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.core.text.model.BitmapFontFace;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.util.Map;

/**
 * ゲーム内で Web の sans-serif 系に割り当てる {@code minecraft:uniform}（unifont）の字幅を写したテスト用の書体。
 *
 * {@link FontFace#FALLBACK} は 1 字 7 単位（約 0.78em）の粗い見積もりで、ゲーム内（約 0.44em）や
 * ブラウザの Segoe UI（約 0.5em）より 1.5 倍ほど広い。ゲーム内の見た目を検証するテストはこちらを使う。
 *
 * 字幅は unifont_all_no_pua-15.1.05.hex（1.21.1 の minecraft:font/unifont.zip）の各グリフの
 * 使われている列の幅から UnihexProvider と同じ式（列数 / 2 + 1）で求めた値。空白は include/space の 4。
 */
public final class UniformFontFace {

    /** U+0020〜U+007E の字幅（素のサイズ 9px あたり）。 */
    private static final int[] ASCII = {
            4, 1, 3, 4, 4, 4, 4, 1, 2, 2, 4, 4, 2, 3, 2, 4,
            4, 3, 4, 4, 4, 4, 4, 4, 4, 4, 2, 2, 3, 4, 3, 4,
            4, 4, 4, 4, 4, 4, 4, 4, 4, 3, 4, 4, 4, 4, 4, 4,
            4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 2, 4, 2, 4, 4,
            2, 4, 4, 4, 4, 4, 3, 4, 4, 3, 3, 4, 3, 4, 4, 4,
            4, 4, 4, 4, 3, 4, 4, 4, 4, 4, 4, 3, 1, 3, 4
    };

    /** showcase で使う記号の字幅（同じ hex から求めた値）。 */
    private static final Map<Integer, Integer> SYMBOLS = Map.ofEntries(
            Map.entry(0x00B7, 2), Map.entry(0x2192, 4), Map.entry(0x2605, 8), Map.entry(0x2606, 7),
            Map.entry(0x263A, 5), Map.entry(0x2665, 4), Map.entry(0x26BF, 7), Map.entry(0x2713, 7),
            Map.entry(0x25BE, 4), Map.entry(0x2764, 8));

    /** 一覧に無い全角の文字（CJK など）と半角の記号の字幅。 */
    private static final int WIDE = 9;
    private static final int NARROW_DEFAULT = 8;

    public static final FontFace FACE = new BitmapFontFace() {
        @Override
        protected float naturalAdvance(String text, boolean bold) {
            if (text == null || text.isEmpty()) return 0;
            float width = 0;
            int glyphs = 0;
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                glyphs++;
                if (cp >= 0x20 && cp <= 0x7E) width += ASCII[cp - 0x20];
                else if (SYMBOLS.containsKey(cp)) width += SYMBOLS.get(cp);
                else if (cp >= 0x2E80) width += WIDE;
                else width += NARROW_DEFAULT;
            }
            // 太字（§l）は字ごとに 1 単位ずらして重ね描きするので、その分だけ広い
            return bold ? width + glyphs : width;
        }
    };

    public static final FontResolver RESOLVER = family -> FACE;

    private UniformFontFace() {}
}
