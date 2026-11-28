package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import com.nishiyu.enderframelib.core.paint.scene.DrawOps;
import com.nishiyu.enderframelib.core.paint.scene.SceneLayer;
import com.nishiyu.enderframelib.core.paint.scene.DrawOpsBuilder;

import java.util.List;
import java.util.Map;

/**
 * 項目の生成器（{@link BoxItemPainter} ほか）が積む描画命令の列（M3b-4a。M3b-4c-1b から詰めた形 {@link DrawOpsBuilder} に書く）。
 *
 * <p>2 つの使い方がある。</p>
 * <ul>
 *   <li><b>層の局所座標</b>（{@link #beginLocal}）: 箱 1 つの描画。座標は層の整数の原点 {@code round(層の絶対位置)} からの float で、
 *       原点の種類（{@link DrawOps#ORIGIN_SELF} など）だけを命令に記録する。生成器には箱のクリップとして null を渡し（クリップの無い
 *       メタは箱のクリップを継ぐ）、部品が狭めたクリップは連鎖だけを持つ。組み立て（{@link OpComposer}）が毎フレームの原点・
 *       箱のクリップ・親の錨を足す。</li>
 *   <li><b>原点からの座標</b>（{@link #beginFinal}）: 走査の側が自分で作る項目（スクロールバー・キャンバスの背景など）。
 *       座標は {@link #setOrigin} で決めた整数の原点からで、組み立ては丸めるだけ。</li>
 * </ul>
 */
final class OpList {
    /** 詰めた命令の組み立て器（使い回す。層の命令は {@link #freeze} で不変にする）。 */
    final DrawOpsBuilder ops = new DrawOpsBuilder();

    /**
     * 文字色のアニメーションを祖先から継ぐ印（{@link #deferred} のとき、生成器が文字の {@code animDef} の代わりに渡す。
     * {@link PaintSupport#addElement} が {@link DrawOps#INHERIT_TEXT_COLOR_ANIM} の印に替える）。中身は空のアニメーションと同じ。
     */
    static final AnimationSpec TEXT_COLOR_ANIM_FROM_ANCESTORS = new AnimationSpec(
            1, 0, 1.0f, AnimationSpec.Direction.NORMAL, AnimationSpec.FillMode.NONE, "ease", 0, List.of());

    /** 層の局所座標で集めているか。 */
    boolean local;
    /**
     * 層だけを見て集めているか（M3b-4a の層ごとの記憶。{@link SceneEmitter} が層を作るとき）。祖先（原点の節・親の錨の節・
     * 祖先の文字色のアニメーション）は組み立てまで分からないので、原点の種類は常に {@link DrawOps#ORIGIN_NODE} として記録し、
     * 祖先の文字色は印で残す。
     */
    boolean deferred;
    /** いま積む命令の座標の基準（0 なら層自身、{@link DrawOps#BASE_ORIGIN_PARENT} なら原点の節）。 */
    int base;
    /** 座標を引く整数の原点（局所なら層の整数の原点、そうでなければ {@link #setOrigin} の値）。 */
    int originX, originY;
    /** 局所のときの、いま積む命令の原点の種類。 */
    byte originKind = DrawOps.ORIGIN_ABSOLUTE;
    /** 命令を作っている層とその断片（{@link #beginDeferred} のときだけ。{@link #runsOf}）。 */
    private SceneLayer building;
    private DrawOps buildingRuns;

    OpList() {
    }

    int size() {
        return ops.size();
    }

    boolean isEmpty() {
        return ops.isEmpty();
    }

    void clear() {
        ops.clear();
        building = null;
        buildingRuns = null;
    }

    /** 積んだ命令を不変の列にする。 */
    DrawOps freeze() {
        return ops.freeze();
    }

    /**
     * 命令を末尾に足す（{@link PaintSupport#addElement} から。座標は既に今の原点からの値）。{@code line} なら、{@code meta}（箱で共有する
     * 文字のメタ）のベースライン・行の高さを命令ごとの値で上書きする。{@code run} は命令を作った断片の番号（無ければ -1）。
     */
    void add(ElementType type, int flags, ImageFitMode fit, String baseNodeId, String id, long key,
             float x, float y, float w, float h, String text, int color, int bgColor, Map<String, String> events,
             int radius, float opacity, float tx, float ty, float sx, float sy, float rot, int trans,
             AnimationSpec animDef, RenderMeta meta, boolean line, float baseline, float lineHeight, int run) {
        ops.add(type, flags, fit, baseNodeId, id, key, x, y, w, h, text, color, bgColor, events, radius, opacity,
                tx, ty, sx, sy, rot, trans, animDef, meta, line, baseline, lineHeight, run);
    }

