package com.nishiyu.enderframelib.core.dom.node;

import com.nishiyu.enderframelib.core.dom.*;
import com.nishiyu.enderframelib.core.dom.node.*;
import com.nishiyu.enderframelib.core.dom.parser.*;
import com.nishiyu.enderframelib.core.dom.parser.*;

/**
 * ノードの種類を判別する列挙型。
 */
public enum NodeType {
    ELEMENT,
    TEXT,
    ROOT,
    COMMENT,
    DOCUMENT_FRAGMENT
}
