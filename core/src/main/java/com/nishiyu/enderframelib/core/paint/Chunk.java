package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import java.util.ArrayList;
import java.util.List;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.addElement;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.isAtomicInline;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.isFlexOrGridItem;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.isInvisible;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.normalizeClipRect;
import com.nishiyu.enderframelib.core.paint.PaintOrder.BoxSlice;

/** 保持した部分木の項目。{@code blocks} と {@code inlines} は、分類したときに入った層ごとの、描いた順の項目。 */
final class Chunk {
    LayoutBox parentBox;
    float boxX, boxY;
    ClipRect clip;
    String parentId;
    boolean parentRelative;
    int parentOriginX, parentOriginY;
    long epoch;
    int boxCount;
    int interactiveCount;
    final List<DisplayItem> blockItems;
    final List<DisplayItem> inlineItems;
    final List<BoxSlice> blockSlices;
    final List<BoxSlice> inlineSlices;
    int created;
    int executed;
    int generation;

    Chunk(LayoutBox parentBox, float boxX, float boxY, ClipRect clip, String parentId,
          boolean parentRelative, int parentOriginX, int parentOriginY,
          long epoch, int boxCount, int interactiveCount) {
        this.parentBox = parentBox;
        this.boxX = boxX;
        this.boxY = boxY;
        this.clip = clip;
        this.parentId = parentId;
        this.parentRelative = parentRelative;
        this.parentOriginX = parentOriginX;
        this.parentOriginY = parentOriginY;
        this.epoch = epoch;
        this.boxCount = boxCount;
        this.interactiveCount = interactiveCount;
        int cap = Math.max(1, Math.min(boxCount, 16));
        this.blockSlices = new ArrayList<>(cap);
        this.inlineSlices = new ArrayList<>(cap);
        if (DisplayListBuilder.verifyStamps) {
            this.blockItems = new ArrayList<>();
            this.inlineItems = new ArrayList<>();
        } else {
            this.blockItems = null;
            this.inlineItems = null;
        }
    }

    void reset(LayoutBox parentBox, float boxX, float boxY, ClipRect clip, String parentId,
               boolean parentRelative, int parentOriginX, int parentOriginY,
               long epoch, int boxCount, int interactiveCount, PaintOrder owner) {
        this.parentBox = parentBox;
        this.boxX = boxX;
        this.boxY = boxY;
        this.clip = clip;
        this.parentId = parentId;
        this.parentRelative = parentRelative;
        this.parentOriginX = parentOriginX;
        this.parentOriginY = parentOriginY;
        this.epoch = epoch;
        this.boxCount = boxCount;
        this.interactiveCount = interactiveCount;
        owner.recycleSlices(this.blockSlices);
        owner.recycleSlices(this.inlineSlices);
        if (this.blockItems != null) this.blockItems.clear();
        if (this.inlineItems != null) this.inlineItems.clear();
        this.created = 0;
        this.executed = 0;
    }

    /** 記録中のタスクが全部走り終えたか（途中で止まった記録は使わない）。 */
    boolean complete() {
        return created == executed;
    }
    void release(PaintOrder owner) {
        this.parentBox = null;
        this.clip = null;
        this.parentId = null;
        owner.recycleSlices(this.blockSlices);
        owner.recycleSlices(this.inlineSlices);
        if (this.blockItems != null) this.blockItems.clear();
        if (this.inlineItems != null) this.inlineItems.clear();
        this.created = 0;
        this.executed = 0;
    }
}
