package com.nishiyu.enderframelib.api.dom;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * 利用者が選んでいる範囲。Selection API の {@code Selection}（{@code window.getSelection()} / {@code document.getSelection()}）。
 *
 * 範囲は 1 つだけ持つ（Chrome と同じ）。{@code anchor} は選び始めた側、{@code focus} は動かす側で、
 * 後ろ向きに選んだとき範囲の始点が focus になる。変更のたびに文書へ {@code selectionchange} が届く。
 */
@ApiStatus.AvailableSince("1.0")
public interface Selection {

    @Nullable
    Node getAnchorNode();

    int getAnchorOffset();

    @Nullable
    Node getFocusNode();

    int getFocusOffset();

    boolean isCollapsed();

    int getRangeCount();

    /** {@code None}（範囲なし）/ {@code Caret}（折りたたまれている）/ {@code Range}。 */
    String getType();

    /** {@code none} / {@code forward} / {@code backward}。 */
    String getDirection();

    Range getRangeAt(int index);

    void addRange(Range range);

    void removeRange(Range range);

    void removeAllRanges();

    void empty();

    void collapse(@Nullable Node node, int offset);

    default void setPosition(@Nullable Node node, int offset) {
        collapse(node, offset);
    }

    void collapseToStart();

    void collapseToEnd();

    void extend(Node node, int offset);

    void setBaseAndExtent(Node anchorNode, int anchorOffset, Node focusNode, int focusOffset);

    void selectAllChildren(Node node);

    void deleteFromDocument();

    boolean containsNode(Node node, boolean allowPartialContainment);

    /** 選んでいる文字列。 */
    String toString();
}
