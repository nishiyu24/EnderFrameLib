package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.ClipChainPart;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.RenderHints;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import com.nishiyu.enderframelib.core.paint.scene.DrawOps;

import java.util.List;

/**
 * 描画命令（{@link DrawOps}。M3b-4c-1b から詰めた列を使い回すカーソルで読む）に毎フレームの文脈（基準の位置・原点・祖先のクリップ・親の錨・祖先の文字色）を足して
 * 表示項目にする（M3b-4a。一時アダプタ {@link SceneDisplayListAdapter} とともに M3b-4 で消える）。
 *
 * <p>座標を整数に丸めるのはここ 1 箇所だけ。命令の座標は基準（層自身か原点の節）からなので、基準の絶対位置から項目の原点
 * （自分の節・祖先の節・絶対）の整数の位置を引いた差を足してから、左右（上下）の辺を丸める。</p>
 *
 * <p>項目の親の錨の位置もここで決める（M3b-4c-2b-3b-1。列の階層 {@link FrameStructure}）: 箱の錨（{@link DrawOps#ANCHOR}）の親は、組んでいる
 * 箱の子の親の錨（自分の錨を出す祖先の箱か、スクロールの中身の節）。それ以外の項目の親は、同じ箱の組み立ての中で先に足した同じ ID の節
 * （文字の影の層の錨・つまみの節）か、組んでいる箱の錨。鍵・ID で列全体から引き直さない。</p>
 */
final class OpComposer {
    /** 命令を読むカーソル（使い回す。組み立ては入れ子にならない）。 */
    private final DrawOps.Cursor cursor = new DrawOps.Cursor();
    /** 1 つの箱の中で、同じメタ（同一性）と同じずれから作ったメタを使い回す（部品どうしでメタを共有していた形を保つ）。 */
    private RenderMeta[] metaSrc = new RenderMeta[16];
    private RenderMeta[] metaDst = new RenderMeta[16];
    private long[] metaShift = new long[16];
    private int metaCount;
    /**
     * 直前に行の値を入れたメタ（同じ行の語は同じメタ・ベースライン・行の高さなので、1 回の組み立ての中で続けて使い回す。
     * フレームをまたいで持たない）。
     */
    private RenderMeta lineSrc, lineOut;
    private int lineBase, lineH;

    // ---- 1 つの箱を組み立てる間だけ使う ----
    private float layerX, layerY, originX, originY;
    private int selfX, selfY, nodeX, nodeY;
    private ClipRect frameClip;
    private String anchorParentId;
    private SceneAdapterBox textBox;
    private BoxAnimation textAnimator;
    private AnimationSpec textAnim;
    /** 組んでいる箱（無ければ null）と、箱の錨の親（無ければ null）。 */
    private SceneAdapterBox self;
    private String selfKey;
    private ItemSink.AnchorRef anchorParent;
    /** この組み立てで足した節の項目の ID と位置（同じ組み立ての中の項目の親を引く。数個）。 */
    private String[] nodeIds = new String[8];
    private int[] nodePos = new int[8];
    private int nodeCount;

    /** 原点からの座標で集めた命令（{@link OpList#beginFinal}）を、そのまま丸めて項目にする。{@code self} は組んでいる箱（無ければ null）。 */
    void composeFinal(OpList ops, SceneAdapterBox self, ItemSink out) {
        setSelf(self);
        this.anchorParent = null;
        DrawOps.Cursor c = cursor.reset(ops.ops);
        while (c.next()) emit(toItem(c, 0f, 0f, 0, 0, lineMeta(c, c.meta())), c.flags(), out);
        lineSrc = null;
        lineOut = null;
        setSelf(null);
        clearNodes();
    }

    private void setSelf(SceneAdapterBox box) {
        self = box;
        selfKey = box != null && box.node() != null ? box.node().key() : null;
    }

