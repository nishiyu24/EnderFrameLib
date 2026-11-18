package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.text.font.ColorBitmap;
import com.nishiyu.enderframelib.core.text.font.ColorLayers;
import com.nishiyu.enderframelib.core.text.font.GlyphBitmap;

import com.nishiyu.enderframelib.core.paint.AnimatedValues;

import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver.Resolved;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.BidiText;
import com.nishiyu.enderframelib.core.text.font.LoadedFace;
import com.nishiyu.enderframelib.core.text.font.OutlineFontFace;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ソフトウェア描画の文字（{@link SoftwareRenderBackend} の TEXT・入力欄の項目）: 書体の選択・下ごしらえ（{@code TextSetup}。P9b）・
 * 字形の塗り（揃った位置の高速経路・変形・縁取り・色付きの字形）。A8 で {@code SoftwareRenderBackend} から切り出した。
 *
 * <p>画素はすべて持ち主の {@code CpuRaster} とタイル実行（{@code paint}）を通して書く。このクラスは 1 フレームの間の作業表と、
 * 項目の実体ごとの下ごしらえの記憶だけを持つ。</p>
 */
final class TextRaster {
    private final SoftwareRenderBackend backend;

    TextRaster(SoftwareRenderBackend backend) {
        this.backend = backend;
    }

    // ------------------------------------------------------------------ 文字

    OutlineFontFace faceFor(DisplayItem el) {
        String family = el.hints().fontFamily();
        if (family == null && el.events() != null) family = el.events().get("font-family");
        FontFace f = backend.resources.fonts().resolve(family);
        if (f instanceof OutlineFontFace o) return o;
        f = SystemFonts.system().resolve(family);
        return f instanceof OutlineFontFace o ? o : null;
    }

    static FontStyle styleFor(DisplayItem el, float fontPx) {
        Map<String, String> ev = el.events() != null ? el.events() : Map.of();
        int weight = LayoutMetrics.numericWeight(ev.get("font-weight"));
        String style = ev.get("font-style");
        boolean italic = style != null && !style.trim().equalsIgnoreCase("normal");
        float letterSpacing = 0f;
        String ls = ev.get("letter-spacing");
        if (ls != null && !ls.isEmpty()) {
            try {
                letterSpacing = Float.parseFloat(ls);
            } catch (NumberFormatException ignored) {
            }
        }
        String fk = ev.get("font-kerning");
        boolean kerning = fk == null || !"none".equalsIgnoreCase(fk.trim());
        return new FontStyle(fontPx, weight >= 600, italic, letterSpacing, weight, kerning);
    }

    boolean skipPlaceholder(DisplayItem el) {
        if (!el.isPart("_ph")) return false;
        String nodeId = el.id().substring(0, el.id().length() - 3);
        DisplayItem input = backend.driver.itemById(nodeId + "_input");
        return input != null && input.text() != null && !input.text().isEmpty();
    }

    /**
     * 文字項目の「項目だけで決まる」下ごしらえ（書体・スタイル・見た目の順の文字列・送り位置・基線・縁取り・影・下線）。
     * 毎フレームの再描画で、変わらない項目のスタイル解釈（{@code events} の引き・数値の解析）と BiDi 並べ替えを繰り返さないよう、
     * 項目の実体（同一性）で覚える。色・姿勢・クリップ・不透明度は毎回渡されるので鍵に含めない。
     */
    static final class TextSetup {
        OutlineFontFace face;
        FontStyle style;
        String visual;
        float[] positions;
        float textX, baseline;
        Stroke stroke;
        boolean shadow, underline, lineThrough;
        float width;
    }
    static final int TEXT_SETUP_LIMIT = 8192;
    final java.util.IdentityHashMap<DisplayItem, TextSetup> textSetups = new java.util.IdentityHashMap<>();

