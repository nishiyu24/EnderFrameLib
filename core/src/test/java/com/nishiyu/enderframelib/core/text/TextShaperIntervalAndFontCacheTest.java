package com.nishiyu.enderframelib.core.text;

import com.nishiyu.enderframelib.core.box.Display;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import com.nishiyu.enderframelib.core.paint.tree.PaintView;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.text.model.*;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P5: テキスト成形の幅区間キャッシュ、{@link BitmapFontFace} の {@code naturalAdvance} 語単位キャッシュ、
 * フォントサイズ変更時の段落セグメント再利用、および {@link TextShaper#wrapParagraph} の一致検証。
 */
class TextShaperIntervalAndFontCacheTest {

    private static final class CountingBitmapFace extends BitmapFontFace {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        protected float naturalAdvance(String text, boolean bold) {
            calls.incrementAndGet();
            int width = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == 'l' || c == 'i' || c == '!' || c == '.' || c == ':' || c == '|') width += 3;
                else if (c == ' ') width += 4;
                else if (c <= 0x00FF) width += 7;
                else width += 12;
            }
            if (bold) width += text.length();
            return width;
        }
    }

    @Test
    void bitmapFontFaceCachesNaturalAdvanceAcrossSizesAndRepeats() {
        CountingBitmapFace face = new CountingBitmapFace();
        FontStyle s16 = new FontStyle(16f, false, false, 0f);
        FontStyle s24 = new FontStyle(24f, false, false, 1f);
        FontStyle b16 = new FontStyle(16f, true, false, 0f);

        float w1 = face.advance("alpha", s16);
        int afterFirst = face.calls.get();
        assertEquals(1, afterFirst);

        // 別インスタンス・別サイズ・別字間でも naturalAdvance は再実行されない
        assertEquals(w1, face.advance(new String("alpha"), s16));
        assertEquals(afterFirst, face.calls.get());

        float expected24 = (w1 / face.scaleFor(s16)) * face.scaleFor(s24) + 5f;
        assertEquals(expected24, face.advance("alpha", s24), 0.0001f);
        assertEquals(afterFirst, face.calls.get());

        // 単一 ASCII 文字のキャッシュも効く
        float ch = face.advance("A", s16);
        int afterChar = face.calls.get();
        assertEquals(ch * 1.5f, face.advance("A", new FontStyle(24f, false, false, 0f)), 0.0001f);
        assertEquals(afterChar, face.calls.get());

        // 太字フラグが変われば別計算
        float wb = face.advance("alpha", b16);
        assertTrue(wb > w1);
        assertEquals(afterChar + 1, face.calls.get());
        assertEquals(wb, face.advance("alpha", b16));
        assertEquals(afterChar + 1, face.calls.get());
    }

    @Test
    void segmentedReusesBaseNaturalWidthsWhenFontSizeChanges() {
        CountingBitmapFace face = new CountingBitmapFace();
        String paragraph = "seg-" + System.nanoTime() + " two-viewers alpha beta gamma delta";
        FontStyle s15 = new FontStyle(15f, false, false, 0f);
        FontStyle s16 = new FontStyle(16f, false, false, 0f);
        FontStyle s21 = new FontStyle(21f, false, false, 0f);

        ParagraphSegments p15 = TextShaper.segmented(paragraph, face, s15, WordBreak.NORMAL);
        int callsAfterFirst = face.calls.get();
        assertTrue(callsAfterFirst > 0);

        // 異なる fontSizePx では Base / NaturalSegments からスケーリングするだけ（naturalAdvance 0 回）
        ParagraphSegments p16 = TextShaper.segmented(paragraph, face, s16, WordBreak.NORMAL);
        ParagraphSegments p21 = TextShaper.segmented(paragraph, face, s21, WordBreak.NORMAL);
        assertEquals(callsAfterFirst, face.calls.get(), "font-size 変更で naturalAdvance を呼ばない");

        for (int i = 0; i < p16.segs().length; i++) {
            String seg = p16.segs()[i];
            if (seg.isEmpty()) continue;
            assertEquals(Float.floatToIntBits(face.advance(seg, s16)), Float.floatToIntBits(p16.fullW(i)));
            assertEquals(Float.floatToIntBits(face.advance(seg, s21)), Float.floatToIntBits(p21.fullW(i)));
            if (i > 0 && !p16.segs()[i - 1].isEmpty()) {
                assertEquals(Float.floatToIntBits(TextShaper.kerningBetween(face, s16, p16.segs()[i - 1], seg)),
                        Float.floatToIntBits(p16.joinW(i)));
            }
        }
        assertSame(p15, TextShaper.segmented(new String(paragraph), face, s15, WordBreak.NORMAL));
    }

    @Test
    void shapeIntervalCacheReusesSameLinesWithinBreakIntervalAndMatchesUncachedAcrossAllWidths() {
        FontFace face = FontFace.FALLBACK;
        FontStyle font = new FontStyle(16f, false, false, 0f);
        String unique = "interval-" + System.nanoTime() + " alpha beta gamma delta epsilon zeta eta theta";
        TextStyle wrapStyle = new TextStyle(font, WhiteSpace.NORMAL, WordBreak.NORMAL, false, false, 20f);

        float fullW = face.advance(unique, font);
        List<ShapedLine> oneLine = TextShaper.shape(unique, face, wrapStyle, fullW + 40f);
        assertEquals(1, oneLine.size());
        // 単一行の有効区間 [fullW, +inf) および unbounded (<= 0) で同一インスタンスを返す
        assertSame(oneLine, TextShaper.shape(unique, face, wrapStyle, fullW + 10f));
        assertSame(oneLine, TextShaper.shape(unique, face, wrapStyle, fullW + 200f));
        assertSame(oneLine, TextShaper.shape(unique, face, wrapStyle, -1f));

        // 複数行に折り返される場合も、同じ改行区間内なら再構築せず同一リスト、境界を超えると厳密一致
        int midW = (int) (fullW * 0.45f);
        // 文面は nanoTime で毎回変わるので、折り返しの境界が midW と midW+0.25 の間に入る回がある。境界をまたがない幅まで進める
        while (!TextShaper.shapeUncached(unique, face, wrapStyle, midW).equals(TextShaper.shapeUncached(unique, face, wrapStyle, midW + 0.25f))) midW++;
        List<ShapedLine> wrapped = TextShaper.shape(unique, face, wrapStyle, midW);
        assertTrue(wrapped.size() >= 2);
        assertSame(wrapped, TextShaper.shape(unique, face, wrapStyle, midW + 0.25f));

        // 狭い幅から広い幅、文字分解・ellipsis を含む全ケースで uncached と完全一致
        TextStyle[] styles = {
                wrapStyle,
                new TextStyle(font, WhiteSpace.NORMAL, WordBreak.BREAK_ALL, false, false, 20f),
                new TextStyle(font, WhiteSpace.NORMAL, WordBreak.NORMAL, true, false, 20f),
                new TextStyle(font, WhiteSpace.NOWRAP, WordBreak.NORMAL, false, false, 20f),
                new TextStyle(font, WhiteSpace.NOWRAP, WordBreak.NORMAL, false, true, 20f),
                new TextStyle(font, WhiteSpace.PRE_WRAP, WordBreak.NORMAL, false, false, 20f),
        };
        Random r = new Random(12345);
        String[] samples = {
                "short",
                "two-viewers alpha beta\ngamma delta   epsilon",
                "superlongunbrokenword1234567890 next",
                "日本語と English が混ざる文章の例"
        };
        for (int sIdx = 0; sIdx < samples.length; sIdx++) {
            String txt = sIdx + "-" + System.nanoTime() + " " + samples[sIdx];
            for (TextStyle ts : styles) {
                float[] widths = {-1f, 0f, 15f, 32.5f, 64f, 100f, 100.1f, 140f, 320f, 800f};
                for (int k = 0; k < 20; k++) {
                    float w = k < widths.length ? widths[k] : (10f + r.nextFloat() * 350f);
                    List<ShapedLine> cached = TextShaper.shape(txt, face, ts, w);
                    List<ShapedLine> uncached = TextShaper.shapeUncached(txt, face, ts, w);
                    assertEquals(uncached, cached, "mismatch at w=" + w + " style=" + ts + " text=" + txt);
                }
            }
        }
    }
}
