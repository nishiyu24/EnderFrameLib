package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer.ZLayers;
import com.nishiyu.enderframelib.client.renderer.WebFontManager.McFontResolver;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.client.gui.TextareaEditor.TextEditState;
import com.nishiyu.enderframelib.client.gui.WebTextInputHost;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.TextShaper;
import com.nishiyu.enderframelib.core.text.model.BitmapFontFace;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.regex.Pattern;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import com.nishiyu.enderframelib.core.text.font.OutlineFontFace;
import static com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer.addQuadRaw;
import static com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer.enableClipScissorFor2D;
import static com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer.snapCoordFor2D;

/** 文字・入力欄・テキストエリアを描く（{@link DisplayItemDrawer} の部品）。 */
public final class TextElementRenderer {
    private TextElementRenderer() {}

    // プリコンパイル済み Lua ハイライト用正規表現
    static final Pattern LUA_KEYWORDS_PATTERN = Pattern.compile("\\b(local|function|return|if|then|else|elseif|end|for|in|do|while|repeat|until|break|and|or|not)\\b");

    static final Pattern LUA_LITERALS_PATTERN = Pattern.compile("\\b(true|false|nil)\\b");

    static final Pattern LUA_BUILTINS_PATTERN = Pattern.compile("\\b(turtle|system|net|ui|math|string|table|os|print|sleep)\\b");

    public static String highlightCodeLine(String raw, String syntax) {
        if (raw == null || raw.isEmpty()) return "";
        if (!"lua".equalsIgnoreCase(syntax)) return raw;
        if (raw.trim().startsWith("--")) return "\u00a78" + raw + "\u00a7r";

        int commentIdx = raw.indexOf("--");
        String codePart = commentIdx >= 0 ? raw.substring(0, commentIdx) : raw;
        String commentPart = commentIdx >= 0 ? "\u00a78" + raw.substring(commentIdx) + "\u00a7r" : "";

        codePart = LUA_KEYWORDS_PATTERN.matcher(codePart).replaceAll("\u00a7d$1\u00a7r");
        codePart = LUA_LITERALS_PATTERN.matcher(codePart).replaceAll("\u00a76$1\u00a7r");
        codePart = LUA_BUILTINS_PATTERN.matcher(codePart).replaceAll("\u00a7b$1\u00a7r");
        return codePart + commentPart;
    }

    /** 測定に使った書体と同じものを引く（引き当ては McFontResolver が唯一の出所）。 */
    static Font resolveElementFont(DisplayItem el, Font defaultFont) {
        if (el.events() != null && el.events().containsKey("font-family")) {
            Font customFont = McFontResolver.fontFor(el.events().get("font-family"));
            if (customFont != null) return customFont;
        }
        return defaultFont;
    }

    static boolean shouldSkipPlaceholder(DisplayItem el, Map<String, DisplayItem> elementMap, String animSessionId) {
        if (!el.isPart("_ph")) return false;
        SessionContext session = ClientWebManager.getSession(animSessionId);
        String inputNodeId = el.id().substring(0, el.id().length() - 3);
        DisplayItem inputEl = elementMap.get(inputNodeId + "_input");
        if (inputEl != null && inputEl.text() != null && !inputEl.text().isEmpty()) {
            return true;
        }
        if (session != null && session.isFocusedBase(inputNodeId)) {
            String activeVal = WebTextInputHost.getActiveText(animSessionId);
            if (activeVal != null && !activeVal.isEmpty()) return true;
        }
        HtmlNode vRoot = session != null ? session.rootNode() : null;
        if (vRoot != null) {
            HtmlNode domNode = vRoot.getNodeById(inputNodeId);
            return domNode != null && !domNode.attrs.getOrDefault("value", "").isEmpty();
        }
        return false;
    }

