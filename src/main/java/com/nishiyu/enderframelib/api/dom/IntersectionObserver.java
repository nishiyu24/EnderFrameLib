package com.nishiyu.enderframelib.api.dom;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 要素が祖先のスクロール領域・ビューポート（または {@code root}）にどれだけ見えているかの変化を知らせる
 * （Intersection Observer の {@code IntersectionObserver}）。
 *
 * <p>{@code rootMargin} は未対応。{@code threshold} は 0（1 px でも見えたら交差）か、指定した比率を跨いだときに通知する。</p>
 */
@ApiStatus.AvailableSince("1.0")
public final class IntersectionObserver implements Document.LayoutObserver {

    /** 通知 1 件。 */
    public record Entry(Element target, boolean isIntersecting, float intersectionRatio,
                        DOMRect boundingClientRect, DOMRect intersectionRect) {
    }

    private final Consumer<List<Entry>> callback;
    @Nullable
    private final Element root;
    private final float[] thresholds;
    /** 対象 → 最後に通知した段階（thresholds の添字 + 1、交差なしは 0。未通知は -1）。 */
    private final Map<Element, Integer> observed = new LinkedHashMap<>();
    private final Map<Element, Document> owners = new LinkedHashMap<>();

    public IntersectionObserver(Consumer<List<Entry>> callback) {
        this(callback, null, new float[]{0f});
    }

    public IntersectionObserver(Consumer<List<Entry>> callback, @Nullable Element root, float[] thresholds) {
        this.callback = callback;
        this.root = root;
        this.thresholds = thresholds == null || thresholds.length == 0 ? new float[]{0f} : thresholds.clone();
        java.util.Arrays.sort(this.thresholds);
    }

    public void observe(Element target) {
        if (target == null || observed.containsKey(target)) return;
        observed.put(target, -1);
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
        for (Map.Entry<Element, Integer> e : observed.entrySet()) {
            Element target = e.getKey();
            DOMRect bounding = target.getBoundingClientRect();
            DOMRect visible = target.getVisibleClientRect(root);
            float area = bounding.width() * bounding.height();
            boolean intersecting = !visible.isEmpty();
            float ratio = area > 0 ? (visible.width() * visible.height()) / area : 0f;
            int stage = 0;
            if (intersecting) {
                stage = 1;
                for (int i = 0; i < thresholds.length; i++) {
                    if (ratio >= thresholds[i] && thresholds[i] > 0) stage = i + 1;
                }
            }
            if (e.getValue() != stage) {
                e.setValue(stage);
                changed.add(new Entry(target, intersecting, ratio, bounding, visible));
            }
        }
        if (!changed.isEmpty()) callback.accept(changed);
    }
}