    /** 下ごしらえを作る。描くものが無ければ null。 */
    TextSetup buildTextSetup(DisplayItem el, OutlineFontFace face, boolean field) {
        String text = el.text() == null ? "" : el.text().replace((char) 0xA0, ' ');
        if (!field && text.isEmpty()) return null;
        RenderMeta meta = el.meta();
        TextSetup s = new TextSetup();
        s.face = face;
        float fontPx = meta != null && meta.fontHeight() > 0f ? meta.fontHeight() : Math.max(1f, el.height());
        s.style = styleFor(el, fontPx);
        float ascent = face.ascent(s.style), descent = face.descent(s.style);
        boolean centers = field || el.isPart("_ph") || meta == null || meta.baseline() <= 0f;
        s.baseline = el.y() + (centers ? (el.height() + ascent - descent) / 2f : meta.baseline());
        s.textX = el.x() + (field ? 4f : 0f);
        s.stroke = textStroke(el);
        String shown = text;
        if (field || el.isPart("_ph")) {
            float available = Math.max(0f, el.width() - 8f);
            shown = text.substring(0, face.fitChars(text, s.style, available));
        }
        s.shadow = el.events() != null && "true".equals(el.events().get("text-shadow"));
        String deco = el.events() != null ? el.events().getOrDefault("text-decoration", "none") : "none";
        boolean decorated = !field && !el.isPart("_ph");
        s.underline = decorated && deco.contains("underline");
        s.lineThrough = decorated && deco.contains("line-through");
        s.width = el.width();
        if (!shown.isEmpty()) {
            s.visual = BidiText.toVisual(shown, null);
            s.positions = face.positions(s.visual, s.style);
        }
        return s;
    }

    void drawText(DisplayItem el, AnimatedValues anim, Resolved res, Clip clip, float opacity) {
        if (skipPlaceholder(el)) return;
        boolean field = el.type() == ElementType.INPUT || el.type() == ElementType.SELECT;
        OutlineFontFace face = faceFor(el);
        if (face == null) return;
        TextSetup s = null;
        boolean cacheable = SoftwareRenderBackend.textSetupReuse && !field && !el.isPart("_ph");
        if (cacheable) {
            s = textSetups.get(el);
            if (s != null && s.face != face) s = null; // 書体の登録が変わった（別の面になった）
        }
        if (s == null) {
            s = buildTextSetup(el, face, field);
            if (s == null) return;
            if (cacheable) {
                if (textSetups.size() >= TEXT_SETUP_LIMIT) textSetups.clear();
                textSetups.put(el, s);
            }
        }

        int alpha = (int) (((anim.color() >>> 24) & 0xFF));
        if (alpha < 4 && s.stroke == null) return;
        int argb = (alpha << 24) | (anim.color() & 0x00FFFFFF);

        if (s.visual != null) {
            if (alpha >= 4) {
                if (s.shadow) {
                    float off = s.style.fontSizePx() / 9f;
                    int shadowColor = (argb & 0xFF000000) | ((argb & 0xFCFCFC) >> 2);
                    drawGlyphs(s.visual, s.positions, face, s.style, s.textX + off, s.baseline + off, shadowColor, res.pose(), clip, opacity, true);
                }
                drawGlyphs(s.visual, s.positions, face, s.style, s.textX, s.baseline, argb, res.pose(), clip, opacity);
            }
            if (s.stroke != null) {
                drawGlyphStrokes(s.visual, s.positions, face, s.style, s.textX, s.baseline, s.stroke.argb(), s.stroke.width(), res.pose(), clip, opacity);
            }
        }
        if (alpha < 4) return; // 塗りが透明（縁取りだけの文字）なら、下線などは付けない

        if (s.underline || s.lineThrough) {
            LoadedFace primary = face.primaryFace(s.style);
            float size = s.style.fontSizePx();
            if (s.underline) {
                float top = s.baseline + primary.underlineOffset(size);
                backend.fillRect(res.pose(), s.textX, top, s.textX + s.width, top + primary.underlineThickness(size), argb, clip, opacity);
            }
            if (s.lineThrough) {
                float thick = primary.strikeoutThickness(size);
                float top = s.baseline - primary.strikeoutPosition(size) - thick / 2f;
                backend.fillRect(res.pose(), s.textX, top, s.textX + s.width, top + thick, argb, clip, opacity);
            }
        }
    }

