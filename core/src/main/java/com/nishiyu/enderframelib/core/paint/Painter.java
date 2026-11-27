package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.SceneWalker.Group;
import java.util.List;

/** 走査の結果を受け取る描画側。 */
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
     * 項目 {@code index} を描く。{@code flat} は層にしなかったグループの不透明度の積
     * （姿勢などは {@link SceneWalker#resolveAt} で引く）。
     */
    void drawItem(DisplayItem el, int index, float flat);

    /**
     * スクロールコンテナの中身（節 {@code node} の子孫の項目 {@code [from, to)}。表示リスト上で連続している）を、保持した層として扱うかを決める。
     * 既定は {@link RetainPlan#NONE}（ふつうに 1 項目ずつ描く）。層を保持できる描画側（GPU）だけが上書きする。
     *
     * <p>呼ぶのは、節の祖先にグループ（不透明度・フィルター）が無いときだけ。{@link RetainPlan#SKIP} を返したら、描画側は
     * 保持済みの層を {@link #drawRetained} で重ねるので、ドライバは範囲の項目を走査しない。{@link RetainPlan#RECORD} なら、
     * 範囲の先頭で {@link #beginRetained}、末尾の後で {@link #endRetained} を呼ぶ（その間の項目は層へ描く）。</p>
     *
     * <p>{@code token} は中身の同一性の印（M3b-4b-3b）: 同じ印なら、節の移動量以外は前に描いたときと同じ中身。走査器
     * （{@link SceneWalker}）はスクロールコンテナの層の子の並び（{@code SceneLayer#children}。中身が変わらない限り同じ参照）を渡す。</p>
     */
    default RetainPlan planRetained(DisplayItem node, int nodeIndex, int from, int to, Object token) { return RetainPlan.NONE; }

    /** {@link RetainPlan#SKIP}: 保持済みの層を今の位置で重ねる。 */
    default void drawRetained(DisplayItem node, int nodeIndex, int from, int to) {}

    /** {@link RetainPlan#RECORD}: 範囲の項目を、保持する層へ描き始める。 */
    default void beginRetained(DisplayItem node, int nodeIndex, int from, int to) {}

    /**
     * {@link RetainPlan#RECORD}: 範囲を描き終えた。層を保存して重ねたら true。保持できなかった（描き方が層に向かない）なら、層を捨てて false を返す。
     * false のとき、ドライバは同じ範囲をふつうに描き直す。
     */
    default boolean endRetained(DisplayItem node, int nodeIndex) { return true; }

}