    static void renderTextOrInputElement(
            DisplayItem el, RenderMeta meta, CssAnimator.AnimatedValues anim, boolean isFocused,
            Map<String, DisplayItem> elementMap, String animSessionId,
            PoseStack poseStack, MultiBufferSource buffer, Font font, boolean renderText, boolean is2DAudio,
            float finalOpacity, float zPos, int packedLight,
            float clipMinX, float clipMinY, float clipMaxX, float clipMaxY, float maxAllowedX, float maxAllowedY
    ) {
        if (shouldSkipPlaceholder(el, elementMap, animSessionId)) return;

        // &nbsp; は U+00A0 で届く。Minecraft のフォントはこの字形を空白の幅で持たないので、測定（McFontResolver）と同じく空白で描く
        String textToDraw = el.text() == null ? "" : el.text().replace(' ', ' ');
        float textX = el.x(), textY = el.y();

        int safeCursorPos = -1;
        if (el.type() == ElementType.INPUT && isFocused) {
            String activeVal = WebTextInputHost.getActiveText(animSessionId);
            if (activeVal != null) {
                textToDraw = activeVal;
                safeCursorPos = WebTextInputHost.getActiveCursorPosition(animSessionId);
            }
        }

        if (textY >= clipMaxY || textY + el.height() <= clipMinY) return;

        float fontH = meta != null ? meta.fontHeight() : el.height();
        OutlineFontFace outline = WebFontManager.outlineFaceFor(el);
        if (outline != null) {
            renderOutlineTextOrInput(el, meta, anim, outline, textToDraw, safeCursorPos, fontH, animSessionId,
                    poseStack, buffer, renderText, is2DAudio, finalOpacity, zPos, packedLight,
                    clipMinX, clipMinY, clipMaxX, clipMaxY, maxAllowedX, maxAllowedY);
            return;
        }
        // 書体の em（素のサイズ 9px）からの倍率。測定側（BitmapFontFace）と同じ式を使う。
        // 以前は測定が 16/12、描画が /12 という別々の換算で、しかも
        // 要素の高さを font-size の代わりに使っていた。
        float textScale = fontH / BitmapFontFace.MC_UNITS_PER_EM;
        float ascent = BitmapFontFace.MC_ASCENT * textScale;
        float descent = BitmapFontFace.MC_DESCENT * textScale;
        Matrix4f currentPose = poseStack.last().pose();
        float matScale = Math.abs(currentPose.m00());

        // ベースラインはレイアウトが決める。持たない要素（入力欄・プレースホルダ）だけ
        // 内容領域の中央に置く。以前は全要素を「高さ 8px の箱」として中央寄せしていた。
        boolean centersInBox = el.type() == ElementType.INPUT || el.type() == ElementType.SELECT
                || el.isPart("_ph") || meta == null || meta.baseline() <= 0;
        float baseline = centersInBox
                ? (el.height() + ascent - descent) / 2.0f
                : meta.baseline();
        if (el.type() == ElementType.INPUT || el.type() == ElementType.SELECT) {
            textX += 4.0f;
        }
        // Minecraft のフォントは ascent の上端を原点に描くので、ベースラインから戻す
        textY += baseline - ascent;

        if (el.type() != ElementType.INPUT && textToDraw.isEmpty()) return;
        if (!renderText || textX >= maxAllowedX || textY >= maxAllowedY) return;

        int newA = (int) (((anim.color() >>> 24) & 0xFF) * finalOpacity);
        // Minecraft の Font はアルファが 4 未満の色を不透明にして描くので、color: transparent が黒く出ないよう描かない
        if (newA < 4) return;

        boolean useShadow = el.events() != null && "true".equals(el.events().get("text-shadow"));
        String textDeco = el.events() != null ? el.events().getOrDefault("text-decoration", "none") : "none";
        int argbColor = (newA << 24) | (anim.color() & 0x00FFFFFF);

        boolean textScissor = enableClipScissorFor2D(currentPose, buffer, clipMinX, clipMinY, clipMaxX, clipMaxY, maxAllowedX, maxAllowedY, is2DAudio);
        poseStack.pushPose();
        try {
            if (el.type() == ElementType.INPUT || el.type() == ElementType.SELECT) {
                poseStack.translate(snapCoordFor2D(textX, matScale, is2DAudio), snapCoordFor2D(textY, matScale, is2DAudio), zPos);
                poseStack.scale(textScale, textScale, 1.0f);

                int scrollOffset = TextEditState.getInputScrollOffset(animSessionId, el.id());
                float maxAvailableWidth = (clipMaxX - textX) / textScale;
                float availableWidth = Math.max(0, Math.min((el.width() - 8.0f) / textScale, maxAvailableWidth));

                if (safeCursorPos >= 0) {
                    safeCursorPos = Math.min(textToDraw.length(), safeCursorPos);
                    if (safeCursorPos < scrollOffset) scrollOffset = safeCursorPos;
                    while (scrollOffset < safeCursorPos && font.width(textToDraw.substring(scrollOffset, safeCursorPos)) > availableWidth) {
                        scrollOffset++;
                    }
                    TextEditState.setInputScrollOffset(animSessionId, el.id(), scrollOffset);
                } else if (scrollOffset > textToDraw.length()) {
                    scrollOffset = textToDraw.length();
                }

                String visibleText = font.plainSubstrByWidth(textToDraw.substring(scrollOffset), (int) availableWidth);
                if (!visibleText.isEmpty()) {
                    font.drawInBatch(visibleText, 0, 0, argbColor, useShadow, poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, packedLight);
                }

                if (safeCursorPos >= 0 && safeCursorPos >= scrollOffset && (safeCursorPos - scrollOffset) <= visibleText.length()) {
                    if ((System.currentTimeMillis() / 500) % 2 == 0) {
                        float cursorX = font.width(visibleText.substring(0, safeCursorPos - scrollOffset));
                        float r = ((anim.color() >>> 16) & 0xFF) / 255.0f;
                        float g = ((anim.color() >>> 8) & 0xFF) / 255.0f;
                        float b = (anim.color() & 0xFF) / 255.0f;
                        addQuadRaw(buffer.getBuffer(RenderType.gui()), poseStack.last().pose(), cursorX, -1.0f, cursorX + 1.5f, 9.0f, r, g, b, newA / 255.0f, ZLayers.SUB_Z_OFFSET);
                    }
                }
            } else if (el.isPart("_ph")) {
                poseStack.translate(snapCoordFor2D(textX, matScale, is2DAudio), snapCoordFor2D(textY, matScale, is2DAudio), zPos);
                poseStack.scale(textScale, textScale, 1.0f);
                float availableWidth = Math.max(0, Math.min((el.width() - 8.0f) / textScale, (clipMaxX - textX) / textScale));
                String visibleText = font.plainSubstrByWidth(textToDraw, (int) availableWidth);
                if (!visibleText.isEmpty()) {
                    font.drawInBatch(visibleText, 0, 0, argbColor, useShadow, poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, packedLight);
                }
            } else if (!textToDraw.isEmpty()) {
                // 折り返し・省略・text-align はすべてレイアウト側で確定済み。
                // 以前はここで font.width を使って測り直し、「幅の 1.3 倍 + 4px」という
                // 独自の基準で省略を判定し、中央寄せも自前でやっていたため、
                // レイアウトが予約した行と実際に見える行が一致しなかった。
                boolean isBold = el.events() != null && com.nishiyu.enderframelib.core.layout.model.LayoutMetrics.isBoldWeight(el.events().get("font-weight"));
                String visibleText = isBold && !textToDraw.startsWith("§l") ? "§l" + textToDraw : textToDraw;
                float rawTextW = textScale > 0 ? el.width() / textScale : 0;

                float drawX = snapCoordFor2D(textX, matScale, is2DAudio);
                float drawY = snapCoordFor2D(textY, matScale, is2DAudio);

                poseStack.translate(drawX, drawY, zPos);
                poseStack.scale(textScale, textScale, 1.0f);
                font.drawInBatch(visibleText, 0, 0, argbColor, useShadow, poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, packedLight);

                if (textDeco.contains("underline") || textDeco.contains("line-through")) {
                    float r = ((anim.color() >>> 16) & 0xFF) / 255.0f;
                    float g = ((anim.color() >>> 8) & 0xFF) / 255.0f;
                    float b = (anim.color() & 0xFF) / 255.0f;
                    VertexConsumer lineBuilder = buffer.getBuffer(RenderType.gui());
                    if (textDeco.contains("underline")) {
                        addQuadRaw(lineBuilder, poseStack.last().pose(), 0, 8.5f, rawTextW, 9.5f, r, g, b, newA / 255.0f, ZLayers.SUB_Z_OFFSET);
                    }
                    if (textDeco.contains("line-through")) {
                        addQuadRaw(lineBuilder, poseStack.last().pose(), 0, 4.0f, rawTextW, 5.0f, r, g, b, newA / 255.0f, ZLayers.SUB_Z_OFFSET);
                    }
                }
            }
            if (textScissor && buffer instanceof MultiBufferSource.BufferSource bs) {
                bs.endBatch();
            }
        } finally {
            poseStack.popPose();
            if (textScissor) {
                RenderSystem.disableScissor();
            }
        }
    }