    /** 位置 {@code at} の錨の命令にレイヤーの指定を載せる。 */
    void withLayer(int at, LayerSpec spec) {
        RenderMeta meta = ops.meta(at);
        RenderMeta m = meta != null ? meta : RenderMeta.EMPTY;
        ops.setMeta(at, m.withHints(m.hints().withLayer(spec)));
    }

    /** 左上 {@code (x, y)} に置いた箱の描画を、その層の整数の原点からの局所座標で集め始める。 */
    void beginLocal(float x, float y) {
        ops.clear();
        building = null;
        buildingRuns = null;
        local = true;
        originX = Math.round(x);
        originY = Math.round(y);
        originKind = DrawOps.ORIGIN_ABSOLUTE;
        base = 0;
        deferred = false;
    }

    /**
     * 位置 0 に置いた層 {@code layer}（作っている途中）の描画を、祖先を組み立てまで残して集め始める。層の断片 {@code runs} を写し、文字の命令は
     * 断片を指せる。生成器は層の断片を {@link #runsOf} から読む（作っている層の {@code ops()} はまだ無い。M3b-4c-1c）。
     */
    void beginDeferred(SceneLayer layer, DrawOps runs) {
        beginLocal(0f, 0f);
        ops.beginRuns(runs);
        deferred = true;
        building = layer;
        buildingRuns = runs;
    }

    /** 箱 {@code box} の行の断片（命令を作っている層ならその断片、ほかは層の中身）。 */
    DrawOps runsOf(SceneLayer box) {
        return box == building ? buildingRuns : box.ops();
    }

    /** 原点からの座標で集め始める（原点は 0、{@link #setOrigin} で変える）。 */
    void beginFinal() {
        ops.clear();
        building = null;
        buildingRuns = null;
        local = false;
        originX = 0;
        originY = 0;
        originKind = DrawOps.ORIGIN_ABSOLUTE;
        base = 0;
        deferred = false;
    }

    /** 原点からの座標のときの整数の原点。 */
    void setOrigin(int ox, int oy) {
        this.originX = ox;
        this.originY = oy;
    }

    /** 以後の項目を描いている箱自身の節からの相対にする（局所座標で集めているときだけ。生成器は局所座標でしか描かない）。 */
    void originSelf() {
        if (local) originKind = DrawOps.ORIGIN_SELF;
    }

    /** 以後の項目を祖先の節（{@code hasNode} が偽なら絶対座標）からの相対にする（局所座標で集めているときだけ）。 */
    void originNode(boolean hasNode) {
        if (local) originKind = hasNode || deferred ? DrawOps.ORIGIN_NODE : DrawOps.ORIGIN_ABSOLUTE;
    }

    /** 今の原点を覚える（{@link #restoreOrigin} で戻す）。確保しないよう 1 つの {@code long} に詰める。 */
    long saveOrigin() {
        return local ? (originKind | base) : (((long) originX) << 32) | (originY & 0xFFFFFFFFL);
    }

    void restoreOrigin(long saved) {
        if (local) {
            originKind = (byte) (saved & DrawOps.ORIGIN_MASK);
            base = (int) saved & DrawOps.BASE_ORIGIN_PARENT;
        } else {
            originX = (int) (saved >> 32);
            originY = (int) saved;
        }
    }

    /** 以後の命令の座標を、層の原点の節（文字・インラインの断片の基準）から数える。戻すのは {@link #restoreOrigin}。 */
    void baseOriginParent() {
        if (local) base = DrawOps.BASE_ORIGIN_PARENT;
    }

    /** 直前に積んだ項目を、箱の錨（組み立てで親の錨の ID を入れる）にする。 */
    void markAnchor() {
        ops.addFlags(ops.size() - 1, DrawOps.ANCHOR);
    }

    /** いま積む命令の印（原点の種類と座標の基準。原点からの座標のときは絶対）。 */
    int flags() {
        return local ? (originKind | base) : DrawOps.ORIGIN_ABSOLUTE;
    }
}
