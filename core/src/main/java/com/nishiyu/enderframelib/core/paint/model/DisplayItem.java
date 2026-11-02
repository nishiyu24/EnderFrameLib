package com.nishiyu.enderframelib.core.paint.model;

import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;

import java.util.Map;

/**
 * レンダリングパイプラインで使用されるUI要素モデル。
 * 構造化メタデータ（RenderMeta）および型付きプロパティを保持する不変レコード。
 */
public record DisplayItem(
        String id,
        String baseNodeId,
        ElementType type,
        int x,
        int y,
        int width,
        int height,
        String text,
        int color,
        int bgColor,
        Map<String, String> events,
        int radius,
        float opacity,
        float translateX,
        float translateY,
        float scaleX,
        float scaleY,
        float rotate,
        int transitionDuration,
        AnimationSpec animDef,
        ImageFitMode fitMode,
        RenderMeta meta
) {
    public String baseId() {
        return (baseNodeId != null && !baseNodeId.isEmpty()) ? baseNodeId : id;
    }

    /**
     * 描画 ID のうち持ち主の節の ID を除いた部分（背景なら {@code _bg}、節自身なら空）。
     * 節の ID の綴りに依存せず、{@link #baseId()} との差だけで決まる。
     */
    public String partSuffix() {
        String base = baseId();
        return id.startsWith(base) ? id.substring(base.length()) : "";
    }

    /** この項目が持ち主の節の部品 {@code suffix}（{@code _sb_v} など）か。 */
    public boolean isPart(String suffix) {
        String base = baseId();
        return id.length() == base.length() + suffix.length() && id.startsWith(base) && id.endsWith(suffix);
    }

    /** 型付きの付帯情報。メタが無ければ空。 */
    public RenderHints hints() {
        return meta != null ? meta.hints() : RenderHints.NONE;
    }

    public boolean hasHover() {
        return meta != null && meta.interactiveState();
    }

    public boolean hasPseudo() {
        return meta != null && meta.interactiveState();
    }

    public DisplayItem withText(String newText) {
        return new DisplayItem(id, baseNodeId, type, x, y, width, height, newText, color, bgColor, events, radius, opacity, translateX, translateY, scaleX, scaleY, rotate, transitionDuration, animDef, fitMode, meta);
    }

    public DisplayItem withTranslation(float newTranslateX, float newTranslateY) {
        return new DisplayItem(id, baseNodeId, type, x, y, width, height, text, color, bgColor, events, radius, opacity, newTranslateX, newTranslateY, scaleX, scaleY, rotate, transitionDuration, animDef, fitMode, meta);
    }
}