    /** textarea が行番号を出すか。クリック位置の解釈（TextareaEditor）と共有する。 */
    public static boolean showsLineNumbers(DisplayItem el) {
        if (el.events() == null) return false;
        String syntax = el.events().getOrDefault("data-syntax", "");
        return "lua".equalsIgnoreCase(syntax) || "true".equalsIgnoreCase(el.events().get("data-line-numbers"));
    }

    /** textarea の本文が始まる x 位置（行番号ガターの幅）。同じく編集側と共有する。 */
    public static float textareaGutterWidth(DisplayItem el, float textScale) {
        return showsLineNumbers(el) ? 28.0f * textScale : 4.0f;
    }

    static void renderTextareaElement(
            DisplayItem el, String sessionId, PoseStack poseStack, MultiBufferSource buffer, Font font,
            CssAnimator.AnimatedValues anim, float finalOpacity, float zPos,
            float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
            float maxAllowedX, float maxAllowedY, boolean is2D, int packedLight
    ) {
        String fullText = el.text() != null ? el.text() : "";
        String[] lines = fullText.replace("\r\n", "\n").split("\n", -1);
        OutlineFontFace outline = WebFontManager.outlineFaceFor(el);

        RenderMeta meta = el.meta();
        RenderMeta safeMeta = meta != null ? meta : RenderMeta.EMPTY;
        float fontH = safeMeta.fontHeight();
        Matrix4f mat = poseStack.last().pose();
        float matScale = Math.abs(mat.m00());
        float textScale = safeMeta.textScale();
        // 行送りは CSS の line-height をレイアウトが解決した値。
        // 以前は max(12 * textScale, 11.0f) という独自の式で、line-height を無視していた。
        float lineHeight = safeMeta.resolvedLineHeight();

        String syntax = el.events() != null ? el.events().getOrDefault("data-syntax", "") : "";
        boolean showLineNums = showsLineNumbers(el);
        float gutterW = textareaGutterWidth(el, textScale);

        boolean isFocused = ClientWebManager.getSession(sessionId) != null
                && ClientWebManager.getSession(sessionId).isActiveInput(el.id());
        int cursorIdx = Math.clamp(TextEditState.getTextareaCursor(sessionId, el.id(), fullText.length()), 0, fullText.length());

        int cursorRow = 0, cursorCol = 0, charAccum = 0;
        for (int i = 0; i < lines.length; i++) {
            int len = lines[i].length();
            if (cursorIdx <= charAccum + len) {
                cursorRow = i;
                cursorCol = cursorIdx - charAccum;
                break;
            }
            charAccum += len + 1;
        }

        int maxVisibleRows = Math.max(1, (int) ((el.height() - 8.0f) / lineHeight));
        int scrollRow = TextEditState.getTextareaScrollRow(sessionId, el.id());
        if (isFocused) {
            if (cursorRow < scrollRow) scrollRow = cursorRow;
            else if (cursorRow >= scrollRow + maxVisibleRows) scrollRow = cursorRow - maxVisibleRows + 1;
        }
        scrollRow = Math.clamp(scrollRow, 0, Math.max(0, lines.length - maxVisibleRows));
        TextEditState.setTextareaScrollRow(sessionId, el.id(), scrollRow);

        float taClipMinX = Math.max(clipMinX, el.x());
        float taClipMinY = Math.max(clipMinY, el.y());
        float taClipMaxX = Math.min(clipMaxX, el.x() + el.width());
        float taClipMaxY = Math.min(clipMaxY, el.y() + el.height());

        boolean taScissor = enableClipScissorFor2D(mat, buffer, taClipMinX, taClipMinY, taClipMaxX, taClipMaxY, maxAllowedX, maxAllowedY, is2D);
        try {
            VertexConsumer quadBuilder = buffer.getBuffer(RenderType.gui());
            if (showLineNums) {
                addQuadRaw(quadBuilder, mat, el.x(), el.y(), el.x() + gutterW - 3.0f, el.y() + el.height(), 0.05f, 0.05f, 0.09f, 0.45f * finalOpacity, zPos);
            }
            if (isFocused && cursorRow >= scrollRow && cursorRow < scrollRow + maxVisibleRows) {
                float activeRowY = el.y() + 4.0f + (cursorRow - scrollRow) * lineHeight;
                if (activeRowY + lineHeight >= taClipMinY && activeRowY <= taClipMaxY) {
                    addQuadRaw(quadBuilder, mat, el.x() + gutterW - 2.0f, activeRowY - 1.0f, el.x() + el.width() - 2.0f, activeRowY + lineHeight - 1.0f, 0.35f, 0.45f, 0.75f, 0.18f * finalOpacity, zPos + 0.0002f);
                }
            }

            int newA = (int) (((anim.color() >>> 24) & 0xFF) * finalOpacity);
            if (newA <= 0) return;
            int textColor = (newA << 24) | (anim.color() & 0x00FFFFFF);
            int gutterColor = (newA << 24) | 0x585B70;
            if (outline != null) {
                boolean worldClip = beginWorldTextClip(is2D, taClipMinX, taClipMinY, taClipMaxX, taClipMaxY, maxAllowedX, maxAllowedY);
                try {
                    drawOutlineTextareaRows(el, outline, lines, syntax, showLineNums, gutterW, lineHeight, scrollRow, maxVisibleRows,
                            isFocused, cursorRow, cursorCol, textColor, gutterColor, finalOpacity, poseStack, buffer, zPos,
                            taClipMinY, taClipMaxY, is2D, packedLight);
                } finally {
                    if (worldClip) OutlineTextRenderer.clearClip();
                }
                if (taScissor && buffer instanceof MultiBufferSource.BufferSource bs) {
                    bs.endBatch();
                }
                return;
            }
            float availW = Math.max(10.0f, (el.width() - gutterW - 6.0f) / textScale);

            for (int i = 0; i < maxVisibleRows; i++) {
                int rowIdx = scrollRow + i;
                if (rowIdx >= lines.length) break;

                float rowY = el.y() + 4.0f + i * lineHeight;
                if (rowY + lineHeight < taClipMinY || rowY > taClipMaxY) continue;

                poseStack.pushPose();
                try {
                    float snappedY = snapCoordFor2D(rowY, matScale, is2D);
                    if (showLineNums) {
                        poseStack.pushPose();
                        poseStack.translate(snapCoordFor2D(el.x() + 4.0f, matScale, is2D), snappedY, zPos + 0.0004f);
                        poseStack.scale(textScale, textScale, 1.0f);
                        font.drawInBatch(String.valueOf(rowIdx + 1), 0, 0, gutterColor, false, poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, packedLight);
                        poseStack.popPose();
                    }

                    String rawLine = lines[rowIdx];
                    String visibleRaw = font.plainSubstrByWidth(rawLine, (int) availW);
                    String colored = highlightCodeLine(visibleRaw, syntax);

                    poseStack.translate(snapCoordFor2D(el.x() + gutterW + 2.0f, matScale, is2D), snappedY, zPos + 0.0004f);
                    poseStack.scale(textScale, textScale, 1.0f);
                    if (!colored.isEmpty()) {
                        font.drawInBatch(colored, 0, 0, textColor, false, poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, packedLight);
                    }

                    if (isFocused && rowIdx == cursorRow && (System.currentTimeMillis() / 500) % 2 == 0) {
                        int safeCol = Math.clamp(cursorCol, 0, visibleRaw.length());
                        float curX = font.width(visibleRaw.substring(0, safeCol));
                        addQuadRaw(buffer.getBuffer(RenderType.gui()), poseStack.last().pose(), curX, -1.0f, curX + 1.5f, 9.0f, 0.54f, 0.71f, 0.98f, finalOpacity, 0.0006f);
                    }
                } finally {
                    poseStack.popPose();
                }
            }
            if (taScissor && buffer instanceof MultiBufferSource.BufferSource bs) {
                bs.endBatch();
            }
        } finally {
            if (taScissor) {
                RenderSystem.disableScissor();
            }
        }
    }

