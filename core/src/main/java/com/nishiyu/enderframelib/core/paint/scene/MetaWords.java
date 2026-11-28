package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.core.paint.model.CircleBorderSpec;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.Corners;
import com.nishiyu.enderframelib.core.paint.model.RenderHints;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import com.nishiyu.enderframelib.core.paint.model.RoundedBorderSpec;

import java.util.function.ToIntFunction;

/**
 * 命令のメタ（{@link RenderMeta}・{@link RenderHints}）を {@link DrawOps} の語に詰める・戻す（M3b-4c-1c。計画書 §1.6 決定 S）。
 *
 * <p>メタは箱ごとに作られ（付帯情報が持ち主の節の ID を持つ）、層の中の命令が共有する。層の列の末尾に、層の中で別のメタを 1 つずつ
 * 固定の 7 語（印・書体の高さ・ベースライン・行の高さ・変形の原点 2 つ・項目の数）と有る参照の語で並べる。付帯情報が持ち主の ID・その鍵・
 * 書体の名前・節の錨の原点の印だけなら付帯情報のオブジェクトを持たず、持ち主の ID の文字列（命令の持ち主の欄と同じ参照の欄）・鍵の 2 語・書体の名前の参照だけを持つ。
 * 鍵は ID から求め直さない（文字列の鍵はプロセス共有の {@code AtomTable} の番号で、求めると表に文字列を足して以後の番号がずれる）。
 * 戻すときは値の等しいメタを作る（同じ列を読む間は {@link DrawOps.Cursor} が 1 つのメタを 1 度だけ作る）。</p>
 */
final class MetaWords {
    private MetaWords() { }

    static final int CLIP = 1;
    static final int RADII = 1 << 1;
    static final int CIRCLE = 1 << 2;
    static final int ROUNDED = 1 << 3;
    /** 付帯情報のオブジェクトをそのまま参照で持つ。 */
    static final int HINTS = 1 << 4;
    /** 付帯情報を持ち主の ID（参照）・鍵（2 語）・書体の名前に分けて持つ。 */
    static final int OWNER = 1 << 5;
    static final int FONT = 1 << 6;
    static final int INTERACTIVE = 1 << 7;
    /** 付帯情報の {@code relativeOrigin}（節の錨が自分の位置に原点を移す）。 */
    static final int RELATIVE_ORIGIN = 1 << 8;

    /** メタ 1 つの語の数の上限（固定 7 語＋参照 4＋付帯情報 4）。 */
    static final int MAX_WORDS = 15;

    /** {@code m} を {@code a[p..]} に書き、書いた語の数を返す。参照は {@code ref} で参照の表の番号にする。 */
    static int write(RenderMeta m, int[] a, int p, ToIntFunction<Object> ref) {
        int start = p;
        int fl = m.interactiveState() ? INTERACTIVE : 0;
        a[p + 1] = Float.floatToRawIntBits(m.fontHeight());
        a[p + 2] = Float.floatToRawIntBits(m.baseline());
        a[p + 3] = Float.floatToRawIntBits(m.lineHeight());
        a[p + 4] = Float.floatToRawIntBits(m.originX());
        a[p + 5] = Float.floatToRawIntBits(m.originY());
        a[p + 6] = m.itemCount();
        p += 7;
        if (m.clipRect() != null) { fl |= CLIP; a[p++] = ref.applyAsInt(m.clipRect()); }
        if (m.borderRadii() != null) { fl |= RADII; a[p++] = ref.applyAsInt(m.borderRadii()); }
        if (m.circleBorder() != null) { fl |= CIRCLE; a[p++] = ref.applyAsInt(m.circleBorder()); }
        if (m.roundedBorder() != null) { fl |= ROUNDED; a[p++] = ref.applyAsInt(m.roundedBorder()); }
        RenderHints h = m.hints();
        if (h != RenderHints.NONE && !h.equals(RenderHints.NONE)) {
            if (ownerOnly(h)) {
                fl |= OWNER;
                a[p++] = ref.applyAsInt(h.parentId());
                a[p++] = (int) (h.parentKey() >>> 32);
                a[p++] = (int) h.parentKey();
                if (h.fontFamily() != null) { fl |= FONT; a[p++] = ref.applyAsInt(h.fontFamily()); }
                if (h.relativeOrigin()) fl |= RELATIVE_ORIGIN;
            } else {
                fl |= HINTS;
                a[p++] = ref.applyAsInt(h);
            }
        }
        a[start] = fl;
        return p - start;
    }

    /** {@code a[p..]} に書いたメタを戻す（値は書いたものと等しい）。 */
    static RenderMeta read(int[] a, int p, Object[] r) {
        int fl = a[p];
        float fontH = Float.intBitsToFloat(a[p + 1]);
        float baseline = Float.intBitsToFloat(a[p + 2]);
        float lineH = Float.intBitsToFloat(a[p + 3]);
        float ox = Float.intBitsToFloat(a[p + 4]);
        float oy = Float.intBitsToFloat(a[p + 5]);
        int items = a[p + 6];
        p += 7;
        ClipRect clip = (fl & CLIP) != 0 ? (ClipRect) r[a[p++]] : null;
        Corners radii = (fl & RADII) != 0 ? (Corners) r[a[p++]] : null;
        CircleBorderSpec circle = (fl & CIRCLE) != 0 ? (CircleBorderSpec) r[a[p++]] : null;
        RoundedBorderSpec rounded = (fl & ROUNDED) != 0 ? (RoundedBorderSpec) r[a[p++]] : null;
        RenderHints hints = RenderHints.NONE;
        if ((fl & HINTS) != 0) {
            hints = (RenderHints) r[a[p++]];
        } else if ((fl & OWNER) != 0) {
            String parentId = (String) r[a[p++]];
            long key = ((long) a[p] << 32) | (a[p + 1] & 0xFFFFFFFFL);
            p += 2;
            String font = (fl & FONT) != 0 ? (String) r[a[p++]] : null;
            hints = new RenderHints(parentId, key, null, false, 0f, 0f, 0f, null, null, false, 0, null, null, null, 0f,
                    (fl & RELATIVE_ORIGIN) != 0, font);
        }
        return new RenderMeta(clip, radii, circle, fontH, baseline, lineH, ox, oy, items, (fl & INTERACTIVE) != 0, rounded, hints);
    }

    /** 付帯情報が持ち主の ID・鍵・書体の名前・{@code relativeOrigin} のほかは既定値か（float は -0 も既定値でないとみなす）。 */
    private static boolean ownerOnly(RenderHints h) {
        return h.title() == null && !h.scrollable() && Float.floatToRawIntBits(h.scrollWidth()) == 0
                && Float.floatToRawIntBits(h.scrollHeight()) == 0 && Float.floatToRawIntBits(h.shadowBlur()) == 0
                && h.shadowShape() == null && h.shaderGradient() == null && !h.pointerEventsNone() && h.transitionDelayMs() == 0
                && h.transitionEasing() == null && h.transitionProperty() == null && h.layer() == null
                && Float.floatToRawIntBits(h.skew()) == 0;
    }
}