    /** 項目を親の錨の位置と一緒に足す（クラスの説明の規則）。 */
    private void emit(DisplayItem item, int flags, ItemSink out) {
        int pos = out.size();
        if ((flags & DrawOps.ANCHOR) != 0) {
            int p = anchorParent != null ? anchorParent.anchorPos() : -1;
            out.add(item, p, p < 0 ? anchorParent : null);
            if (self != null && self.anchorPos < 0) self.anchorPos = pos;
        } else {
            String pid = item.hints().parentId();
            int p = -1;
            ItemSink.AnchorRef later = null;
            if (pid != null) {
                for (int k = nodeCount - 1; k >= 0 && p < 0; k--) if (pid.equals(nodeIds[k])) p = nodePos[k];
                if (p < 0 && pid.equals(selfKey)) {
                    p = self.anchorPos;
                    if (p < 0) later = self;
                }
            }
            out.add(item, p, later);
        }
        if (item.type() == ElementType.NODE) {
            if (nodeCount == nodeIds.length) {
                nodeIds = java.util.Arrays.copyOf(nodeIds, nodeCount * 2);
                nodePos = java.util.Arrays.copyOf(nodePos, nodeCount * 2);
            }
            nodeIds[nodeCount] = item.id();
            nodePos[nodeCount++] = pos;
        }
    }

    private void clearNodes() {
        for (int i = 0; i < nodeCount; i++) nodeIds[i] = null;
        nodeCount = 0;
    }

    /** 命令の行の値を入れたメタ（{@link DrawOps.Cursor#lineMeta}。直前と同じ元・同じ値なら直前の結果を使う）。 */
    private RenderMeta lineMeta(DrawOps.Cursor op, RenderMeta m) {
        if (m == null || !op.hasLine()) return m;
        int b = Float.floatToRawIntBits(op.baseline()), h = Float.floatToRawIntBits(op.lineHeight());
        if (m == lineSrc && b == lineBase && h == lineH) return lineOut;
        lineSrc = m;
        lineBase = b;
        lineH = h;
        lineOut = op.lineMeta(m);
        return lineOut;
    }

    /**
     * 毎フレーム層の局所座標で集めた命令（{@link OpList#beginLocal}）を項目にする。命令は層の整数の原点からなので、
     * 基準の位置はどちらも層の整数の原点。{@code textBox} は祖先の文字色のアニメーションを解く文字の箱（無ければ null）。
     * {@code self} は組んでいる箱、{@code anchorParent} は箱の錨の親（どちらも無ければ null）。
     */
    void composeLocal(OpList ops, SceneAdapterBox node, ClipRect frameClip, String anchorParentId, ItemSink.AnchorRef anchorParent,
                      SceneAdapterBox self, SceneAdapterBox textBox, BoxAnimation animator, ItemSink out) {
        begin(ops.originX, ops.originY, ops.originX, ops.originY, ops.originX, ops.originY, node, frameClip, anchorParentId,
                anchorParent, self, textBox, animator);
        DrawOps.Cursor c = cursor.reset(ops.ops);
        while (c.next()) emit(compose(c), c.flags(), out);
        end();
    }

    /**
     * 層に記憶した命令（{@link OpList#beginDeferred}。祖先を組み立てまで残したもの）を、箱 {@code box} の今の位置で項目にする。
     *
     * @param node           {@link DrawOps#ORIGIN_NODE} の命令の原点にする祖先の節（無ければ絶対座標）
     * @param frameClip      箱のクリップ（命令のメタのクリップが null なら、これを継ぐ。null 可）
     * @param anchorParentId 箱の錨に入れる親の錨の ID（無ければ null）
     * @param anchorParent   箱の錨の親（列の中の位置を引く。無ければ null）
     * @param animator       祖先の文字色のアニメーションを解く道具
     */
    void composeLayer(DrawOps ops, SceneAdapterBox box, SceneAdapterBox node, ClipRect frameClip, String anchorParentId,
                      ItemSink.AnchorRef anchorParent, BoxAnimation animator, ItemSink out) {
        SceneAdapterBox op = box.originParent();
        begin(box.x(), box.y(), op != null ? op.x() : 0f, op != null ? op.y() : 0f,
                Math.round(box.x()), Math.round(box.y()), node, frameClip, anchorParentId, anchorParent, box, box, animator);
        DrawOps.Cursor c = cursor.reset(ops);
        while (c.next()) emit(compose(c), c.flags(), out);
        end();
    }