    /** ワールド内（ブロックの画面）の文字をラスタライズする倍率。見る距離が決まらないので固定の値にする。 */
    static final float WORLD_TEXT_SCALE = 3.0f;

    /** 1 CSS px が何デバイスピクセルか。GUI では変換行列の倍率 × 画面の倍率、ワールド内では固定。 */
    static float deviceScaleFor(Matrix4f pose, boolean is2D) {
        if (!is2D) return WORLD_TEXT_SCALE;
        return (float) (Math.abs(pose.m00()) * Minecraft.getInstance().getWindow().getGuiScale());
    }

    /**
     * システムの書体で文字・入力欄・プレースホルダーを描く。位置と幅は測定（{@link OutlineFontFace}）と同じ値で決める。
     * ベースラインはレイアウトが決めた値、持たない要素（入力欄など）は内容領域の中央に置く。
     */
    static void renderOutlineTextOrInput(
            DisplayItem el, RenderMeta meta, CssAnimator.AnimatedValues anim, OutlineFontFace face,
            String textToDraw, int cursorPos, float fontH, String animSessionId,
            PoseStack poseStack, MultiBufferSource buffer, boolean renderText, boolean is2D,
            float finalOpacity, float zPos, int packedLight,
            float clipMinX, float clipMinY, float clipMaxX, float clipMaxY, float maxAllowedX, float maxAllowedY
    ) {
        FontStyle style = WebFontManager.fontStyleFor(el, fontH);
        float ascent = face.ascent(style);
        float descent = face.descent(style);
        boolean isField = el.type() == ElementType.INPUT || el.type() == ElementType.SELECT;
        boolean centersInBox = isField || el.isPart("_ph") || meta == null || meta.baseline() <= 0;
        float baseline = el.y() + (centersInBox ? (el.height() + ascent - descent) / 2.0f : meta.baseline());
        float textX = el.x() + (isField ? 4.0f : 0.0f);

        if (el.type() != ElementType.INPUT && textToDraw.isEmpty()) return;
        if (!renderText || textX >= maxAllowedX || baseline - ascent >= maxAllowedY) return;

        int newA = (int) (((anim.color() >>> 24) & 0xFF) * finalOpacity);
        if (newA < 4) return;
        boolean useShadow = el.events() != null && "true".equals(el.events().get("text-shadow"));
        String textDeco = el.events() != null ? el.events().getOrDefault("text-decoration", "none") : "none";
        int argb = (newA << 24) | (anim.color() & 0x00FFFFFF);
        float r = ((anim.color() >>> 16) & 0xFF) / 255.0f;
        float g = ((anim.color() >>> 8) & 0xFF) / 255.0f;
        float b = (anim.color() & 0xFF) / 255.0f;

        Matrix4f outer = poseStack.last().pose();
        float deviceScale = deviceScaleFor(outer, is2D);
        boolean textScissor = enableClipScissorFor2D(outer, buffer, clipMinX, clipMinY, clipMaxX, clipMaxY, maxAllowedX, maxAllowedY, is2D);
        boolean worldClip = beginWorldTextClip(is2D, clipMinX, clipMinY, clipMaxX, clipMaxY, maxAllowedX, maxAllowedY);
        poseStack.pushPose();
        try {
            poseStack.translate(0, 0, zPos);
            Matrix4f pose = poseStack.last().pose();
            if (isField) {
                int scrollOffset = TextEditState.getInputScrollOffset(animSessionId, el.id());
                float available = Math.max(0, Math.min(el.width() - 8.0f, clipMaxX - textX));
                if (cursorPos >= 0) {
                    cursorPos = Math.min(textToDraw.length(), cursorPos);
                    if (cursorPos < scrollOffset) scrollOffset = cursorPos;
                    while (scrollOffset < cursorPos && face.advance(textToDraw.substring(scrollOffset, cursorPos), style) > available) {
                        scrollOffset++;
                    }
                    TextEditState.setInputScrollOffset(animSessionId, el.id(), scrollOffset);
                } else if (scrollOffset > textToDraw.length()) {
                    scrollOffset = textToDraw.length();
                }
                String rest = textToDraw.substring(Math.min(scrollOffset, textToDraw.length()));
                String visible = rest.substring(0, face.fitChars(rest, style, available));
                OutlineTextRenderer.draw(visible, face, style, textX, baseline, argb, useShadow, pose, buffer, deviceScale, is2D, packedLight);
                if (cursorPos >= scrollOffset && cursorPos - scrollOffset <= visible.length()
                        && (System.currentTimeMillis() / 500) % 2 == 0) {
                    float cx = textX + face.advance(visible.substring(0, cursorPos - scrollOffset), style);
                    addQuadRaw(buffer.getBuffer(RenderType.gui()), pose, cx, baseline - ascent, cx + 1.0f, baseline + descent,
                            r, g, b, newA / 255.0f, ZLayers.SUB_Z_OFFSET);
                }
            } else if (el.isPart("_ph")) {
                float available = Math.max(0, Math.min(el.width() - 8.0f, clipMaxX - textX));
                String visible = textToDraw.substring(0, face.fitChars(textToDraw, style, available));
                OutlineTextRenderer.draw(visible, face, style, textX, baseline, argb, useShadow, pose, buffer, deviceScale, is2D, packedLight);
            } else {
                // 折り返し・省略・text-align はすべてレイアウト側で確定済み（描画は測り直さない）
                OutlineTextRenderer.draw(textToDraw, face, style, textX, baseline, argb, useShadow, pose, buffer, deviceScale, is2D, packedLight);
                if (textDeco.contains("underline") || textDeco.contains("line-through")) {
                    var primary = face.primaryFace(style);
                    float size = style.fontSizePx();
                    VertexConsumer lineBuilder = buffer.getBuffer(RenderType.gui());
                    if (textDeco.contains("underline")) {
                        float top = snapCoordFor2D(baseline + primary.underlineOffset(size), Math.abs(outer.m00()), is2D);
                        addQuadRaw(lineBuilder, pose, textX, top, textX + el.width(), top + primary.underlineThickness(size),
                                r, g, b, newA / 255.0f, ZLayers.SUB_Z_OFFSET);
                    }
                    if (textDeco.contains("line-through")) {
                        float thick = primary.strikeoutThickness(size);
                        float top = snapCoordFor2D(baseline - primary.strikeoutPosition(size) - thick / 2, Math.abs(outer.m00()), is2D);
                        addQuadRaw(lineBuilder, pose, textX, top, textX + el.width(), top + thick,
                                r, g, b, newA / 255.0f, ZLayers.SUB_Z_OFFSET);
                    }
                }
            }
            if (textScissor && buffer instanceof MultiBufferSource.BufferSource bs) {
                bs.endBatch();
            }
        } finally {
            poseStack.popPose();
            if (worldClip) OutlineTextRenderer.clearClip();
            if (textScissor) {
                RenderSystem.disableScissor();
            }
        }
    }

