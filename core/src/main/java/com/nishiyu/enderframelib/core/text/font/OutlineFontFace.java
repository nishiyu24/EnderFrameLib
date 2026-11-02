package com.nishiyu.enderframelib.core.text.font;

import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * システムのアウトライン書体（TrueType / OpenType）による {@link FontFace}。
 *
 * {@code font-family} の並びと代替書体から、字ごとに字形を持つ最初の書体を選ぶ（CSS Fonts 4 §5.4 の字単位の代替）。
 * 縦の寸法は並びの最初の書体（第 1 書体）から取る。送り幅はヒンティングの無い設計上の値で、
 * 部分文字列の幅の合計が全体の幅と一致する（折り返し位置が描画とずれない）。
 *
 * カーニングと合字は適用しない（Chrome は既定で適用するので、字の組み合わせによって幅がわずかに違う）。
 */
public final class OutlineFontFace implements FontFace {

    /** 字ごとに選んだ書体の面と、合成の要否。 */
    public record GlyphFace(LoadedFace face, boolean syntheticBold, boolean syntheticItalic) {}

    private final SystemFonts catalog;
    private final List<String> families;
    private final List<String> fallbacks;
    private final String key;
    private final Map<Integer, StyleChain> chains = new ConcurrentHashMap<>();

    /**
     * @param families  {@code font-family} を展開した具体的な書体名（総称ファミリは展開済み）。並びの順に優先する
     * @param fallbacks 並びのどれにも字形が無いときに試す書体
     */
    public OutlineFontFace(SystemFonts catalog, List<String> families, List<String> fallbacks) {
        this.catalog = catalog;
        this.families = List.copyOf(families);
        this.fallbacks = List.copyOf(fallbacks);
        this.key = String.join(",", this.families);
    }

    /** 同じ並びの書体を見分けるための名前（描画側の字形の置き場の名前に使う）。 */
    public String key() {
        return key;
    }

    public List<String> families() {
        return families;
    }

