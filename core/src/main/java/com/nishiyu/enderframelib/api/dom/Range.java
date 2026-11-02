package com.nishiyu.enderframelib.api.dom;

import com.nishiyu.enderframelib.api.dom.DOMNodes.DocumentFragment;
import org.jetbrains.annotations.ApiStatus;

/**
 * 文書の一部分（始点と終点の境界点に挟まれた範囲）。WHATWG DOM Standard §5 の {@code Range}。
 *
 * 境界点は「節 + オフセット」で、文字データの節（Text / Comment）ではオフセットが UTF-16 の位置、
 * それ以外の節では子の位置。生きた範囲（live range）で、範囲を持つ文書の節の挿入・削除・文字の変更に追従する。
 * {@link Document#createRange()} で作る。
 */
@ApiStatus.AvailableSince("1.0")
public interface Range {

    short START_TO_START = 0;
    short START_TO_END = 1;
    short END_TO_END = 2;
    short END_TO_START = 3;

    Node getStartContainer();

    int getStartOffset();

    Node getEndContainer();

    int getEndOffset();

    boolean isCollapsed();

    Node getCommonAncestorContainer();

    void setStart(Node node, int offset);

    void setEnd(Node node, int offset);

    void setStartBefore(Node node);

    void setStartAfter(Node node);

    void setEndBefore(Node node);

    void setEndAfter(Node node);

    void collapse(boolean toStart);

    default void collapse() {
        collapse(false);
    }

    void selectNode(Node node);

    void selectNodeContents(Node node);

    /** {@code how} は {@link #START_TO_START} など。この範囲の点が {@code sourceRange} の点より前なら -1、同じなら 0、後なら 1。 */
    short compareBoundaryPoints(short how, Range sourceRange);

    void deleteContents();

    DocumentFragment extractContents();

    DocumentFragment cloneContents();

    void insertNode(Node node);

    void surroundContents(Node newParent);

    Range cloneRange();

    /** 何もしない（仕様上の互換のためだけに残っている）。 */
    void detach();

    boolean isPointInRange(Node node, int offset);

    short comparePoint(Node node, int offset);

    boolean intersectsNode(Node node);

    /** 範囲に含まれるテキスト（Text 節の文字を文書順に連結したもの）。 */
    String toString();
}
