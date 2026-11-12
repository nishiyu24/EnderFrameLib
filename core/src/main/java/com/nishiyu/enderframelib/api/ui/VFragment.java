package com.nishiyu.enderframelib.api.ui;

import com.nishiyu.enderframelib.api.dom.DOMNodes.DocumentFragment;
import com.nishiyu.enderframelib.api.dom.Document;
import com.nishiyu.enderframelib.api.dom.Node;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Virtual DocumentFragment grouping multiple virtual nodes without introducing an extra DOM element.
 */
@ApiStatus.AvailableSince("1.0")
public class VFragment implements VNode {

    private final List<VNode> children = new ArrayList<>();

    public VFragment() {}

    public VFragment(List<VNode> children) {
        if (children != null) {
            this.children.addAll(children);
        }
    }

    public List<VNode> children() {
        return Collections.unmodifiableList(children);
    }

    public VFragment addChild(VNode child) {
        if (child != null) {
            this.children.add(child);
        }
        return this;
    }

    @Override
    @NotNull
    public Node mount(Document doc) {
        DocumentFragment fragment = doc.createDocumentFragment();
        for (VNode child : children) {
            Node childNode = child.mount(doc);
            if (childNode != null) {
                fragment.appendChild(childNode);
            }
        }
        return fragment;
    }
}
