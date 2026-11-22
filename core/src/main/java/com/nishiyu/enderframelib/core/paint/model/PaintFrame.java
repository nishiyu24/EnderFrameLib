package com.nishiyu.enderframelib.core.paint.model;

import java.util.List;

/**
 * L6（論理・レイアウト・表示リスト生成）から L5b（描画バックエンド・ホスト）へ渡す
 * 不変のフレーム境界スナップショット PDU（S7 ③ / B7 前提）。
 *
 * <p>全欄が {@code final} かつ不変であり、{@link #items()} は不変リスト、
 * {@link #offsets()} は {@link PaintOffsets#isImmutable() 不変スナップショット} であることが保証される。</p>
 */
public record PaintFrame(List<DisplayItem> items, PaintOffsets offsets) {
    public static final PaintFrame EMPTY = new PaintFrame(List.of(), PaintOffsets.EMPTY);

    public PaintFrame {
        if (items == null || items.isEmpty()) items = List.of();
        else if (!(items instanceof FrozenItemList)) items = List.copyOf(items);
        offsets = offsets == null ? PaintOffsets.EMPTY : offsets.snapshot();
    }

    public static PaintFrame of(List<DisplayItem> items, PaintOffsets offsets) {
        if ((items == null || items.isEmpty()) && (offsets == null || offsets.isEmpty())) return EMPTY;
        return new PaintFrame(items, offsets);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}
