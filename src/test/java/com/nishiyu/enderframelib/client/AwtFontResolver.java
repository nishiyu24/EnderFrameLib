package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import com.nishiyu.enderframelib.core.text.model.FontStyle;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.geom.Rectangle2D;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java 標準の {@link java.awt.Font} を使用してフォントメトリクスを解決するリゾルバー。
 * ネイティブ DLL や FreeType に依存せず、純粋な Java 環境（AWT / Swing / ヘッドレス）で動作します。
 */
public class AwtFontResolver implements FontResolver {

    public static final AwtFontResolver INSTANCE = new AwtFontResolver();
    private final Map<String, FontFace> cache = new ConcurrentHashMap<>();

    @Override
    public FontFace resolve(String fontFamily) {
        String key = (fontFamily != null && !fontFamily.isBlank()) ? fontFamily.trim() : "SansSerif";
        String normalized = switch (key.toLowerCase()) {
            case "sans-serif" -> "SansSerif";
            case "serif" -> "Serif";
            case "monospace" -> "Monospaced";
            default -> key;
        };
        return cache.computeIfAbsent(normalized, AwtFontFace::new);
    }

    public static class AwtFontFace implements FontFace {
        private final String family;
        private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

        public AwtFontFace(String family) {
            this.family = family;
        }

        public Font getAwtFont(FontStyle style) {
            int awtStyle = Font.PLAIN;
            if (style.bold() && style.italic()) {
                awtStyle = Font.BOLD | Font.ITALIC;
            } else if (style.bold()) {
                awtStyle = Font.BOLD;
            } else if (style.italic()) {
                awtStyle = Font.ITALIC;
            }
            int size = Math.max(1, Math.round(style.fontSizePx()));
            return new Font(family, awtStyle, size);
        }

        @Override
        public float advance(String text, FontStyle style) {
            if (text == null || text.isEmpty()) return 0;
            Font font = getAwtFont(style);
            Rectangle2D bounds = font.getStringBounds(text, FRC);
            float baseAdvance = (float) bounds.getWidth();
            if (style.letterSpacingPx() != 0) {
                int glyphs = glyphCount(text);
                baseAdvance += glyphs * style.letterSpacingPx();
            }
            return baseAdvance;
        }

        @Override
        public float ascent(FontStyle style) {
            Font font = getAwtFont(style);
            LineMetrics lm = font.getLineMetrics("Mg", FRC);
            return lm.getAscent();
        }

        @Override
        public float descent(FontStyle style) {
            Font font = getAwtFont(style);
            LineMetrics lm = font.getLineMetrics("Mg", FRC);
            return lm.getDescent();
        }

        @Override
        public float lineGap(FontStyle style) {
            Font font = getAwtFont(style);
            LineMetrics lm = font.getLineMetrics("Mg", FRC);
            return lm.getLeading();
        }

        @Override
        public float xHeight(FontStyle style) {
            Font font = getAwtFont(style);
            Rectangle2D bounds = font.getStringBounds("x", FRC);
            return (float) bounds.getHeight();
        }
    }
}
