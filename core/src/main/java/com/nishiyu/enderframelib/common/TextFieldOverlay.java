package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import com.nishiyu.enderframelib.core.text.font.OutlineFontFace;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import com.nishiyu.enderframelib.core.text.model.FontStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * テキスト欄（{@code <input>} / {@code <textarea>}）の編集の見た目（選択範囲・キャレット・IME の未確定文字）を描画の項目として作り、
 * 点からキャレットの位置を求める。
 *
 * <p>欄の文字はコアの描画（{@code TextRaster}）が、欄の項目の左上から {@link #PAD} だけ内側に、{@link #face} の書体で描く。
 * ここも同じ書体・同じ原点で位置を決めるので、描いた文字とキャレットがずれない。スタンドアロンのビュー（{@link StandaloneView}）と
 * Minecraft の MOD の画面が、この同じ計算を使う（M3a で MOD の旧レンダラの欄の描き方を撤去した）。
 */
public final class TextFieldOverlay {

    /** 欄の文字の原点（欄の項目の左上からの内側の余白。CSS px）。 */
    public static final float PAD = 4f;
    private static final int SELECTION = 0x663B82F6;

    private TextFieldOverlay() {}

    /** 欄の文字を描く書体（描画と同じ選び方: ホストの解決 → システムの書体）。輪郭の書体が無ければ null。 */
    public static OutlineFontFace face(FontResolver fonts, DisplayItem el) {
        String family = el.hints().fontFamily();
        if (family == null && el.events() != null) family = el.events().get("font-family");
        FontFace f = fonts != null ? fonts.resolve(family) : null;
        if (f instanceof OutlineFontFace o) return o;
        f = SystemFonts.system().resolve(family);
        return f instanceof OutlineFontFace o ? o : null;
    }

    /** 欄の文字の字形の指定（太さ・斜体・字間・カーニング）。 */
    public static FontStyle style(DisplayItem el) {
        Map<String, String> ev = el.events() != null ? el.events() : Map.of();
        int weight = LayoutMetrics.numericWeight(ev.get("font-weight"));
        String italic = ev.getOrDefault("font-style", "normal").trim();
        float spacing = 0f;
        try {
            spacing = Float.parseFloat(ev.getOrDefault("letter-spacing", "0"));
        } catch (NumberFormatException ignored) {
        }
        boolean kerning = !"none".equalsIgnoreCase(ev.getOrDefault("font-kerning", "auto").trim());
        return new FontStyle(fontPx(el), weight >= 600, !italic.equalsIgnoreCase("normal"), spacing, weight, kerning);
    }

    private static float fontPx(DisplayItem el) {
        RenderMeta meta = el.meta();
        return meta != null && meta.fontHeight() > 0f ? meta.fontHeight() : Math.max(1f, el.height());
    }

    private static float lineHeight(DisplayItem el) {
        return (el.meta() != null ? el.meta() : RenderMeta.EMPTY).resolvedLineHeight();
    }

    /** 点（{@code el} と同じ座標）の下の文字の位置（{@code text} の添字）。 */
    public static int caretAt(DisplayItem el, OutlineFontFace face, String text, boolean multiline, double px, double py) {
        FontStyle style = style(el);
        if (!multiline) return OutlineFontFace.nearestBoundary(text, face.positions(text, style), px - (el.x() + PAD));
        String[] lines = text.split("\n", -1);
        int row = Math.max(0, Math.min(lines.length - 1, (int) Math.floor((py - el.y() - PAD) / lineHeight(el))));
        int offset = 0;
        for (int i = 0; i < row; i++) offset += lines[i].length() + 1;
        return offset + OutlineFontFace.nearestBoundary(lines[row], face.positions(lines[row], style), px - (el.x() + PAD));
    }

    /** 選択も未確定文字も無い、キャレットだけの見た目（ホストが自分の編集の状態から作るとき）。 */
    public static List<DisplayItem> caret(String key, DisplayItem el, OutlineFontFace face, String text, boolean multiline, int caret) {
        return build(key, el, face, text, multiline, new TextEditing.State(Math.max(0, Math.min(text.length(), caret))));
    }

    /**
     * 選択範囲（行ごとの矩形）・キャレット・IME の未確定文字の項目。{@code key} は項目の ID の頭（欄の要素の ID）。
     *
     * @param el        欄の項目（{@code _input} / {@code _textarea}。絶対座標）
     * @param text      欄の今の値
     * @param multiline textarea なら true
     * @param st        キャレット・選択・未確定文字
     */
    public static List<DisplayItem> build(String key, DisplayItem el, OutlineFontFace face, String text, boolean multiline,
                                          TextEditing.State st) {
        FontStyle style = style(el);
        float glyphH = face.ascent(style) + face.descent(style);
        float lineH = multiline ? lineHeight(el) : el.height();
        float left = el.x() + PAD, right = el.x() + el.width() - PAD;
        int caretColor = (el.color() >>> 24) == 0 ? 0xFF000000 : (el.color() | 0xFF000000);
        List<DisplayItem> out = new ArrayList<>();
        String[] lines = multiline ? text.split("\n", -1) : new String[]{text};
        int offset = 0;
        for (int row = 0; row < lines.length; row++) {
            String line = lines[row];
            float top = multiline ? el.y() + PAD + row * lineH + (lineH - glyphH) / 2f : el.y() + (el.height() - glyphH) / 2f;
            if (top > el.y() + el.height()) break;
            float[] pos = face.positions(line, style);
            if (st.hasSelection() && st.start() <= offset + line.length() && st.end() >= offset) {
                int lo = Math.max(0, st.start() - offset), hi = Math.min(line.length(), st.end() - offset);
                float x0 = Math.min(right, left + pos[lo]);
                float x1 = Math.min(right, left + pos[hi]);
                // 行をまたぐ選択は、行末の改行も塗る
                if (st.end() > offset + line.length() && row < lines.length - 1) x1 = Math.min(right, x1 + 4f);
                if (x1 > x0) out.add(rect(key, "_sel" + row, x0, top, x1 - x0, glyphH, SELECTION));
            }
            if (st.caret >= offset && st.caret <= offset + line.length()) {
                int rel = st.caret - offset;
                float caretX = Math.min(right, left + pos[rel]);
                if (st.isComposing() && st.compositionText != null && !st.compositionText.isEmpty()) {
                    caretX = composition(out, key, el, st, line, rel, caretX, top, glyphH, right, style, face);
                }
                out.add(rect(key, "_caret", caretX, top, 1f, glyphH, caretColor));
            }
            offset += line.length() + 1;
        }
        return out;
    }

    /** IME の未確定文字を、キャレットの位置に重ねて描く項目。未確定文字の中のキャレットの x を返す。 */
    private static float composition(List<DisplayItem> out, String key, DisplayItem el, TextEditing.State st, String line, int rel,
                                     float x0, float top, float glyphH, float right, FontStyle style, OutlineFontFace face) {
        float[] pre = face.positions(st.compositionText, style);
        float preW = pre.length > 0 ? pre[pre.length - 1] : 0f;
        float x1 = Math.min(right, x0 + preW);
        int bg = (el.bgColor() >>> 24) == 0 ? 0xFFFFFFFF : el.bgColor();
        if (x0 < right) out.add(rect(key, "_ime_clr", x0, top - 2f, right - x0, glyphH + 4f, bg));
        if (x1 > x0) {
            out.add(rect(key, "_ime_bg", x0, top, x1 - x0, glyphH, 0x333B82F6));
            out.add(rect(key, "_ime_ul", x0, top + glyphH - 1f, x1 - x0, 2f, 0xFF3B82F6));
            out.add(text(key, "_ime_txt", x0, top, x1 - x0, glyphH, st.compositionText, el));
        }
        if (rel < line.length() && x1 < right) {
            out.add(text(key, "_ime_rem", x1, top, right - x1, glyphH, line.substring(rel), el));
        }
        int cur = Math.max(0, Math.min(st.compositionText.length(), st.compositionCursor));
        return Math.min(right, x0 + (cur < pre.length ? pre[cur] : preW));
    }

    private static DisplayItem text(String key, String part, float x, float y, float w, float h, String text, DisplayItem el) {
        int left = Math.round(x), top = Math.round(y);
        int color = (el.color() >>> 24) == 0 ? 0xFF000000 : (el.color() | 0xFF000000);
        return new DisplayItem(key + part, key, ElementType.TEXT, left, top,
                Math.max(1, Math.round(x + w) - left), Math.max(1, Math.round(y + h) - top), text, color, 0,
                Map.of(), 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, ImageFitMode.FILL, el.meta());
    }

    private static DisplayItem rect(String key, String part, float x, float y, float w, float h, int argb) {
        int left = Math.round(x), top = Math.round(y);
        return new DisplayItem(key + part, key, ElementType.RECT, left, top,
                Math.max(1, Math.round(x + w) - left), Math.max(1, Math.round(y + h) - top), "", 0, argb,
                Map.of(), 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, ImageFitMode.FILL, null);
    }
}
