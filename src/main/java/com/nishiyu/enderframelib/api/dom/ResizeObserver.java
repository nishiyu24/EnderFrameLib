package com.nishiyu.enderframelib.api.dom;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 要素の content box の大きさの変化を知らせる（CSSOM View / Resize Observer の {@code ResizeObserver}）。
 *
 * <p>観測を始めた直後の最初のレイアウトでも 1 回通知する（ブラウザと同じ）。通知はレイアウトが確定した後に同じスレッドで呼ぶ。</p>
 */
@ApiStatus.AvailableSince("1.0")
public final class ResizeObserver implements Document.LayoutObserver {

    /** 通知 1 件。{@code contentRect} は padding box の左上を原点にした content box。 */
    public record Entry(Element target, DOMRect contentRect) {
    }

    private final Consumer<List<Entry>> callback;
    private final Map<Element, float[]> observed = new LinkedHashMap<>();
    private final Map<Element, Document> owners = new LinkedHashMap<>();

    public ResizeObserver(Consumer<List<Entry>> callback) {
        this.callback = callback;
    }

    public void observe(Element target) {
        if (target == null || observed.containsKey(target)) return;
        observed.put(target, new float[]{Float.NaN, Float.NaN});
        Document doc = target.getOwnerDocument();
        if (doc != null) {
            owners.put(target, doc);
            doc.addLayoutObserver(this);
        }
    }

    public void unobserve(Element target) {
        observed.remove(target);
        owners.remove(target);
    }

    public void disconnect() {
        for (Document doc : new ArrayList<>(owners.values())) doc.removeLayoutObserver(this);
        observed.clear();
        owners.clear();
    }

    @Override
    public void layoutUpdated() {
        List<Entry> changed = new ArrayList<>();
        for (Map.Entry<Element, float[]> e : observed.entrySet()) {
            DOMRect rect = e.getKey().getContentRect();
            if (rect == null) rect = DOMRect.EMPTY;
            float[] last = e.getValue();
            if (Float.compare(last[0], rect.width()) != 0 || Float.compare(last[1], rect.height()) != 0) {
                last[0] = rect.width();
                last[1] = rect.height();
                changed.add(new Entry(e.getKey(), rect));
            }
        }
        if (!changed.isEmpty()) callback.accept(changed);
    }
}
