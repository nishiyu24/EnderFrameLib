// ==========================================
// 新規: core/dom/NodeType.java
// ==========================================
package com.nishiyu.enderframelib.core.dom;

/**
 * ノードの種類を判別する列挙型。
 * "#text".equals(node.tag) のような文字列比較をなくします。
 */
public enum NodeType {
    ELEMENT,
    TEXT,
    ROOT
}