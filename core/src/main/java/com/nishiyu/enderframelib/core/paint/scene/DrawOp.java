package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;

import java.util.Map;

/**
 * {@link SceneLayer} 自身の描画命令（M3b-4。計画書 §3.4）。
 *
 * <p>M3b-4a では、箱 1 つの描画（背景・枠線・影・部品・文字）を、層の整数の原点（{@code round(層の絶対位置)}）からの
 * float の座標の命令として作り、走査の側が毎フレームの位置・クリップ・親の錨を足して表示項目に組み立てる。
 * 命令はフレームの文脈（祖先のクリップ・親の錨の ID・スクロール量）を持たない。</p>
 */
public sealed interface DrawOp permits DrawOp.Item {
    DrawOp[] EMPTY = new DrawOp[0];

    /** 原点: 絶対座標（ビューポートの左上）。 */
    byte ORIGIN_ABSOLUTE = 0;
    /** 原点: 祖先の節（変形・不透明度を引き継ぐ錨）の整数の原点。 */
    byte ORIGIN_NODE = 1;
    /** 原点: 層自身の整数の原点。 */
    byte ORIGIN_SELF = 2;
    /** {@link Item#flags} の原点の種類の部分。 */
    int ORIGIN_MASK = 0x3;
    /** 座標の基準が層の原点の節（文字・インラインの断片。スクロールを含まない）。無ければ層自身。 */
    int BASE_ORIGIN_PARENT = 0x4;
    /** 箱の錨（組み立てで親の錨の ID を入れる）。 */
    int ANCHOR = 0x8;
    /** 文字色のアニメーションを祖先から継ぐ（組み立てで祖先から解く）。 */
    int INHERIT_TEXT_COLOR_ANIM = 0x10;

    /**
     * 描画項目 1 つぶんの命令。{@code x}・{@code y} は座標の基準（層自身か、{@link #BASE_ORIGIN_PARENT} なら原点の節）からの
     * float の座標（丸める前）。{@code flags} の下 2 ビットは、組み立てた項目の座標をどの原点からの相対にするか
     * （{@link #ORIGIN_ABSOLUTE} / {@link #ORIGIN_NODE} / {@link #ORIGIN_SELF}）。{@code meta} のクリップは、箱のクリップを継ぐ印か、
     * 部品が自分で狭めた分だけの連鎖（座標は同じ基準から）。
     */
    record Item(ElementType type, String id, String baseNodeId, long key,
                float x, float y, float w, float h, String text,
                int color, int bgColor, Map<String, String> events, int radius, float opacity,
                float tx, float ty, float sx, float sy, float rot,
                int transitionDuration, AnimationSpec animDef, ImageFitMode fitMode, RenderMeta meta,
                int flags) implements DrawOp {

        /** 原点の種類（{@link #ORIGIN_ABSOLUTE} / {@link #ORIGIN_NODE} / {@link #ORIGIN_SELF}）。 */
        public byte origin() {
            return (byte) (flags & ORIGIN_MASK);
        }

        /** メタだけを差し替えた命令。 */
        public Item withMeta(RenderMeta m) {
            return m == meta ? this : new Item(type, id, baseNodeId, key, x, y, w, h, text, color, bgColor, events, radius, opacity,
                    tx, ty, sx, sy, rot, transitionDuration, animDef, fitMode, m, flags);
        }

        /** 印を足した命令。 */
        public Item withFlags(int add) {
            return (flags | add) == flags ? this : new Item(type, id, baseNodeId, key, x, y, w, h, text, color, bgColor, events, radius,
                    opacity, tx, ty, sx, sy, rot, transitionDuration, animDef, fitMode, meta, flags | add);
        }
    }
}