    void drawTextarea(DisplayItem el, AnimatedValues anim, Resolved res, Clip clip, float opacity) {
        String full = el.text() == null ? "" : el.text();
        if (full.isEmpty()) return;
        OutlineFontFace face = faceFor(el);
        if (face == null) return;
        RenderMeta meta = el.meta() != null ? el.meta() : RenderMeta.EMPTY;
        FontStyle style = styleFor(el, meta.fontHeight() > 0f ? meta.fontHeight() : 16f);
        float lineHeight = meta.resolvedLineHeight();
        float ascent = face.ascent(style), descent = face.descent(style);
        int alpha = (anim.color() >>> 24) & 0xFF;
        if (alpha < 4) return;
        // 箱の外へは出さない
        List<ClipPart> parts = new ArrayList<>(clip.parts());
        backend.clipOwnerCount = 0; // 持ち主の記録は使わない
        backend.addClipPart(parts, new float[]{el.x(), el.y(), el.x() + el.width(), el.y() + el.height(), 0, 0, 0, 0}, null);
        Clip boxed = backend.finishClip(parts);
        String[] lines = full.replace("\r\n", "\n").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            float top = el.y() + 4f + i * lineHeight;
            if (top > el.y() + el.height()) break;
            if (lines[i].isEmpty()) continue;
            float baseline = top + (lineHeight + ascent - descent) / 2f;
            drawRun(lines[i], face, style, el.x() + 4f, baseline, anim.color(), false, res, boxed, opacity);
        }
    }

    /** 1 行の文字を描く。影があれば 1/9 em ずらして 1/4 の明るさで先に描く（GL 版と同じ）。 */
    void drawRun(String text, OutlineFontFace face, FontStyle style, float x, float baseline, int argb, boolean shadow,
                         Resolved res, Clip clip, float opacity) {
        drawRun(text, face, style, x, baseline, argb, shadow, res, clip, opacity, true, null);
    }

    /**
     * @param fill   塗りを描くか（縁取りだけの文字では false）
     * @param stroke 縁取り（幅は CSS px、色は非乗算 ARGB）。null なら縁取りなし。塗りの上に重ねる
     */
    void drawRun(String text, OutlineFontFace face, FontStyle style, float x, float baseline, int argb, boolean shadow,
                         Resolved res, Clip clip, float opacity, boolean fill, Stroke stroke) {
        if (text == null || text.isEmpty()) return;
        text = BidiText.toVisual(text, null);
        float[] positions = face.positions(text, style);
        if (!fill) {
            drawGlyphStrokes(text, positions, face, style, x, baseline, stroke.argb(), stroke.width(), res.pose(), clip, opacity);
            return;
        }
        if (shadow) {
            float off = style.fontSizePx() / 9f;
            int shadowColor = (argb & 0xFF000000) | ((argb & 0xFCFCFC) >> 2);
            drawGlyphs(text, positions, face, style, x + off, baseline + off, shadowColor, res.pose(), clip, opacity, true);
        }
        drawGlyphs(text, positions, face, style, x, baseline, argb, res.pose(), clip, opacity);
        if (stroke != null) {
            drawGlyphStrokes(text, positions, face, style, x, baseline, stroke.argb(), stroke.width(), res.pose(), clip, opacity);
        }
    }

    /** {@code text-stroke} イベント（{@code "<幅> <色の 16 進>"}）。無い・幅が 0 以下なら null。 */
    record Stroke(float width, int argb) {}

    static Stroke textStroke(DisplayItem el) {
        String v = el.events() != null ? el.events().get("text-stroke") : null;
        if (v == null) return null;
        int sp = v.indexOf(' ');
        if (sp < 0) return null;
        try {
            float w = Float.parseFloat(v.substring(0, sp));
            int color = (int) Long.parseLong(v.substring(sp + 1), 16);
            return w > 0f ? new Stroke(w, color) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 文字の縁取り。輪郭からの符号付き距離（SDF の字形）が {@code |d| <= 幅/2} の帯を、1 画素の傾斜でなめらかに塗る
     * （{@code -webkit-text-stroke} と同じ、輪郭の上に中心を置く）。幅は SDF の広がり（{@link LoadedFace#SDF_SPREAD}）で頭打ち。
     */
    void drawGlyphStrokes(String text, float[] positions, OutlineFontFace face, FontStyle style, float x, float baseline,
                                  int argb, float widthCss, Matrix2D pose, Clip clip, float opacity) {
        boolean aligned = ShapeGeometry.glyphAxisAligned(pose);
        float pixelSize = GlyphCache.quantize((float) (style.fontSizePx() * pose.meanScale()));
        if (pixelSize < 0.5f) return;
        float half = Math.min(widthCss * (float) pose.meanScale() / 2f, LoadedFace.SDF_SPREAD - 1.5f);
        if (half <= 0.01f) return;
        int pm = Pixmap.premultiply(argb);
        if ((argb >>> 24) < 4) return;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int n = Character.charCount(cp);
            if (cp <= 0x20 || cp == 0xA0 || Character.isWhitespace(cp)) {
                i += n;
                continue;
            }
            OutlineFontFace.GlyphFace gf = face.glyphFace(cp, style);
            float gx = x + positions[i];
            if (aligned) {
                double devX = pose.applyX(gx, baseline), devY = pose.applyY(gx, baseline);
                float originX = ShapeGeometry.glyphOriginX(devX), originY = ShapeGeometry.glyphOriginY(devY);
                GlyphBitmap sdf = backend.glyphCache.sdfGlyph(gf, cp, pixelSize, ShapeGeometry.glyphSubpixel(devX));
                if (!sdf.isEmpty() && !GpuAttempt.glyphStroke(backend.route.current(), sdf, originX, originY, half, argb, clip, opacity)) {
                    blitStrokeAligned(sdf, originX, originY, half, pm, clip, opacity);
                }
            } else {
                GlyphBitmap sdf = backend.glyphCache.sdfGlyph(gf, cp, pixelSize, 0);
                if (!sdf.isEmpty()) blitStrokeTransformed(sdf, gx, baseline, pixelSize / style.fontSizePx(), pose, half, pm, clip, opacity);
            }
            i += n;
        }
    }

    void blitStrokeAligned(GlyphBitmap g, float originX, float originY, float half, int pm, Clip clip, float opacity) {
        float bx = originX + g.left(), by = originY - g.top();
        backend.paint(Matrix2D.IDENTITY, bx, by, bx + g.width(), by + g.height(), clip, opacity, (lx, ly) -> {
            int col = (int) Math.floor(lx - bx), row = (int) Math.floor(ly - by);
            if (col < 0 || row < 0 || col >= g.width() || row >= g.height()) return 0;
            float cov = GlyphCache.strokeCoverage(g.coverageAt(col, row), half);
            return cov <= 0f ? 0 : SoftShapes.scalePremul(pm, cov);
        }, Fast.PURE);
    }

    void blitStrokeTransformed(GlyphBitmap g, float ox, float oy, float s, Matrix2D pose, float half, int pm, Clip clip, float opacity) {
        float[] lb = ShapeGeometry.glyphLocalBounds(g, ox, oy, s);
        backend.paint(pose, lb[0], lb[1], lb[2], lb[3], clip, opacity, (lx, ly) -> {
            float col = (lx - ox) * s - g.left() - 0.5f, row = (ly - oy) * s + g.top() - 0.5f;
            float cov = GlyphCache.strokeCoverage(GlyphCache.sampleRaw(g, col, row), half);
            return cov <= 0f ? 0 : SoftShapes.scalePremul(pm, cov);
        }, Fast.PURE);
    }

    void drawGlyphs(String text, float[] positions, OutlineFontFace face, FontStyle style, float x, float baseline,
                            int argb, Matrix2D pose, Clip clip, float opacity) {
        drawGlyphs(text, positions, face, style, x, baseline, argb, pose, clip, opacity, false);
    }

    /** {@code monochrome}: カラー字形でも 1 色（{@code argb}）で塗る（影）。 */
    void drawGlyphs(String text, float[] positions, OutlineFontFace face, FontStyle style, float x, float baseline,
                            int argb, Matrix2D pose, Clip clip, float opacity, boolean monochrome) {
        // 回転・せん断・縦横で違う拡縮が無ければ、字をデバイス画素の格子に揃えて置く（サブピクセル位置は 1/4 画素）
        boolean aligned = ShapeGeometry.glyphAxisAligned(pose);
        float pixelSize = GlyphCache.quantize((float) (style.fontSizePx() * pose.meanScale()));
        if (pixelSize < 0.5f) return;
        boolean light = GlyphCache.isLight(argb);
        int pm = Pixmap.premultiply(argb);
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int n = Character.charCount(cp);
            if (cp <= 0x20 || cp == 0xA0 || Character.isWhitespace(cp)) {
                i += n;
                continue;
            }
            OutlineFontFace.GlyphFace gf = face.glyphFace(cp, style);
            float gx = x + positions[i];
            if (!monochrome) {
                // COLR v1（グラデーション・変形・合成）は、描いた色付きビットマップを画像と同じ経路で置く
                ColorBitmap colorBitmap = gf.face().colorBitmap(cp, pixelSize, argb);
                if (colorBitmap != null) {
                    drawColorBitmapGlyph(colorBitmap, gx, baseline, pixelSize, aligned, style, pose, clip, opacity);
                    i += n;
                    continue;
                }
            }
            ColorLayers colorLayers = monochrome ? null : gf.face().colorLayers(cp);
            if (colorLayers != null) {
                drawColorGlyph(gf, colorLayers, gx, baseline, argb, pixelSize, aligned, style, pose, clip, opacity);
                i += n;
                continue;
            }
            if (aligned) {
                double devX = pose.applyX(gx, baseline), devY = pose.applyY(gx, baseline);
                float originX = ShapeGeometry.glyphOriginX(devX), originY = ShapeGeometry.glyphOriginY(devY);
                GlyphBitmap bmp = backend.glyphCache.glyph(gf, cp, pixelSize, ShapeGeometry.glyphSubpixel(devX), light);
                if (!bmp.isEmpty() && !GpuAttempt.glyph(backend.route.current(), bmp, originX, originY, argb, clip, opacity)) {
                    blitAligned(bmp, originX, originY, pm, clip, opacity);
                }
            } else {
                GlyphBitmap bmp = backend.glyphCache.glyph(gf, cp, pixelSize, 0, light);
                if (!bmp.isEmpty() && !GpuAttempt.glyphTransformed(backend.route.current(), bmp, gx, baseline, pixelSize / style.fontSizePx(), pose, argb, clip, opacity)) {
                    blitTransformed(bmp, gx, baseline, pixelSize / style.fontSizePx(), pose, pm, clip, opacity);
                }
            }
            i += n;
        }
    }

    static final float[] NO_RADII = new float[4];

    /** 色付きのビットマップの字形。画素の格子に載せるときは等倍、回転などの姿勢の下では双一次補間で貼る。 */
    void drawColorBitmapGlyph(ColorBitmap cb, float gx, float baseline, float pixelSize, boolean aligned,
                                      FontStyle style, Matrix2D pose, Clip clip, float opacity) {
        Pixmap pm = cb.pixmap();
        if (aligned) {
            double devX = pose.applyX(gx, baseline), devY = pose.applyY(gx, baseline);
            float bx = ShapeGeometry.glyphOriginX(devX) + cb.left(), by = ShapeGeometry.glyphOriginY(devY) - cb.top();
            drawGlyphPixmap(pm, bx, by, cb.width(), cb.height(), Matrix2D.IDENTITY, 1f, clip, opacity);
        } else {
            float s = pixelSize / style.fontSizePx();
            drawGlyphPixmap(pm, gx + cb.left() / s, baseline - cb.top() / s, cb.width() / s, cb.height() / s, pose,
                    (float) pose.meanScale(), clip, opacity);
        }
    }

    /** 事前乗算の画素を、局所の矩形 {@code (x0, y0, w, h)} に貼る（GPU が受ければ GPU、なければ CPU）。 */
    void drawGlyphPixmap(Pixmap pm, float x0, float y0, float w, float h, Matrix2D pose, float scale, Clip clip, float opacity) {
        if (!(w > 0) || !(h > 0)) return;
        float sx = pm.width() / w, sy = pm.height() / h;
        if (GpuAttempt.image(backend.route.current(), pose, pm, x0, y0, w, h, x0, y0, w, h, sx, sy, NO_RADII, NO_RADII, 1, scale, clip, opacity)) return;
        float m = 1f / Math.max(scale, 0.01f);
        backend.paint(pose, x0 - m, y0 - m, x0 + w + m, y0 + h + m, clip, opacity, (lx, ly) -> {
            float u = (lx - x0) * sx, v = (ly - y0) * sy;
            if (u < -0.5f || v < -0.5f || u > pm.width() + 0.5f || v > pm.height() + 0.5f) return 0;
            return SoftwareRenderBackend.bilinear(pm, u - 0.5f, v - 0.5f);
        }, Fast.PURE);
    }

    /** カラー字形（COLR）を、レイヤーごとの色で下から重ねて描く。レイヤーの被覆率は通常の字形と同じ経路（GL でも CPU でも）を通る。 */
    void drawColorGlyph(OutlineFontFace.GlyphFace gf, ColorLayers layers, float gx, float baseline, int textArgb,
                                float pixelSize, boolean aligned, FontStyle style, Matrix2D pose, Clip clip, float opacity) {
        double devX = pose.applyX(gx, baseline), devY = pose.applyY(gx, baseline);
        int sub = aligned ? ShapeGeometry.glyphSubpixel(devX) : 0;
        float originX = ShapeGeometry.glyphOriginX(devX), originY = ShapeGeometry.glyphOriginY(devY);
        for (int k = 0; k < layers.count(); k++) {
            int layerArgb = layers.foreground()[k] ? textArgb : layers.argb()[k];
            if ((layerArgb >>> 24) == 0) continue;
            GlyphBitmap bmp = backend.glyphCache.layerGlyph(gf, layers.glyphs()[k], pixelSize, sub, GlyphCache.isLight(layerArgb));
            if (bmp.isEmpty()) continue;
            int pm = Pixmap.premultiply(layerArgb);
            if (aligned) {
                if (!GpuAttempt.glyph(backend.route.current(), bmp, originX, originY, layerArgb, clip, opacity)) {
                    blitAligned(bmp, originX, originY, pm, clip, opacity);
                }
            } else if (!GpuAttempt.glyphTransformed(backend.route.current(), bmp, gx, baseline, pixelSize / style.fontSizePx(), pose, layerArgb, clip, opacity)) {
                blitTransformed(bmp, gx, baseline, pixelSize / style.fontSizePx(), pose, pm, clip, opacity);
            }
        }
    }

    /** デバイス画素の格子にそのまま置く（原点 {@code (originX, originY)}）。 */
    void blitAligned(GlyphBitmap g, float originX, float originY, int pm, Clip clip, float opacity) {
        float bx = originX + g.left(), by = originY - g.top();
        if (SoftwareRenderBackend.fastSolid && bx == Math.rint(bx) && by == Math.rint(by) && Math.abs(bx) < 1e6f && Math.abs(by) < 1e6f) {
            blitGridAligned(g, (int) bx, (int) by, pm, clip, opacity);
            return;
        }
        backend.paint(Matrix2D.IDENTITY, bx, by, bx + g.width(), by + g.height(), clip, opacity, (lx, ly) -> {
            int col = (int) Math.floor(lx - bx), row = (int) Math.floor(ly - by);
            if (col < 0 || row < 0 || col >= g.width() || row >= g.height()) return 0;
            int cov = g.coverageAt(col, row);
            return cov == 0 ? 0 : SoftShapes.scalePremul(pm, cov / 255f);
        }, Fast.PURE);
    }

    /** 被覆率 0..255 → 色（{@code covLutColor} の事前乗算を {@code cov/255} 倍したもの）。同じ色の字が続く間は再利用（命令が参照するので上書きしない）。 */
    int[] covLut;
    int covLutColor;

    /**
     * 字形の左上が画素の格子に載るとき（原点・左・上が整数）の貼り付け。恒等の姿勢の {@code paint} と同じ画素を同じ式で重ねるが、
     * 画素ごとの逆変換・シェーダー呼び出しをせず、被覆率→色は 256 項の表で引く。
     */
    void blitGridAligned(GlyphBitmap g, int bx, int by, int pm, Clip clip, float opacity) {
        if (opacity <= 0f || g.width() <= 0 || g.height() <= 0) return;
        int[] r = backend.paintRange(Matrix2D.IDENTITY, bx, by, bx + g.width(), by + g.height(), clip);
        if (r == null) return;
        if (covLut == null || covLutColor != pm) {
            int[] lut = new int[256];
            for (int i = 1; i < 256; i++) lut[i] = SoftShapes.scalePremul(pm, i / 255f);
            covLut = lut;
            covLutColor = pm;
        }
        PixelOps.GlyphOp op = backend.pool.acquireGlyph();
        op.px = backend.raster.target().pixels();
        op.w = backend.raster.targetWidth();
        op.x0 = r[0]; op.y0 = r[1]; op.x1 = r[2]; op.y1 = r[3];
        op.g = g; op.bx = bx; op.by = by; op.lut = covLut; op.clip = clip; op.clipped = !clip.parts().isEmpty(); op.opacity = opacity;
        backend.cpuOps++;
        backend.queue.submit(op, true);
    }

    /** 回転などの姿勢の下で、字形を双一次補間で貼る。{@code s} は 1 レイアウト単位あたりの字形の画素数。 */
    void blitTransformed(GlyphBitmap g, float ox, float oy, float s, Matrix2D pose, int pm, Clip clip, float opacity) {
        float[] lb = ShapeGeometry.glyphLocalBounds(g, ox, oy, s);
        backend.paint(pose, lb[0], lb[1], lb[2], lb[3], clip, opacity, (lx, ly) -> {
            float col = (lx - ox) * s - g.left() - 0.5f, row = (ly - oy) * s + g.top() - 0.5f;
            float cov = GlyphCache.sampleCoverage(g, col, row);
            return cov <= 0f ? 0 : SoftShapes.scalePremul(pm, cov);
        }, Fast.PURE);
    }
}