    private void begin(float layerX, float layerY, float originX, float originY, int selfX, int selfY, SceneAdapterBox node,
                       ClipRect frameClip, String anchorParentId, ItemSink.AnchorRef anchorParent, SceneAdapterBox self,
                       SceneAdapterBox textBox, BoxAnimation textAnimator) {
        metaCount = 0;
        this.anchorParent = anchorParent;
        setSelf(self);
        this.layerX = layerX;
        this.layerY = layerY;
        this.originX = originX;
        this.originY = originY;
        this.selfX = selfX;
        this.selfY = selfY;
        this.nodeX = node != null ? Math.round(node.x()) : 0;
        this.nodeY = node != null ? Math.round(node.y()) : 0;
        this.frameClip = frameClip;
        this.anchorParentId = anchorParentId;
        this.textBox = textBox;
        this.textAnimator = textAnimator;
        this.textAnim = null;
    }

    private void end() {
        lineSrc = null;
        lineOut = null;
        frameClip = null;
        anchorParentId = null;
        textBox = null;
        textAnimator = null;
        textAnim = null;
        setSelf(null);
        anchorParent = null;
        clearNodes();
        for (int i = 0; i < metaCount; i++) {
            metaSrc[i] = null;
            metaDst[i] = null;
        }
        metaCount = 0;
    }

    private DisplayItem compose(DrawOps.Cursor op) {
        int flags = op.flags();
        int rx, ry;
        switch (op.origin()) {
            case DrawOps.ORIGIN_SELF -> { rx = selfX; ry = selfY; }
            case DrawOps.ORIGIN_NODE -> { rx = nodeX; ry = nodeY; }
            default -> { rx = 0; ry = 0; }
        }
        boolean fromOrigin = (flags & DrawOps.BASE_ORIGIN_PARENT) != 0;
        float bx = fromOrigin ? originX : layerX, by = fromOrigin ? originY : layerY;
        String parentId = (flags & DrawOps.ANCHOR) != 0 ? anchorParentId : null;
        DisplayItem item = toItem(op, bx, by, rx, ry, lineMeta(op, composeMeta(op.meta(), bx, by, rx, ry, parentId)));
        if ((flags & DrawOps.INHERIT_TEXT_COLOR_ANIM) != 0 && textBox != null) {
            if (textAnim == null) textAnim = ancestorTextColorAnimation(textBox, textAnimator);
            if (textAnim != item.animDef()) {
                item = new DisplayItem(item.id(), item.baseNodeId(), item.type(), item.text(), item.events(), item.transitionDuration(),
                        textAnim, item.fitMode(), item.meta(), item.key(), item.cell());
            }
        }
        return item;
    }

    /**
     * 文字の色のアニメーションを祖先から継ぐ（M3b-4c-1a で {@code BoxAnimation} から移した。祖先を読むので組み立ての側）。
     * {@code animation} は継承されないが、祖先の {@code color} を補間すると継承した文字の色も一緒に変わる（CSS Animations 1 §3: アニメーションした
     * 値も算出値として継承される）。途中の要素が自分の色を持っていれば、そこから先には届かない。
     */
    static AnimationSpec ancestorTextColorAnimation(SceneAdapterBox text, BoxAnimation animator) {
        int inherited = text.style().typed().inheritedPaint.color;
        for (SceneAdapterBox a = text.paintParent(); a != null && a.style() != null; a = a.paintParent()) {
            com.nishiyu.enderframelib.core.css.Style as = a.style().typed();
            if (as.inheritedPaint.color != inherited) break;
            if (as.visual.animation != null || as.visual.animationName != null) {
                AnimationSpec anim = animator.parseAnimationDef(a.style(), a.w(), a.h(), as.inheritedPaint.color, 0)
                        .withChannels(AnimationSpec.CH_COLOR);
                if (!anim.isEmpty()) return anim;
            }
            // 祖先の要素が自分の色を宣言していれば、その上の祖先の色は継承されない
            SceneAdapterBox p = a.paintParent();
            if (p != null && p.style() != null && a.style().typed().inheritedPaint.color != p.style().typed().inheritedPaint.color) break;
        }
        return AnimationSpec.NONE;
    }