    /**
     * シザーが使えない世界内の面で、祖先のクリップにかかる字を {@link OutlineTextRenderer} で板ごと切る準備をする。
     * クリップが面全体なら何もしない。切ったら {@link OutlineTextRenderer#clearClip} で戻すこと。
     */
    static boolean beginWorldTextClip(boolean is2D, float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                                      float maxAllowedX, float maxAllowedY) {
        if (is2D) return false;
        if (clipMinX <= 0.5f && clipMinY <= 0.5f && clipMaxX >= maxAllowedX - 0.5f && clipMaxY >= maxAllowedY - 0.5f) return false;
        OutlineTextRenderer.setClip(clipMinX, clipMinY, clipMaxX, clipMaxY);
        return true;
    }

    /** テキストエリアの行をシステムの書体で描く。行の中の文字の位置は half-leading の分け方（{@link TextShaper#leadingAbove}）で決める。 */
    static void drawOutlineTextareaRows(
            DisplayItem el, OutlineFontFace face, String[] lines, String syntax, boolean showLineNums, float gutterW,
            float lineHeight, int scrollRow, int maxVisibleRows, boolean isFocused, int cursorRow, int cursorCol,
            int textColor, int gutterColor, float finalOpacity, PoseStack poseStack, MultiBufferSource buffer, float zPos,
            float taClipMinY, float taClipMaxY, boolean is2D, int packedLight
    ) {
        RenderMeta meta = el.meta() != null ? el.meta() : RenderMeta.EMPTY;
        FontStyle style = WebFontManager.fontStyleFor(el, meta.fontHeight());
        float ascent = face.ascent(style);
        float descent = face.descent(style);
        float halfLeading = TextShaper.leadingAbove(lineHeight, ascent, descent);
        float availW = Math.max(10.0f, el.width() - gutterW - 6.0f);

        poseStack.pushPose();
        try {
            poseStack.translate(0, 0, zPos + 0.0004f);
            Matrix4f pose = poseStack.last().pose();
            float deviceScale = deviceScaleFor(pose, is2D);
            for (int i = 0; i < maxVisibleRows; i++) {
                int rowIdx = scrollRow + i;
                if (rowIdx >= lines.length) break;
                float rowY = el.y() + 4.0f + i * lineHeight;
                if (rowY + lineHeight < taClipMinY || rowY > taClipMaxY) continue;
                float baseline = rowY + halfLeading + ascent;

                if (showLineNums) {
                    OutlineTextRenderer.draw(String.valueOf(rowIdx + 1), face, style, el.x() + 4.0f, baseline, gutterColor,
                            false, pose, buffer, deviceScale, is2D, packedLight);
                }
                String rawLine = lines[rowIdx];
                String visibleRaw = rawLine.substring(0, face.fitChars(rawLine, style, availW));
                float textX = el.x() + gutterW + 2.0f;
                OutlineTextRenderer.drawFormatted(highlightCodeLine(visibleRaw, syntax), face, style, textX, baseline, textColor,
                        pose, buffer, deviceScale, is2D, packedLight);

                if (isFocused && rowIdx == cursorRow && (System.currentTimeMillis() / 500) % 2 == 0) {
                    int safeCol = Math.clamp(cursorCol, 0, visibleRaw.length());
                    float curX = textX + face.advance(visibleRaw.substring(0, safeCol), style);
                    addQuadRaw(buffer.getBuffer(RenderType.gui()), pose, curX, baseline - ascent, curX + 1.0f, baseline + descent,
                            0.54f, 0.71f, 0.98f, finalOpacity, 0.0002f);
                }
            }
        } finally {
            poseStack.popPose();
        }
    }
}
