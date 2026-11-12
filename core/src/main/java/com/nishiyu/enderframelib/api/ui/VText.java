package com.nishiyu.enderframelib.api.ui;

import com.nishiyu.enderframelib.api.dom.DOMNodes.Text;
import com.nishiyu.enderframelib.api.dom.Document;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
 * Virtual text node, supporting static strings, suppliers, and reactive {@link State}.
 */
@ApiStatus.AvailableSince("1.0")
public class VText implements VNode {

    private final Object source;

    public VText(Object source) {
        this.source = source;
    }

    @Override
    @NotNull
    public Text mount(Document doc) {
        String initial = format(source);
        Text textNode = doc.createTextNode(initial);
        if (source instanceof State<?> state) {
            state.onChange(val -> textNode.setData(val != null ? String.valueOf(val) : ""));
        }
        return textNode;
    }

    private static String format(Object obj) {
        if (obj == null) return "";
        if (obj instanceof State<?> s) {
            Object v = s.get();
            return v != null ? String.valueOf(v) : "";
        }
        if (obj instanceof Supplier<?> sup) {
            Object v = sup.get();
            return v != null ? String.valueOf(v) : "";
        }
        return String.valueOf(obj);
    }
}
