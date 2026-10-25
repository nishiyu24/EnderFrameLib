package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.Corners;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ShaderGradient;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import com.nishiyu.enderframelib.core.paint.model.RoundedBorderSpec;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link DisplayItem} の描画コマンド列を Java 標準の {@link Graphics2D} (AWT / Swing / BufferedImage)
 * に描画するスタンドアロンレンダラー。
 *
 * <p>Chrome / Web 標準の忠実度（Fidelity）に合わせてフォント、太さ、ベースライン、グラデーションを描画します。</p>
 */
public class AwtDisplayItemDrawer {

    private static final Map<String, BufferedImage> IMAGE_CACHE = new ConcurrentHashMap<>();

    /**
     * ディスプレイリストの全要素を指定された Graphics2D に描画します。
     */
    public static void render(Graphics2D g2d, List<DisplayItem> items) {
        if (items == null || items.isEmpty()) return;

        // 高品位なレンダリングヒントを設定 (Chrome のテキストレンダリングと同等の品質)
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        for (DisplayItem item : items) {
            drawItem(g2d, item);
        }
    }

    private static void drawItem(Graphics2D g2d, DisplayItem item) {
        AffineTransform oldTransform = g2d.getTransform();
        Shape oldClip = g2d.getClip();
        Composite oldComposite = g2d.getComposite();

        try {
            // 1. 不透明度の適用
            if (item.opacity() < 1.0f) {
                float currentAlpha = 1.0f;
                if (oldComposite instanceof AlphaComposite ac) {
                    currentAlpha = ac.getAlpha();
                }
                float newAlpha = Math.max(0.0f, Math.min(1.0f, currentAlpha * item.opacity()));
                g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, newAlpha));
            }

            // 2. 変形 (Transform)
            boolean hasTransform = item.translateX() != 0 || item.translateY() != 0
                    || item.scaleX() != 1.0f || item.scaleY() != 1.0f || item.rotate() != 0;
            if (hasTransform) {
                AffineTransform tx = new AffineTransform(oldTransform);
                tx.translate(item.translateX(), item.translateY());
                if (item.rotate() != 0 || item.scaleX() != 1.0f || item.scaleY() != 1.0f) {
                    float cx = item.x() + item.width() / 2.0f;
                    float cy = item.y() + item.height() / 2.0f;
                    tx.translate(cx, cy);
                    if (item.rotate() != 0) {
                        tx.rotate(Math.toRadians(item.rotate()));
                    }
                    if (item.scaleX() != 1.0f || item.scaleY() != 1.0f) {
                        tx.scale(item.scaleX(), item.scaleY());
                    }
                    tx.translate(-cx, -cy);
                }
                g2d.setTransform(tx);
            }

            // 3. クリップ領域
            RenderMeta meta = item.meta();
            if (meta != null && meta.hasClip()) {
                ClipRect c = meta.clipRect();
                float cw = Math.max(0, c.maxX() - c.minX());
                float ch = Math.max(0, c.maxY() - c.minY());
                g2d.clip(new Rectangle2D.Float(c.minX(), c.minY(), cw, ch));
            }

