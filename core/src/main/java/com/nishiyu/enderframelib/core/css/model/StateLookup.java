package com.nishiyu.enderframelib.core.css.model;

import com.nishiyu.enderframelib.core.css.*;
import com.nishiyu.enderframelib.core.css.model.*;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

/**
 * 任意の DOM ノードに対する現在の {@link ElementState} を返す関数。
 */
@FunctionalInterface
public interface StateLookup {
    ElementState stateOf(HtmlNode node);

    /** {@code node} をコンテナとして見たときの種類・寸法・カスタムプロパティ。算出スタイルが無ければ null。 */
    default ContainerBox containerOf(HtmlNode node) {
        return null;
    }

    /**
     * {@code from} から祖先へたどって、最初の（寸法の問い合わせに答えられる）コンテナ。
     * {@code cqw} などのコンテナ単位の基準になる。無ければ {@link ContainerBox#NONE_SIZE}。
     */
    default ContainerBox nearestSizeContainer(HtmlNode from) {
        for (HtmlNode cur = from; cur != null; cur = cur.parent) {
            ContainerBox box = containerOf(cur);
            if (box != null && box.isSizeContainer()) return box;
        }
        return ContainerBox.NONE_SIZE;
    }
}