    private RenderMeta composeMeta(RenderMeta meta, float bx, float by, int rx, int ry, String parentId) {
        if (meta == null) return null;
        float sx = bx - rx, sy = by - ry;
        long shift = shift(sx, sy);
        if (parentId == null) {
            for (int i = 0; i < metaCount; i++) {
                if (metaSrc[i] == meta && metaShift[i] == shift) return metaDst[i];
            }
        }
        ClipRect c = meta.clipRect();
        ClipRect nc = c == null ? frameClip : rebase(c, frameClip, bx, by, rx, ry);
        RenderHints h = meta.hints();
        RenderHints nh = h;
        if ((sx != 0f || sy != 0f) && h != RenderHints.NONE) {
            if (nh.shadowShape() != null) nh = nh.withShadowShape(nh.shadowShape().offset(sx, sy));
            if (nh.shaderGradient() != null) nh = nh.withShaderGradient(nh.shaderGradient().offset(sx, sy));
        }
        if (parentId != null) nh = nh.withParentId(parentId);
        RenderMeta res = nc == c && nh == h ? meta
                : new RenderMeta(nc, meta.borderRadii(), meta.circleBorder(), meta.fontHeight(), meta.baseline(), meta.lineHeight(),
                        meta.originX(), meta.originY(), meta.itemCount(), meta.interactiveState(), meta.roundedBorder(), nh);
        if (parentId == null) {
            if (metaCount == metaSrc.length) {
                metaSrc = java.util.Arrays.copyOf(metaSrc, metaCount * 2);
                metaDst = java.util.Arrays.copyOf(metaDst, metaCount * 2);
                metaShift = java.util.Arrays.copyOf(metaShift, metaCount * 2);
            }
            metaSrc[metaCount] = meta;
            metaDst[metaCount] = res;
            metaShift[metaCount] = shift;
            metaCount++;
        }
        return res;
    }

    /**
     * 部品が狭めたクリップを、箱のクリップ {@code frameClip} の上へ連鎖の 1 つずつ積み直す。連鎖の矩形は命令と同じ基準
     * {@code (bx, by)} からなので、絶対座標に直してから項目の原点 {@code (rx, ry)} を引く（以前の {@code intersectClip} と同じ順序）。
     */
    private static ClipRect rebase(ClipRect c, ClipRect frameClip, float bx, float by, int rx, int ry) {
        List<ClipChainPart> chain = c.chain();
        ClipRect cur = frameClip;
        for (int i = 0, n = chain.size(); i < n; i++) {
            ClipChainPart p = chain.get(i);
            float[] r = p.rect();
            float x0 = r[0] + bx, y0 = r[1] + by, x1 = r[2] + bx, y1 = r[3] + by; // 絶対座標
            r[0] = x0 - rx;
            r[1] = y0 - ry;
            r[2] = x1 - rx;
            r[3] = y1 - ry;
            boolean ell = r.length >= 12;
            cur = BoxDecorations.intersectClipRect(cur, x0, y0, x1, y1,
                    r[4], r[5], r[6], r[7],
                    ell ? r[8] : 0f, ell ? r[9] : 0f, ell ? r[10] : 0f, ell ? r[11] : 0f,
                    ell, p.ownerId(), ClipRect.appendChain(cur, r, p.ownerId(), rx, ry));
        }
        return cur;
    }

    private static long shift(float sx, float sy) {
        return (((long) Float.floatToRawIntBits(sx)) << 32) | (Float.floatToRawIntBits(sy) & 0xFFFFFFFFL);
    }

    /** 命令の座標を絶対座標（基準 {@code (bx, by)} を足す）にしてから項目の原点 {@code (rx, ry)} を引き、辺を丸める。 */
    private static DisplayItem toItem(DrawOps.Cursor op, float bx, float by, int rx, int ry, RenderMeta meta) {
        float x = op.x(), y = op.y();
        if (bx != 0f) x += bx;
        if (by != 0f) y += by;
        if (rx != 0) x -= rx;
        if (ry != 0) y -= ry;
        int left = Math.round(x);
        int top = Math.round(y);
        int width = Math.round(x + op.w()) - left;
        int height = Math.round(y + op.h()) - top;
        return new DisplayItem(op.id(), op.baseNodeId(), op.type(), left, top, width, height, op.text(), op.color(), op.bgColor(),
                op.events(), op.radius(), op.opacity(), op.tx(), op.ty(), op.sx(), op.sy(), op.rot(),
                op.transitionDuration(), op.animDef(), op.fitMode(), meta, op.key());
    }
}