            // 4. 要素の描画
            renderElement(g2d, item, meta);

        } finally {
            g2d.setTransform(oldTransform);
            g2d.setClip(oldClip);
            g2d.setComposite(oldComposite);
        }
    }

    private static void renderElement(Graphics2D g2d, DisplayItem item, RenderMeta meta) {
        int x = item.x();
        int y = item.y();
        int w = item.width();
        int h = item.height();
        if (w <= 0 || h <= 0) return;

        // 背景描画（RECT, BUTTON, INPUT, ROUNDED_BORDER 共通）
        if (item.bgColor() != 0 || item.hints().shaderGradient() != null) {
            drawBackground(g2d, item, meta, x, y, w, h);
        }

        // 画像描画 (IMAGE)
        if (item.type() == ElementType.IMAGE && item.text() != null && !item.text().isEmpty()) {
            drawImage(g2d, item.text(), x, y, w, h);
        }

        // 枠線描画
        if (meta != null && meta.roundedBorder() != null) {
            drawRoundedBorder(g2d, meta.roundedBorder(), meta.borderRadii(), x, y, w, h);
        } else if (item.type() == ElementType.ROUNDED_BORDER && item.color() != 0) {
            drawSimpleBorder(g2d, item, meta, x, y, w, h);
        }

        // テキスト描画
        if (item.text() != null && !item.text().isEmpty() && item.type() != ElementType.IMAGE) {
            drawText(g2d, item, meta, x, y, w, h);
        }
    }

    private static void drawBackground(Graphics2D g2d, DisplayItem item, RenderMeta meta, int x, int y, int w, int h) {
        Shape shape = createShape(meta != null ? meta.borderRadii() : null, item.radius(), x, y, w, h);

        if (item.hints().shaderGradient() != null) {
            // シェーダーの 1 区間を画素ごとに計算する（範囲の外は捨てるので、区間を重ねて描けば 1 つのグラデーションになる）
            ShaderGradient segment = item.hints().shaderGradient();
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(Math.max(1, w), Math.max(1, h), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int py = 0; py < h; py++) {
                for (int px = 0; px < w; px++) {
                    Integer c = com.nishiyu.enderframelib.core.paint.ShaderGradientEmulator.colorOf(segment, x + px + 0.5f, y + py + 0.5f);
                    if (c != null) image.setRGB(px, py, c);
                }
            }
            java.awt.Shape oldClip = g2d.getClip();
            g2d.clip(shape);
            g2d.drawImage(image, x, y, null);
            g2d.setClip(oldClip);
            return;
        } else {
            g2d.setColor(toAwtColor(item.bgColor()));
        }

        g2d.fill(shape);
    }

    private static void drawRoundedBorder(Graphics2D g2d, RoundedBorderSpec rb, Corners radii, int x, int y, int w, int h) {
        if (rb.widthTop() == rb.widthRight() && rb.widthRight() == rb.widthBottom() && rb.widthBottom() == rb.widthLeft()
                && rb.colorTop() == rb.colorRight() && rb.colorRight() == rb.colorBottom() && rb.colorBottom() == rb.colorLeft()) {
            float strokeW = rb.widthTop();
            if (strokeW <= 0) return;
            g2d.setColor(toAwtColor(rb.colorTop()));
            g2d.setStroke(new BasicStroke(strokeW));
            float half = strokeW / 2.0f;
            Shape borderShape = createShape(radii, 0, x + half, y + half, w - strokeW, h - strokeW);
            g2d.draw(borderShape);
            return;
        }

        if (rb.widthTop() > 0 && rb.colorTop() != 0) {
            g2d.setColor(toAwtColor(rb.colorTop()));
            g2d.setStroke(new BasicStroke(rb.widthTop()));
            g2d.drawLine(x, y, x + w, y);
        }
        if (rb.widthBottom() > 0 && rb.colorBottom() != 0) {
            g2d.setColor(toAwtColor(rb.colorBottom()));
            g2d.setStroke(new BasicStroke(rb.widthBottom()));
            g2d.drawLine(x, y + h, x + w, y + h);
        }
        if (rb.widthLeft() > 0 && rb.colorLeft() != 0) {
            g2d.setColor(toAwtColor(rb.colorLeft()));
            g2d.setStroke(new BasicStroke(rb.widthLeft()));
            g2d.drawLine(x, y, x, y + h);
        }
        if (rb.widthRight() > 0 && rb.colorRight() != 0) {
            g2d.setColor(toAwtColor(rb.colorRight()));
            g2d.setStroke(new BasicStroke(rb.widthRight()));
            g2d.drawLine(x + w, y, x + w, y + h);
        }
    }

    private static void drawSimpleBorder(Graphics2D g2d, DisplayItem item, RenderMeta meta, int x, int y, int w, int h) {
        g2d.setColor(toAwtColor(item.color()));
        g2d.setStroke(new BasicStroke(1.0f));
        Shape shape = createShape(meta != null ? meta.borderRadii() : null, item.radius(), x + 0.5f, y + 0.5f, w - 1f, h - 1f);
        g2d.draw(shape);
    }

    private static void drawText(Graphics2D g2d, DisplayItem item, RenderMeta meta, int x, int y, int w, int h) {
        int color = item.color() != 0 ? item.color() : 0xFFFFFFFF;
        g2d.setColor(toAwtColor(color));

        // 1. フォントファミリの解決 (CSS の font-family に準拠)
        String family = "Segoe UI";
        if (item.events() != null && item.events().containsKey("font-family")) {
            String specified = item.events().get("font-family");
            if (specified != null && !specified.isBlank()) {
                String first = specified.split(",")[0].trim().replace("\"", "").replace("'", "");
                if (!first.isBlank() && !first.equalsIgnoreCase("sans-serif")) {
                    family = first;
                }
            }
        }

        // 2. 太さ (font-weight) と斜体 (font-style) の解決
        int fontStyle = Font.PLAIN;
        if (item.events() != null) {
            String weight = item.events().get("font-weight");
            boolean bold = false;
            if (weight != null) {
                if (weight.equalsIgnoreCase("bold") || weight.equalsIgnoreCase("bolder")) {
                    bold = true;
                } else {
                    try {
                        int num = Integer.parseInt(weight.trim());
                        if (num >= 600) bold = true;
                    } catch (NumberFormatException ignored) {}
                }
            }
            String fstyle = item.events().get("font-style");
            boolean italic = fstyle != null && fstyle.equalsIgnoreCase("italic");

            if (bold && italic) fontStyle = Font.BOLD | Font.ITALIC;
            else if (bold) fontStyle = Font.BOLD;
            else if (italic) fontStyle = Font.ITALIC;
        }

        float fontSize = (meta != null && meta.fontHeight() > 0) ? meta.fontHeight() : 16.0f;
        Font font = new Font(family, fontStyle, Math.max(1, Math.round(fontSize)));
        g2d.setFont(font);

        // 3. 正確なベースライン座標の適用 (Chrome / Web と同一のベースライン配置)
        float baselineY;
        if (meta != null && meta.baseline() > 0) {
            baselineY = y + meta.baseline();
        } else {
            LineMetrics lm = font.getLineMetrics(item.text(), g2d.getFontRenderContext());
            baselineY = y + lm.getAscent();
        }

        g2d.drawString(item.text(), (float) x, baselineY);
    }

    private static void drawImage(Graphics2D g2d, String src, int x, int y, int w, int h) {
        BufferedImage img = loadImage(src);
        if (img != null) {
            g2d.drawImage(img, x, y, w, h, null);
        }
    }

    private static BufferedImage loadImage(String src) {
        if (src == null || src.isBlank()) return null;
        return IMAGE_CACHE.computeIfAbsent(src, path -> {
            try {
                if (path.startsWith("http://") || path.startsWith("https://")) {
                    return ImageIO.read(new URL(path));
                }
                File f = new File(path);
                if (f.exists()) {
                    return ImageIO.read(f);
                }
                InputStream is = AwtDisplayItemDrawer.class.getResourceAsStream(path.startsWith("/") ? path : "/" + path);
                if (is != null) {
                    return ImageIO.read(is);
                }
            } catch (Exception ignored) {}
            return null;
        });
    }

    private static Shape createShape(Corners radii, int singleRadius, float x, float y, float w, float h) {
        if (radii != null && radii.hasRadius()) {
            Path2D.Float path = new Path2D.Float();
            float tl = radii.topLeft();
            float tr = radii.topRight();
            float br = radii.bottomRight();
            float bl = radii.bottomLeft();

            path.moveTo(x + tl, y);
            path.lineTo(x + w - tr, y);
            if (tr > 0) path.quadTo(x + w, y, x + w, y + tr);
            path.lineTo(x + w, y + h - br);
            if (br > 0) path.quadTo(x + w, y + h, x + w - br, y + h);
            path.lineTo(x + bl, y + h);
            if (bl > 0) path.quadTo(x, y + h, x, y + h - bl);
            path.lineTo(x, y + tl);
            if (tl > 0) path.quadTo(x, y, x + tl, y);
            path.closePath();
            return path;
        } else if (singleRadius > 0) {
            float diam = singleRadius * 2.0f;
            return new RoundRectangle2D.Float(x, y, w, h, diam, diam);
        } else {
            return new Rectangle2D.Float(x, y, w, h);
        }
    }

    public static Color toAwtColor(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return new Color(r, g, b, a);
    }
}
