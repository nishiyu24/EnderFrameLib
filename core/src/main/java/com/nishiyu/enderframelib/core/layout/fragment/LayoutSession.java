package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.util.HashMap;
import java.util.Map;

/**
 * 1 ビューのレイアウトの実行文脈（Engine v3 M2a）。書体の解決器・ビューポート・{@link LayoutCache} を持つ。
 * 状態はすべてこの実例に閉じ、プロセス共有の表・エポック・{@code ThreadLocal} は持たない。
 */
public final class LayoutSession {
    private final FontResolver fonts;
    private final LayoutCache cache = new LayoutCache();
    private final Map<String, FontFace> faces = new HashMap<>();
    private float viewportW, viewportH;
    private Map<String, com.nishiyu.enderframelib.core.css.model.PositionTryRule> positionTryRules = Map.of();

    public LayoutSession(FontResolver fonts) {
        this.fonts = fonts != null ? fonts : FontResolver.FALLBACK;
    }

    public LayoutCache cache() { return cache; }

    public float viewportW() { return viewportW; }

    public float viewportH() { return viewportH; }

    void viewport(float w, float h) {
        viewportW = w;
        viewportH = h;
    }

    /** シートの {@code @position-try} の規則（M2b-5。名前 → 規則。シートが変われば箱の木を作り直すので、キャッシュは古くならない）。 */
    public Map<String, com.nishiyu.enderframelib.core.css.model.PositionTryRule> positionTryRules() {
        return positionTryRules;
    }

    /** 駆動側がフレームの始めにシートの規則を渡す。 */
    public void positionTryRules(Map<String, com.nishiyu.enderframelib.core.css.model.PositionTryRule> rules) {
        positionTryRules = rules != null ? rules : Map.of();
    }

    /** {@code font-family} の書体（ファミリ名ごとに 1 度だけ引く）。 */
    public FontFace face(Style st) {
        String family = st.inherited.fontFamily;
        String key = family != null ? family : "";
        FontFace f = faces.get(key);
        if (f == null) {
            f = fonts.resolve(family);
            faces.put(key, f);
        }
        return f;
    }

    /**
     * 制約を作る。照合は {@code ==} の高速路つきの値等価（案 A）。M2a の実測で、ビュー内インターン（案 B）はヒット率・確保量が
     * 同じで時間が同等〜やや遅かった（表の探索の分）ので採らない（計画書 §3.3 B の判定基準 3）。
     */
    ConstraintSpace space(float availableWidth, float fixedWidth, float fixedHeight, float percentHeightBase, int flags) {
        return new ConstraintSpace(availableWidth, fixedWidth, fixedHeight, percentHeightBase, flags);
    }
}
