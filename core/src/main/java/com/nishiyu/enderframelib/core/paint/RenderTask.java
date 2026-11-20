package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import java.util.List;
import com.nishiyu.enderframelib.core.paint.model.*;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.addElement;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.isAtomicInline;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.isFlexOrGridItem;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.isInvisible;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.normalizeClipRect;

/**
 * {@code box} は箱自身を描くタスクだけが持つ（スクロールバーは null）。{@code layerStart} はレイヤーの開始の印
 * （次に出る錨から {@link #LAYER_END} までがレイヤーの中身）、{@code layerEnd} は終了の印。印は項目を出さない。
 */
final class RenderTask {
    static final byte KIND_DRAW = 0;
    static final byte KIND_RECORD = 1;
    static final byte KIND_REPLAY = 2;
    static final byte KIND_SCROLLBARS = 3;
    static final byte KIND_BACKDROP = 4;
    static final byte KIND_LAYER_START = 5;
    static final byte KIND_LAYER_END = 6;

    static final RenderTask LAYER_END = new RenderTask().setLayerEnd();

    byte kind;
    LayoutBox box;
    ClipRect clip;
    String parentId;
    List<DisplayItem> replayItems;
    Chunk chunk;
    boolean inline;
    LayerSpec layerStart;
    LayoutBox layerBox;
    boolean hasMovingAncestor;

    RenderTask setDraw(LayoutBox box, ClipRect clip, String parentId, boolean hasMovingAncestor) {
        this.kind = KIND_DRAW;
        this.box = box;
        this.clip = clip;
        this.parentId = parentId;
        this.replayItems = null;
        this.chunk = null;
        this.layerStart = null;
        this.layerBox = null;
        this.hasMovingAncestor = hasMovingAncestor;
        return this;
    }

    RenderTask setRecord(LayoutBox box, ClipRect clip, String parentId, Chunk chunk, boolean inline, boolean hasMovingAncestor) {
        this.kind = KIND_RECORD;
        this.box = box;
        this.clip = clip;
        this.parentId = parentId;
        this.replayItems = null;
        this.chunk = chunk;
        this.inline = inline;
        this.layerStart = null;
        this.layerBox = null;
        this.hasMovingAncestor = hasMovingAncestor;
        return this;
    }

    RenderTask setReplay(LayoutBox box, List<DisplayItem> replayItems, boolean hasMovingAncestor) {
        this.kind = KIND_REPLAY;
        this.box = box;
        this.clip = null;
        this.parentId = null;
        this.replayItems = replayItems;
        this.chunk = null;
        this.layerStart = null;
        this.layerBox = null;
        this.hasMovingAncestor = hasMovingAncestor;
        return this;
    }

    RenderTask setScrollbars(LayoutBox box, ClipRect clip) {
        this.kind = KIND_SCROLLBARS;
        this.box = box;
        this.clip = clip;
        this.parentId = null;
        this.replayItems = null;
        this.chunk = null;
        this.layerStart = null;
        this.layerBox = null;
        this.hasMovingAncestor = false;
        return this;
    }

    RenderTask setBackdrop(LayoutBox box) {
        this.kind = KIND_BACKDROP;
        this.box = box;
        this.clip = null;
        this.parentId = null;
        this.replayItems = null;
        this.chunk = null;
        this.layerStart = null;
        this.layerBox = null;
        this.hasMovingAncestor = false;
        return this;
    }

    RenderTask setLayerStart(LayerSpec spec, LayoutBox layerBox) {
        this.kind = KIND_LAYER_START;
        this.box = null;
        this.clip = null;
        this.parentId = null;
        this.replayItems = null;
        this.chunk = null;
        this.layerStart = spec;
        this.layerBox = layerBox;
        this.hasMovingAncestor = false;
        return this;
    }

    private RenderTask setLayerEnd() {
        this.kind = KIND_LAYER_END;
        return this;
    }

    LayoutBox box() { return (kind == KIND_SCROLLBARS || kind == KIND_LAYER_START || kind == KIND_LAYER_END) ? null : box; }
    LayerSpec layerStart() { return layerStart; }
    boolean layerEnd() { return kind == KIND_LAYER_END; }
    LayoutBox layerBox() { return layerBox; }
    boolean hasMovingAncestor() { return hasMovingAncestor; }
}