    @Override
    public float advance(String text, FontStyle style) {
        if (text == null || text.isEmpty()) return 0;
        StyleChain chain = chainFor(style);
        float em = 0;
        int count = 0;
        LoadedFace prevFace = null;
        int prevGlyph = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isZeroWidth(cp)) continue;
            count++;
            LoadedFace face = chain.pick(cp).face();
            int glyph = face.glyphId(cp);
            // 詰めは同じ書体の字どうしだけ（代替書体との境目には掛からない）
            if (style.kerning() && face == prevFace) em += face.kerningEm(prevGlyph, glyph);
            em += face.advanceEm(cp);
            prevFace = face;
            prevGlyph = glyph;
        }
        float px = em * style.fontSizePx();
        if (style.letterSpacingPx() != 0) px += style.letterSpacingPx() * count;
        return px;
    }

    /**
     * 字ごとの送り位置（px）。{@code out[i]} は i 番目の char の字の原点の x（代用対の後半は前半と同じ値）、
     * {@code out[text.length()]} は全体の幅。{@link #advance(String, FontStyle)} と同じ値になる
     * （描画とキャレットの位置が測定と一致する）。
     */
    public float[] positions(String text, FontStyle style) {
        float[] out = new float[text.length() + 1];
        StyleChain chain = chainFor(style);
        float size = style.fontSizePx();
        float x = 0;
        LoadedFace prevFace = null;
        int prevGlyph = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int n = Character.charCount(cp);
            if (isZeroWidth(cp)) {
                for (int k = 0; k < n; k++) out[i + k] = x;
                i += n;
                continue;
            }
            LoadedFace face = chain.pick(cp).face();
            int glyph = face.glyphId(cp);
            if (style.kerning() && face == prevFace) x += face.kerningEm(prevGlyph, glyph) * size;
            for (int k = 0; k < n; k++) out[i + k] = x;
            x += face.advanceEm(cp) * size + style.letterSpacingPx();
            prevFace = face;
            prevGlyph = glyph;
            i += n;
        }
        out[text.length()] = x;
        return out;
    }

    /**
     * {@code x}（文字列の左端からの px）に最も近い字の境目（char の位置、代用対の途中には置かない）。
     * {@code positions} は {@link #positions} の結果。
     */
    public static int nearestBoundary(String text, float[] positions, double x) {
        if (text == null || text.isEmpty() || x <= 0) return 0;
        int prev = 0;
        for (int i = 0; i < text.length(); ) {
            int next = text.offsetByCodePoints(i, 1);
            if (x < (positions[prev] + positions[next]) / 2.0) return prev;
            prev = next;
            i = next;
        }
        return text.length();
    }

    /** 先頭から {@code maxWidth} に収まる最長の部分（char 数）。詰めを含めて測る。 */
    @Override
    public int fitChars(String text, FontStyle style, float maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) return 0;
        float[] pos = positions(text, style);
        int fit = 0;
        for (int i = 0; i < text.length(); ) {
            int next = text.offsetByCodePoints(i, 1);
            float end = next < text.length() ? pos[next] : pos[text.length()];
            if (end > maxWidth) return fit;
            fit = next;
            i = next;
        }
        return text.length();
    }

    /** 1 字を描く書体。どの書体にも字形が無ければ第 1 書体（.notdef を描く）。 */
    public GlyphFace glyphFace(int codePoint, FontStyle style) {
        return chainFor(style).pick(codePoint);
    }

    @Override
    public float ascent(FontStyle style) {
        return chainFor(style).primary.face().ascent(style.fontSizePx());
    }

    @Override
    public float descent(FontStyle style) {
        return chainFor(style).primary.face().descent(style.fontSizePx());
    }

    @Override
    public float lineGap(FontStyle style) {
        return chainFor(style).primary.face().lineGap(style.fontSizePx());
    }

    @Override
    public float xHeight(FontStyle style) {
        return chainFor(style).primary.face().xHeight(style.fontSizePx());
    }

    /** 第 1 書体の面（測定・描画のテストで、どの書体が選ばれたかを確かめるため）。 */
    public LoadedFace primaryFace(FontStyle style) {
        return chainFor(style).primary.face();
    }

    private StyleChain chainFor(FontStyle style) {
        int weight = style.weight();
        int styleKey = (weight << 1) | (style.italic() ? 1 : 0);
        StyleChain chain = chains.get(styleKey);
        if (chain == null) {
            chain = chains.computeIfAbsent(styleKey, k -> buildChain(weight, style.italic()));
        }
        return chain;
    }

    private StyleChain buildChain(int weight, boolean italic) {
        List<GlyphFace> faces = new ArrayList<>();
        Set<SystemFonts.Face> seen = new LinkedHashSet<>();
        Set<String> tried = new LinkedHashSet<>();
        for (List<String> list : List.of(families, fallbacks)) {
            for (String family : list) {
                if (!tried.add(family.toLowerCase(java.util.Locale.ROOT))) continue;
                SystemFonts.Match m = SystemFonts.match(catalog.family(family), weight, italic);
                if (m == null || !seen.add(m.face())) continue;
                LoadedFace loaded = LoadedFace.open(m.face());
                if (loaded != null) faces.add(new GlyphFace(loaded, m.syntheticBold(), m.syntheticItalic()));
            }
        }
        if (faces.isEmpty()) throw new IllegalStateException("No usable font for " + families);
        return new StyleChain(faces);
    }

    /** ある太さ・斜体での、字ごとの書体の選び方。 */
    private static final class StyleChain {
        final GlyphFace[] faces;
        final GlyphFace primary;
        /** 字ごとに選んだ書体の番号 + 1（0 は未確認）。BMP は 256 字ずつのページ。 */
        final byte[][] choicePages = new byte[256][];
        final Map<Integer, Integer> choiceOther = new ConcurrentHashMap<>();

        StyleChain(List<GlyphFace> faces) {
            this.faces = faces.toArray(GlyphFace[]::new);
            this.primary = this.faces[0];
        }

        float advanceEm(int cp) {
            if (isZeroWidth(cp)) return 0;
            GlyphFace f = pick(cp);
            return f.face().advanceEm(cp);
        }

        GlyphFace pick(int cp) {
            if (cp < 0x10000) {
                byte[] page = choicePages[cp >>> 8];
                if (page == null) {
                    page = new byte[256];
                    choicePages[cp >>> 8] = page;
                }
                int c = page[cp & 0xFF] & 0xFF;
                if (c == 0) {
                    c = Math.min(255, choose(cp) + 1);
                    page[cp & 0xFF] = (byte) c;
                }
                return faces[c - 1];
            }
            return faces[choiceOther.computeIfAbsent(cp, this::choose)];
        }

        private int choose(int cp) {
            for (int i = 0; i < faces.length && i < 254; i++) {
                if (faces[i].face().hasGlyph(cp)) return i;
            }
            return 0;
        }
    }

    /** 送り幅を持たない文字（制御文字・ゼロ幅の書式文字）。Chrome も幅 0 で扱う。 */
    static boolean isZeroWidth(int cp) {
        return cp < 0x20 || (cp >= 0x7F && cp < 0xA0) || cp == 0x00AD
                || (cp >= 0x200B && cp <= 0x200F) || (cp >= 0x2028 && cp <= 0x202E) || cp == 0x2060 || cp == 0xFEFF;
    }
}
