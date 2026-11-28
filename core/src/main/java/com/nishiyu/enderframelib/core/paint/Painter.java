package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.SceneWalker.Group;
import java.util.List;

/**
 * 走査の結果を受け取る描画側。項目・保持層の中身は層の文脈（{@link LayerContext}）で受け取り、項目の列の位置で走査器に問わない
 * （M3b-4c-2b-3b-2b-4a）。
 */
public interface Painter {
    /**
     * 走査器が描き直す範囲 {@code damage} を決めた（描き直す範囲を渡したフレームだけ。項目を描く前）。false を返すと項目を描かない
     * （範囲が空）。既定は true。
     */
    default boolean beginWalk(SceneDamage.Rects damage) { return true; }

    /** 開いている層を、項目の祖先のグループ {@code want}（外側から内側の順）に合わせる。 */
    /** {@code want} は呼び出しの間だけ有効（走査が次の項目で使い回す。持ち続けるなら写す）。 */
    void syncLayers(List<Group> want);

    /**
     * 項目を描く。{@code item} は項目の文脈（姿勢・補間値・クリップの持ち主。呼び出しの間だけ有効）、{@code flat} は層にしなかった
     * グループの不透明度の積。
     */
    void drawItem(DisplayItem el, LayerContext.Item item, float flat);

    /**
     * スクロールコンテナの中身 {@code content}（描画順で連続する節の子孫の項目）を、保持した層として扱うかを決める。
     * 既定は {@link RetainPlan#NONE}（ふつうに 1 項目ずつ描く）。層を保持できる描画側（GPU）だけが上書きする。
     *
     * <p>呼ぶのは、節の祖先にグループ（不透明度・フィルター）が無いときだけ。{@link RetainPlan#SKIP} を返したら、描画側は
     * 保持済みの層を {@link #drawRetained} で重ねるので、走査器は中身の項目を走査しない。{@link RetainPlan#RECORD} なら、
     * 中身の先頭の前で {@link #beginRetained}、末尾の後で {@link #endRetained} を呼ぶ（その間の項目は層へ描く）。</p>
     *
     * <p>中身の同一性の印は {@link LayerContext.Content#token}（M3b-4b-3b）: 同じ印なら、節の移動量以外は前に描いたときと同じ中身。
     * {@code content} は呼び出しの間だけ有効（覚えるなら {@link LayerContext.Content#id} で）。</p>
     */
    default RetainPlan planRetained(LayerContext.Content content) { return RetainPlan.NONE; }

    /** {@link RetainPlan#SKIP}: 保持済みの層を今の位置で重ねる。 */
    default void drawRetained(LayerContext.Content content) {}

    /** {@link RetainPlan#RECORD}: 中身の項目を、保持する層へ描き始める。 */
    default void beginRetained(LayerContext.Content content) {}

    /**
     * {@link RetainPlan#RECORD}: 中身を描き終えた。層を保存して重ねたら true。保持できなかった（描き方が層に向かない）なら、層を捨てて false を返す。
     * false のとき、走査器は同じ中身をふつうに描き直す。
     */
    default boolean endRetained(LayerContext.Content content) { return true; }

}
