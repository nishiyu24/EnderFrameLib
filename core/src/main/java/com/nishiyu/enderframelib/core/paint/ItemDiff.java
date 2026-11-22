package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.BlendMode;
import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;

/**
 * 「前のフレームの項目」と「いまの項目」の比べ方を 1 か所に集めたもの（B9 の M1 / C6）。
 * {@link DisplayItem} は完全に不変であり、変化の判定は実体の同一性（{@code ==}）1 つに一本化している。
 * ここ以外で項目同士を {@code ==} / {@code !=} で比べてはならない（{@code ItemDiffRatchetTest}）。
 */
public final class ItemDiff {
    private ItemDiff() {}

    /** 前回から何も変わっていない項目か（項目は不変なので実体の同一性だけを見る）。 */
    public static boolean unchanged(DisplayItem prev, DisplayItem cur) {
        return prev == cur;
    }

    /** {@link #unchanged} の否定。 */
    public static boolean changed(DisplayItem prev, DisplayItem cur) {
        return prev != cur;
    }

    /** 同じ位置・鍵で、種類も同じか（差分の対応づけの前提）。 */
    public static boolean sameKeyAndType(DisplayItem a, DisplayItem b) {
        return a.key() == b.key() && a.type() == b.type();
    }

    /** スクロールの中身の項目が、前回から平行移動しているか。 */
    public static boolean translationChanged(DisplayItem prev, DisplayItem cur) {
        return cur.translateX() != prev.translateX() || cur.translateY() != prev.translateY();
    }

    /** グループ・フィルタ・マスク・ブレンド・クリップなど、層を要する効果を持つ NODE か。 */
    public static boolean hasFxLayer(DisplayItem el) {
        return el.type() == ElementType.NODE && hasFx(el.hints().layer());
    }

    /** 層を要する効果が 1 つでもあるか。 */
    public static boolean hasFx(LayerSpec spec) {
        return spec != null && (!spec.filters().isEmpty() || spec.masked() || spec.blend() != BlendMode.NORMAL || spec.isolate()
                || spec.clip() != null || spec.hasBackdrop());
    }

    /** 索引・親子・グループの錨・スクロール範囲が、項目から読むもの（種類・親・層）が同じか。 */
    public static boolean sameStructure(DisplayItem a, DisplayItem b) {
        if (a.type() != b.type()) return false;
        long pka = a.hints().parentKey(), pkb = b.hints().parentKey();
        if (pka != 0L || pkb != 0L) {
            if (pka != pkb) return false;
        } else {
            String pa = a.hints().parentId(), pb = b.hints().parentId();
            if (pa != pb && (pa == null || !pa.equals(pb))) return false;
        }
        if (a.type() == ElementType.NODE) {
            LayerSpec la = a.hints().layer(), lb = b.hints().layer();
            boolean fa = hasFx(la), fb = hasFx(lb);
            if (fa != fb) return false;
            if (fa && la.span() != lb.span()) return false;
        }
        return true;
    }

    /** 姿勢（位置・寸法・変形・不透明度・原点・層）が同じで、どちらにもアニメーション指定が無いか。 */
    public static boolean sameGeometryWithoutTransition(DisplayItem a, DisplayItem b) {
        if (a.type() != b.type()
                || a.x() != b.x() || a.y() != b.y() || a.width() != b.width() || a.height() != b.height()
                || Float.compare(a.translateX(), b.translateX()) != 0
                || Float.compare(a.translateY(), b.translateY()) != 0
                || Float.compare(a.scaleX(), b.scaleX()) != 0
                || Float.compare(a.scaleY(), b.scaleY()) != 0
                || Float.compare(a.rotate(), b.rotate()) != 0
                || Float.compare(a.opacity(), b.opacity()) != 0) {
            return false;
        }
        var aa = a.animDef();
        var ba = b.animDef();
        if (aa != null && !AnimationSpec.NONE.equals(aa)) return false;
        if (ba != null && !AnimationSpec.NONE.equals(ba)) return false;
        RenderMeta ma = a.meta(), mb = b.meta();
        float oxa = ma != null ? ma.originX() : 0.5f, oxb = mb != null ? mb.originX() : 0.5f;
        float oya = ma != null ? ma.originY() : 0.5f, oyb = mb != null ? mb.originY() : 0.5f;
        if (Float.compare(oxa, oxb) != 0 || Float.compare(oya, oyb) != 0) return false;
        var ha = a.hints();
        var hb = b.hints();
        if (ha.relativeOrigin() != hb.relativeOrigin() || Float.compare(ha.skew(), hb.skew()) != 0) return false;
        return java.util.Objects.equals(ha.layer(), hb.layer());
    }
}
