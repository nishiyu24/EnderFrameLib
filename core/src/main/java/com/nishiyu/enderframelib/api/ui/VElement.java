package com.nishiyu.enderframelib.api.ui;

import com.nishiyu.enderframelib.api.dom.Document;
import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.api.dom.Node;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Virtual DOM Element representing an HTML tag with props and child nodes.
 */
@ApiStatus.AvailableSince("1.0")
public class VElement implements VNode {

    private final String tag;
    private final List<Prop> props = new ArrayList<>();
    private final List<VNode> children = new ArrayList<>();
    private Consumer<Element> refCallback;

    public VElement(@NotNull String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }

    public List<Prop> props() {
        return Collections.unmodifiableList(props);
    }

    public List<VNode> children() {
        return Collections.unmodifiableList(children);
    }

    public VElement addProp(Prop prop) {
        if (prop != null) {
            this.props.add(prop);
        }
        return this;
    }

    public VElement addChild(VNode child) {
        if (child != null) {
            this.children.add(child);
        }
        return this;
    }

    public VElement setRef(Consumer<Element> callback) {
        this.refCallback = callback;
        return this;
    }

    @Override
    @NotNull
    public Element mount(Document doc) {
        Element el = doc.createElement(tag);
        for (Prop prop : props) {
            prop.apply(el);
        }
        for (VNode child : children) {
            Node childNode = child.mount(doc);
            if (childNode != null) {
                el.appendChild(childNode);
            }
        }
        if (refCallback != null) {
            refCallback.accept(el);
        }
        return el;
    }
}
