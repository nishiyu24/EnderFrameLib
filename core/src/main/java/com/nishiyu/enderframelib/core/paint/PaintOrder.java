package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.CssKeywords;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.layout.BlockLayoutEngine;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.Corners;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import java.util.function.Predicate;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.addElement;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.isAtomicInline;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.isFlexOrGridItem;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.isInvisible;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.normalizeClipRect;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 描画の順序（CSS 2.1 付録 E のスタッキングコンテキスト）を決め、箱ごとの描画を呼ぶ。スクロールバーと、
 * 箱が中身に掛けるクリップもここで持つ。
 */
final class PaintOrder {
    /** 箱 1 つ自身（背景・枠・部品）を描画項目にする。 */
    @FunctionalInterface
    interface BoxPainter {
        void draw(List<DisplayItem> elements, LayoutBox box, ClipRect clipRect, String parentId);
    }

    private final int rootW;
    private final int rootH;
    private final ScrollState scrollState;
    private final AtomicInteger fragIds;
    private final BoxPainter painter;
    /**
     * 箱が中身に掛けるクリップ（祖先のクリップと自分の overflow の交わり。null なら切らない）。
     * 絶対配置の箱は木の親ではなく包含ブロックの値を引く。
     */
    private final Map<LayoutBox, ClipRect> contentClips = new IdentityHashMap<>();
    /**
     * 値として等しいクリップを同じ実体にそろえる表。箱ごとの記憶（{@code DisplayListBuilder} の BoxMemo）はクリップを覚えていて、
     * 次の描画で同じか比べる。クリップする箱は描画のたびに新しい {@link ClipRect} を作るので、そろえないと
     * 子の箱ごとに連鎖の中身まで比べる（5000 行のリストで描画の 1 割強）。そろえておけば同一性（{@code ==}）で済む。
     * 描画をまたいで持ち、増えすぎたら捨てる（捨てても結果は変わらず、次の比較が 1 度深くなるだけ）。
     */
    private final Map<ClipRect, ClipRect> canonicalClips = new java.util.HashMap<>();
    private static final int CANONICAL_CLIPS_MAX = 1024;

    private ClipRect canonical(ClipRect clip) {
        if (clip == null) return null;
        ClipRect known = canonicalClips.putIfAbsent(clip, clip);
        if (known != null) return known;
        if (canonicalClips.size() > CANONICAL_CLIPS_MAX) {
            canonicalClips.clear();
            canonicalClips.put(clip, clip);
        }
        return clip;
    }

    /** その箱の {@code animation} が {@code opacity} を動かすか（静的な値は 1 でも、動く間はグループとして合成する）。 */
    private final Predicate<LayoutBox> animatesOpacity;
    /** 子孫に {@code mix-blend-mode} を持つ箱がある（スタッキングコンテキストを越えない範囲で）か。 */
    private final Map<LayoutBox, Boolean> blendDescendants = new IdentityHashMap<>();

    /** 描画中のスクロールコンテナ（中身の節を持つもの）。つまみの動きの係数は、つまみを出すときに埋まる。 */
    static final class ScrollContent {
        final LayoutBox box;
        final String contentId;
        final float sx0, sy0;
        String thumbVId, thumbHId;
        float kx, ky;

        ScrollContent(LayoutBox box, String contentId, float sx0, float sy0) {
            this.box = box;
            this.contentId = contentId;
            this.sx0 = sx0;
            this.sy0 = sy0;
        }
    }

    private final java.util.IdentityHashMap<LayoutBox, ScrollContent> scrollContentOf = new java.util.IdentityHashMap<>();
    private final List<ScrollContent> scrollContents = new ArrayList<>();

    // ==========================================
    // 部分木の保持（チャンク）
    // ==========================================
    //
    // 描画は毎回、全ての箱を分類して描画順のタスクを作り直す。1 か所の文字を変えただけでも、5000 行のリストなら 5000 行ぶんの
    // 分類木・タスク・ラムダを作り、箱ごとの記憶（BoxMemo）と突き合わせる（局所更新の約 6 割）。
    // そこで、小さくて「純粋なフロー」の部分木（スタッキングコンテキスト・位置指定・float・スクロール・アニメーション・表・入力部品を
    // 含まない）の項目を、層（blocks / inlines）ごとに保持する。部分木のどの箱にも変更印が付いておらず、親・クリップ・親の id・
    // 箱の数が同じなら、分類も箱の照合も飛ばして、保持した項目をそのまま出す。
    // 描画順の決まり方は変えない: 部分木の項目は、分類したときと同じ層の同じ位置に出る。

    /** 保持した部分木の項目。{@code blocks} と {@code inlines} は、分類したときに入った層ごとの、描いた順の項目。 */
    static final class Chunk {
        final LayoutBox parentBox;
        final ClipRect clip;
        final String parentId;
        final long epoch;
        final int boxCount;
        final int interactiveCount;
        final List<DisplayItem> blockItems = new ArrayList<>();
        final List<DisplayItem> inlineItems = new ArrayList<>();
        int created;
        int executed;
        int generation;
        private PaintNode blockNode, inlineNode;

        Chunk(LayoutBox parentBox, ClipRect clip, String parentId, long epoch, int boxCount, int interactiveCount) {
            this.parentBox = parentBox;
            this.clip = clip;
            this.parentId = parentId;
            this.epoch = epoch;
            this.boxCount = boxCount;
            this.interactiveCount = interactiveCount;
        }

        /** 記録中のタスクが全部走り終えたか（途中で止まった記録は使わない）。 */
        boolean complete() {
            return created == executed;
        }

        PaintNode blockNode() {
            if (blockNode == null) blockNode = replayNode(blockItems);
            return blockNode;
        }

        PaintNode inlineNode() {
            if (inlineNode == null) inlineNode = replayNode(inlineItems);
            return inlineNode;
        }

        private static PaintNode replayNode(List<DisplayItem> items) {
            return new PaintNode(new RenderTask(null, elements -> DisplayListBuilder.appendItems(elements, items)), false, 0, null);
        }
    }

    /** 描画側が持つ箱の記憶との橋渡し。 */
    interface MemoKeeper {
        /**
         * 箱を記録中の部分木の中で描いた直後に呼ぶ。その箱の記憶を {@code chunk} のものとして印を付ける
         * （記憶の生死は、部分木が再利用されている間は部分木の世代で決まる。箱ごとに世代を書き直さずに済む）。
         */
        void owned(LayoutBox box, Chunk chunk);
    }

    /** 記憶と部分木の掃除は、毎回ではなくこの回数に 1 度（木から消えた箱の分が少し残るだけで、結果には響かない）。 */
    static final int SWEEP_EVERY = 8;

    /** 切り分け用: false なら部分木の保持を使わない（結果は同じでなければならない。{@code -Denderframe.paint.nochunks=true} でも切れる）。 */
    static volatile boolean chunksEnabled = !Boolean.getBoolean("enderframe.paint.nochunks");
    /** 1 つの部分木に含める箱の上限。これを超える部分木は保持せず、子ごとに判断する。 */
    static final int CHUNK_MAX_BOXES = 128;
    /** 診断: 部分木を再利用した回数・新しく記録した回数の累計。 */
    static final java.util.concurrent.atomic.LongAdder STAT_CHUNK_REPLAYED = new java.util.concurrent.atomic.LongAdder(),
            STAT_CHUNK_RECORDED = new java.util.concurrent.atomic.LongAdder();

    private final Map<LayoutBox, Chunk> chunks = new IdentityHashMap<>();
    private MemoKeeper memoKeeper;
    private Predicate<LayoutBox> memoizable = b -> false;
    private boolean chunksOn, replayAllowed;
    private int renderSerial, renderGeneration;
    private long renderEpoch;
    private Chunk recording;
    private int scanCount, scanInteractive;
    private long scanMaxStamp;

    void configureChunks(MemoKeeper keeper, Predicate<LayoutBox> memoizable) {
        this.memoKeeper = keeper;
        this.memoizable = memoizable;
    }

    /**
     * 次の {@link #paintTasks} で部分木の保持を使うか。{@code enabled} は、描く箱が全件そろう描画のとき（スクロールの平行移動や
     * 画面外の省略では、項目が欠けたり動いたりするので使わない）。{@code replay} は、変更印で「変わっていない」と言ってよい
     * 描画のとき（属性の変更は印に出ないので、呼び出し側が有効にする）。
     */
    void beginRender(int serial, long epoch, int generation, boolean enabled, boolean replay) {
        chunksOn = chunksEnabled && enabled;
        replayAllowed = replay;
        renderSerial = serial;
        renderEpoch = epoch;
        renderGeneration = generation;
        recording = null;
    }

    /** 描き終えた。木から消えた箱の部分木は捨てる。 */
    void endRender() {
        chunksOn = false;
        recording = null;
        if (!chunks.isEmpty() && renderGeneration % SWEEP_EVERY == 0) {
            int g = renderGeneration;
            chunks.values().removeIf(c -> c.generation != g);
        }
    }

    /**
     * 部分木 {@code box} の走査。箱の数・変更印の最大値・対話ルールを持つ箱の数を数える。箱が多すぎるか、
     * {@code full} のとき保持できない箱があれば false。再利用の判定は {@code full=false}（保持できるかは記録時に確かめてある）。
     */
    private boolean scan(LayoutBox b, boolean full) {
        if (++scanCount > CHUNK_MAX_BOXES) return false;
        if (full && !chunkable(b)) return false;
        if (b.paintStamp > scanMaxStamp) scanMaxStamp = b.paintStamp;
        if (b.hasInteractiveRules) scanInteractive++;
        if (b.display == null || b.display.isNone()) return true;
        List<LayoutBox> kids = b.children;
        for (int i = 0, n = kids.size(); i < n; i++) {
            if (!scan(kids.get(i), full)) return false;
        }
        return true;
    }

    /** 純粋なフローの箱か（保持した項目が、箱の入力だけで決まり、描画順の外へ出ていかない）。 */
    private boolean chunkable(LayoutBox b) {
        if (b.display == null || b.style == null || b.node.tag == null) return false;
        switch (b.node.tag) {
            case "html", "body", "root", "option", "optgroup", "select", "textarea", "input" -> {
                return false;
            }
            default -> { }
        }
        if (!memoizable.test(b)) return false;
        if (b.display.isNone()) return true;
        StyleFacts f = factsOf(b.style);
        if (f.displayNone()) return true;
        if (f.scrollbars() || f.hasAnimation() || !"visible".equals(f.overflowX()) || !"visible".equals(f.overflowY())) return false;
        if (createsStackingContext(b) || isPositioned(b) || BlockLayoutEngine.isFloating(b) || isAtomicInline(b)) return false;
        return !b.node.attrs.containsKey("data-pan-zoom");
    }

    /**
     * 部分木を保持した項目で出す（できれば true）。保持が使えなければ、保持できる部分木なら記録しながら通常の分類をして true、
     * 保持できない部分木は false（呼び出し側が通常の分類をする）。
     */
    private boolean tryChunk(LayoutBox box, ClipRect clip, String parentId, PaintLayers sc, PaintLayers local) {
        Chunk c = chunks.get(box);
        if (c != null && c.complete() && replayAllowed) {
            scanCount = 0;
            scanMaxStamp = 0;
            scanInteractive = 0;
            if (scan(box, false) && c.parentBox == box.parent && c.clip == clip && java.util.Objects.equals(c.parentId, parentId)
                    && scanCount == c.boxCount && scanInteractive == c.interactiveCount
                    && Math.max(scanMaxStamp, box.pathPaintStamp(renderSerial)) <= c.epoch) {
                if (DisplayListBuilder.verifyStamps) verifyChunk(c, box, clip, parentId);
                c.generation = renderGeneration;
                if (!c.blockItems.isEmpty()) local.blocks.add(c.blockNode());
                if (!c.inlineItems.isEmpty()) local.inlines.add(c.inlineNode());
                STAT_CHUNK_REPLAYED.increment();
                return true;
            }
        }
        scanCount = 0;
        scanMaxStamp = 0;
        scanInteractive = 0;
        if (!scan(box, true)) return false;
        Chunk fresh = new Chunk(box.parent, clip, parentId, renderEpoch, scanCount, scanInteractive);
        fresh.generation = renderGeneration;
        chunks.put(box, fresh);
        recording = fresh;
        try {
            classifyPlain(box, clip, parentId, sc, local);
        } finally {
            recording = null;
        }
        STAT_CHUNK_RECORDED.increment();
        return true;
    }

    /** 検証: 保持した項目が、通常の分類で作り直した項目と（層ごとに）同じであること。 */
    private void verifyChunk(Chunk c, LayoutBox box, ClipRect clip, String parentId) {
        boolean was = chunksOn;
        chunksOn = false;
        PaintLayers tl = new PaintLayers();
        List<DisplayItem> blocks = new ArrayList<>(), inlines = new ArrayList<>();
        try {
            classifyPlain(box, clip, parentId, tl, tl);
        } finally {
            chunksOn = was;
        }
        if (!tl.negative.isEmpty() || !tl.floats.isEmpty() || !tl.positioned.isEmpty() || !tl.positive.isEmpty() || !tl.after.isEmpty()) {
            throw new AssertionError("保持した部分木が純粋なフローではない: " + box.node.tag + "#" + box.node.key());
        }
        runTasks(tl.blocks, blocks);
        runTasks(tl.inlines, inlines);
        if (!blocks.equals(c.blockItems) || !inlines.equals(c.inlineItems)) {
            throw new AssertionError("保持した部分木の項目が作り直した項目と違う: " + box.node.tag + "#" + box.node.key()
                    + " blocks " + c.blockItems.size() + "/" + blocks.size() + " inlines " + c.inlineItems.size() + "/" + inlines.size());
        }
    }

    private static void runTasks(List<PaintNode> nodes, List<DisplayItem> out) {
        List<RenderTask> tasks = new ArrayList<>();
        for (PaintNode n : nodes) emit(n, tasks);
        for (RenderTask t : tasks) t.drawAction().accept(out);
    }

    /** 箱が中身の節を持つスクロールコンテナなら、その記録。 */
    ScrollContent scrollContentOf(LayoutBox box) {
        return scrollContentOf.get(box);
    }

    /** 直近の {@link #paintTasks} で中身の節を作ったスクロールコンテナの記録（描いたあとに読む）。 */
    List<com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo> scrollContentInfos() {
        List<com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo> out = new ArrayList<>(scrollContents.size());
        for (ScrollContent c : scrollContents) {
            out.add(new com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo(c.box.node, c.contentId, c.thumbVId, c.thumbHId,
                    c.sx0, c.sy0, c.kx, c.ky));
        }
        return out;
    }

    /**
     * スクロールするだけで中身が正しく動くコンテナか: スクロールコンテナで、中身に sticky・固定配置・包含ブロックがコンテナの外の
     * 絶対配置（スクロールに動かされない）が無い。そうした子孫があると、中身の節を動かすと位置が合わないので、従来の作り直しにする。
     */
    private static boolean scrollsAsOneNode(LayoutBox box) {
        if (box.isAnonymous || box.w <= 0 || box.h <= 0 || !box.isScrollContainer()) return false;
        String tag = box.node.tag;
        if ("root".equals(tag) || "html".equals(tag) || "body".equals(tag)) return false;
        return !hasUnscrollableDescendant(box, box);
    }

    private static boolean hasUnscrollableDescendant(LayoutBox container, LayoutBox box) {
        for (LayoutBox child : box.children) {
            if (child.display != null && child.display.isNone()) continue;
            if (child.isFixed || child.isSticky) return true;
            if (child.isAbsolute) {
                LayoutBox cb = child.findContainingBlock();
                if (cb == null) return true;
                boolean inside = false;
                for (LayoutBox a = cb; a != null; a = a.parent) {
                    if (a == container) {
                        inside = true;
                        break;
                    }
                }
                if (!inside) return true;
            }
            if (hasUnscrollableDescendant(container, child)) return true;
        }
        return false;
    }

    PaintOrder(int rootW, int rootH, ScrollState scrollState, AtomicInteger fragIds, BoxPainter painter,
               Predicate<LayoutBox> animatesOpacity) {
        this.rootW = rootW;
        this.rootH = rootH;
        this.scrollState = scrollState;
        this.fragIds = fragIds;
        this.painter = painter;
        this.animatesOpacity = animatesOpacity;
    }

    /**
     * 箱の描画順（先に描くものが先頭）。{@link #render} と同じ並べ替えを通す。
     *
     * 忠実度コーパスが z-order をブラウザと突き合わせるための読み口
     * （{@code FidelityDumper} の paint 列）。描画順の決め方を変えるときは
     * ここも同じ経路を通るので、テストが描画と食い違うことはない。
     */
    public List<LayoutBox> paintOrder(LayoutBox root) {
        List<LayoutBox> order = new ArrayList<>();
        for (RenderTask task : paintTasks(root)) {
            if (task.box() != null) order.add(task.box());
        }
        return order;
    }

    static LayoutBox canvasBackgroundSource(LayoutBox root) {
        LayoutBox html = childWithTag(root, "html");
        if (html != null && hasBackground(html)) return html;
        LayoutBox body = childWithTag(html != null ? html : root, "body");
        return body != null && hasBackground(body) ? body : null;
    }

    static LayoutBox childWithTag(LayoutBox parent, String tag) {
        for (LayoutBox child : parent.children) {
            if (tag.equals(child.node.tag)) return child;
        }
        return null;
    }

    static boolean hasBackground(LayoutBox box) {
        return box.style.containsKey(CssProperty.BACKGROUND_COLOR) || box.style.containsKey(CssProperty.BACKGROUND);
    }

    // ==========================================
    // 描画順（CSS 2.2 付録 E）
    // ==========================================

    /**
     * 描画順に並べたタスク。ボックスツリーからスタッキングコンテキストの木を組み、付録 E の層の順に平らにする。
     *
     * 何が壊れていたか: 以前は z-index を親に足し込んだ値と「通常フロー / float / 位置指定」の 3 層で
     * 全体を 1 回並べ替えていた。スタッキングコンテキストが無いので中の z-index が外へ漏れ
     * （z-index: 1 の箱の中の z-index: 100 が外の z-index: 2 を越えた）、opacity と transform は
     * コンテキストを作らず、位置指定されていないブロックの z-index も効き、
     * コンテキストの中の負の z-index は親の背景より下に潜っていた。
     */
    List<RenderTask> paintTasks(LayoutBox root) {
        fragIds.set(0);
        scrollContentOf.clear();
        scrollContents.clear();
        contentClips.clear();
        blendDescendants.clear();
        PaintNode top;
        if ("root".equals(root.node.tag)) {
            top = new PaintNode(null, true, 0);
            collectChildren(root, null, null, top.layers, top.layers);
        } else {
            top = new PaintNode(selfTask(root, null, null), true, 0);
            collectChildren(root, null, null, top.layers, top.layers);
        }
        List<RenderTask> out = new ArrayList<>();
        emit(top, out);
        return out;
    }

    /**
     * 子を描画の層に振り分ける。
     *
     * @param sc    いちばん近い本物のスタッキングコンテキストの層（負・0・正の z-index と位置指定要素が入る）
     * @param local 今いる層（float・位置指定要素・不可分なインラインは自分の中身だけを持つ疑似コンテキストを作る）
     */
    void collectChildren(LayoutBox box, ClipRect clip, String parentId, PaintLayers sc, PaintLayers local) {
        String tag = box.node.tag;
        if ("select".equalsIgnoreCase(tag) || "textarea".equalsIgnoreCase(tag) || "input".equalsIgnoreCase(tag)) return;

        ClipRect computedClip = computeChildClipRect(box, clip);
        // 切らない箱は親のクリップをそのまま返す（すでにそろえてある）。新しく作った分だけそろえる
        ClipRect childClip = computedClip == clip ? clip : canonical(computedClip);
        contentClips.put(box, childClip);
        // 匿名ボックスは描くものを持たない。子の持ち主は匿名ボックスの外の要素のまま
        String childParent = "root".equals(tag) ? null : box.isAnonymous ? parentId : box.node.key();
        if (DisplayListBuilder.scrollContentNodes && scrollsAsOneNode(box)) {
            // 中身は専用の節の下に置く: スクロールではこの節だけを動かせばよい（箱自身の背景・枠・つまみは動かさない）
            ScrollContent content = new ScrollContent(box, box.node.key() + "_scrollc",
                    scrollState.getScrollX(box.node), scrollState.getScrollY(box.node));
            scrollContentOf.put(box, content);
            scrollContents.add(content);
            childParent = content.contentId;
        }
        for (LayoutBox child : box.children) classify(child, clipForChild(child, childClip), childParent, sc, local);

        boolean scrollbars = factsOf(box.style).scrollbars();
        if (!"root".equals(tag) && box.w > 0 && box.h > 0 && scrollbars && !isInvisible(box)) {
            local.after.add(new RenderTask(null, elements -> drawScrollbarsIfNeeded(elements, box, clip)));
        }
    }

    /**
     * 子に掛かるクリップ。overflow が切るのは自分が包含ブロックの祖先になっている子孫だけ（CSS 2.2 §11.1.1）なので、
     * 絶対配置の箱は木の親ではなく包含ブロックが中身に掛けるクリップを使う。固定配置（包含ブロックがビューポート）は切られない。
     *
     * 何が壊れていたか: 以前は親のクリップを木のすべての子へそのまま渡していたので、位置指定の無い
     * {@code overflow: hidden} の箱の中のドロップダウンやモーダル（absolute / fixed）が箱の枠で切り落とされていた。
     */
    ClipRect clipForChild(LayoutBox child, ClipRect flowClip) {
        if (!child.isAbsolute) return flowClip;
        LayoutBox cb = child.findContainingBlock();
        if (cb == null) return null;
        return contentClips.containsKey(cb) ? contentClips.get(cb) : flowClip;
    }

    /**
     * 付録 E の分類。スタッキングコンテキストは z-index の符号で親のコンテキストの層 2 / 8 / 9 に入り、
     * 中身を自分の層に閉じ込める。z-index: auto の位置指定要素（層 8）・float（層 5）・
     * 不可分なインラインと flex / grid アイテム（層 7）は「コンテキストを作ったかのように」中身ごと描くが、
     * 中の位置指定要素と本物のコンテキストは親のコンテキストに属する。
     */
    void classify(LayoutBox box, ClipRect clip, String parentId, PaintLayers sc, PaintLayers local) {
        if (box.display.isNone() || factsOf(box.style).displayNone()) return;
        if ("option".equalsIgnoreCase(box.node.tag)) return;
        // 記録の途中（部分木の中）では、中の箱を別に保持しない
        if (chunksOn && recording == null && tryChunk(box, clip, parentId, sc, local)) return;
        classifyPlain(box, clip, parentId, sc, local);
    }

    /** {@link #classify} の本体（部分木の保持を試したあとの、通常の分類）。 */
    private void classifyPlain(LayoutBox box, ClipRect clip, String parentId, PaintLayers sc, PaintLayers local) {
        RenderTask self = selfTask(box, clip, parentId);
        if (createsStackingContext(box)) {
            int z = isZIndexAuto(box) ? 0 : box.zIndex;
            PaintNode node = PaintNode.context(self, z, self != null ? layerSpecFor(box) : null, box);
            (z < 0 ? sc.negative : z > 0 ? sc.positive : sc.positioned).add(node);
            collectChildren(box, clip, parentId, node.layers, node.layers);
        } else if (isPositioned(box)) {
            // 先に入れてから中身を集める（中の位置指定要素は木の順で後ろに並ぶ）
            PaintNode node = new PaintNode(self, false, 0);
            sc.positioned.add(node);
            collectChildren(box, clip, parentId, sc, node.layers);
        } else if (BlockLayoutEngine.isFloating(box)) {
            PaintNode node = new PaintNode(self, false, 0);
            local.floats.add(node);
            collectChildren(box, clip, parentId, sc, node.layers);
        } else if (isAtomicInline(box)) {
            PaintNode node = new PaintNode(self, false, 0);
            local.inlines.add(node);
            collectChildren(box, clip, parentId, sc, node.layers);
        } else {
            PaintNode leaf = new PaintNode(self, false, 0, null);
            (box.display.isInlineLevel() ? local.inlines : local.blocks).add(leaf);
            collectChildren(box, clip, parentId, sc, local);
        }
    }

    /** 付録 E の層の順に平らにする。z-index の並べ替えは安定（同じ値なら木の順）。 */
    static void emit(PaintNode node, List<RenderTask> out) {
        // レイヤーに描く箱は、錨から子孫の最後までを開始と終了の印で挟む（スタッキングコンテキストの木は連続している）
        if (node.layerSpec != null) out.add(RenderTask.layerStart(node.layerSpec, node.layerBox));
        if (node.self != null) out.add(node.self);
        PaintLayers l = node.layers;
        if (l == null) {
            if (node.layerSpec != null) out.add(RenderTask.LAYER_END);
            return;
        }
        if (node.stackingContext) {
            l.negative.sort(Comparator.comparingInt(PaintNode::z));
            for (PaintNode n : l.negative) emit(n, out);
        }
        for (PaintNode n : l.blocks) emit(n, out);
        for (PaintNode n : l.floats) emit(n, out);
        for (PaintNode n : l.inlines) emit(n, out);
        if (node.stackingContext) {
            for (PaintNode n : l.positioned) emit(n, out);
            l.positive.sort(Comparator.comparingInt(PaintNode::z));
            for (PaintNode n : l.positive) emit(n, out);
        }
        out.addAll(l.after);
        if (node.layerSpec != null) out.add(RenderTask.LAYER_END);
    }

    RenderTask selfTask(LayoutBox box, ClipRect clip, String parentId) {
        if (box.w <= 0 || box.h <= 0 || box.isAnonymous) return null;
        Chunk rec = recording;
        if (rec == null) return new RenderTask(box, elements -> painter.draw(elements, box, clip, parentId));
        // 部分木を記録している間は、描いた項目を（入る層ごとに）保持へ写す。保持できる部分木の箱は全て通常のブロック／インラインの葉
        boolean inline = box.display.isInlineLevel();
        rec.created++;
        return new RenderTask(box, elements -> {
            int start = elements.size();
            painter.draw(elements, box, clip, parentId);
            List<DisplayItem> dst = inline ? rec.inlineItems : rec.blockItems;
            for (int i = start, n = elements.size(); i < n; i++) dst.add(elements.get(i));
            if (memoKeeper != null) memoKeeper.owned(box, rec);
            rec.executed++;
        });
    }

    static boolean isPositioned(LayoutBox box) {
        return box.isRelative || box.isAbsolute || box.isSticky;
    }

    static boolean isZIndexAuto(LayoutBox box) {
        return factsOf(box.style).zAuto();
    }

    /**
     * 算出スタイルだけから決まる、描画順の分類に使う値。箱ごとに十数回の文字列引きをしていた所を、スタイルごとに一度で済ませる
     * （算出スタイルは不変で、箱の木が変わっても同じインスタンスなら使い回せる）。
     */
    record StyleFacts(boolean displayNone, boolean zAuto, float opacity, boolean stackingByStyle, boolean hasMask,
                      String overflowX, String overflowY, boolean scrollbars, boolean hasAnimation) {
    }

    /** 診断: {@link StyleFacts} を新しく算出した回数（キャッシュに当たらなかった回数）。 */
    static final java.util.concurrent.atomic.LongAdder STAT_FACTS_COMPUTED = new java.util.concurrent.atomic.LongAdder();

    static StyleFacts factsOf(com.nishiyu.enderframelib.core.css.ComputedStyle style) {
        if (style.derivedFacts() instanceof StyleFacts cached) return cached;
        STAT_FACTS_COMPUTED.increment();
        String z = style.get(CssProperty.Z_INDEX);
        boolean zAuto = z == null || z.isBlank() || "auto".equalsIgnoreCase(z.trim());
        boolean mask = styleHasMask(style);
        boolean stacking = !isNone(style.get(CssProperty.TRANSFORM)) || !isNone(style.get("filter"))
                || !isNone(style.get("clip-path")) || !isNone(style.get("backdrop-filter")) || mask
                || style.keywordOf("isolation", CssKeywords.Isolation.class) == CssKeywords.Isolation.ISOLATE
                || !"normal".equals(style.keyword("mix-blend-mode", "normal"));
        String[] overflow = overflowPair(style);
        StyleFacts facts = new StyleFacts("none".equals(style.getOrDefault(CssProperty.DISPLAY, "block")), zAuto,
                style.number(CssProperty.OPACITY, 1f), stacking, mask, overflow[0], overflow[1],
                isScrollbarOverflow(overflow[0]) || isScrollbarOverflow(overflow[1]),
                style.get(CssProperty.ANIMATION) != null || style.get(CssProperty.ANIMATION_NAME) != null);
        style.derivedFacts(facts);
        return facts;
    }

    /**
     * スタッキングコンテキストを作るか（CSS 2.2 §9.9.1 と CSS Positioned Layout 3 / Color 4 /
     * Compositing 1 / Transforms 1 の追加分）。
     */
    boolean createsStackingContext(LayoutBox box) {
        StyleFacts f = factsOf(box.style);
        boolean zAuto = f.zAuto();
        if (isPositioned(box) && !zAuto) return true;
        if (box.isFixed || box.isSticky) return true;
        if (!zAuto && isFlexOrGridItem(box)) return true;
        if (f.opacity() < 1f) return true;
        // opacity を動かすアニメーションは、静的な値が 1 でも動いている間スタッキングコンテキストを作る（CSS Positioned Layout 3 §2）
        if (animatesOpacity.test(box)) return true;
        // transform・filter・clip-path・mask・backdrop-filter・isolation・mix-blend-mode（CSS Masking 1 / Filter Effects 2 / Compositing 1）
        return f.stackingByStyle();
    }

    /**
     * スタッキングコンテキストの箱をレイヤーに描いて合成する指定。グループの不透明度・{@code filter}・{@code mix-blend-mode}、
     * 子孫の混合を閉じ込める必要があるとき（{@code isolation: isolate} か、{@code mix-blend-mode} の子孫を含む）に作る。
     * どれも無ければ null（直に描く）。{@code span} は {@link DisplayListBuilder} が項目を出し終えてから埋める。
     */
    LayerSpec layerSpecFor(LayoutBox box) {
        float opacity = box.style.number(CssProperty.OPACITY, 1f);
        boolean opacityMoves = animatesOpacity.test(box);
        List<FilterOp> filters = FilterOp.parse(box.style.get("filter"), new FilterOp.Context(rootW, rootH,
                box.style.fontSizePx(), box.style.rootFontSizePx(), box.style.color(CssProperty.COLOR, 0xFF000000)));
        BlendMode blend = BlendMode.fromCss(box.style.keyword("mix-blend-mode", "normal"));
        boolean isolate = hasBlendDescendant(box);

        float fontPx = box.style.fontSizePx(), rootFontPx = box.style.rootFontSizePx();
        float[] border = {box.x, box.y, box.w, box.h};
        float[] padding = {box.x + box.bL, box.y + box.bT, Math.max(0, box.w - box.bL - box.bR), Math.max(0, box.h - box.bT - box.bB)};
        float[] content = {padding[0] + box.pL, padding[1] + box.pT, Math.max(0, padding[2] - box.pL - box.pR), Math.max(0, padding[3] - box.pT - box.pB)};
        ClipShape clip = ClipShape.parse(box.style.get("clip-path"),
                ClipShape.Context.of(border, padding, content, rootW, rootH, fontPx, rootFontPx));

        boolean masked = hasMask(box);
        boolean luminance = masked && !BoxDecorations.maskModes(box.style).isEmpty()
                && "luminance".equals(BoxDecorations.maskModes(box.style).getFirst());

        List<FilterOp> backdrop = FilterOp.parse(box.style.get("backdrop-filter"), new FilterOp.Context(rootW, rootH, fontPx, rootFontPx,
                box.style.color(CssProperty.COLOR, 0xFF000000)));
        ClipShape backdropShape = null;
        if (!backdrop.isEmpty()) {
            float[] r = LengthResolver.cornerRadiiXY(box.style, box.w, box.h, rootW, rootH);
            float[] d = new float[12];
            d[0] = box.x;
            d[1] = box.y;
            d[2] = box.x + box.w;
            d[3] = box.y + box.h;
            System.arraycopy(r, 0, d, 4, 8);
            backdropShape = new ClipShape(ClipShape.Kind.INSET, d, false);
        }

        if (opacity >= 1f && !opacityMoves && filters.isEmpty() && blend == BlendMode.NORMAL && !isolate
                && clip == null && !masked && backdrop.isEmpty()) {
            return null;
        }
        return new LayerSpec(0, opacity, filters, blend, isolate, clip, masked ? LayerSpec.WANTS_MASK : -1, luminance, backdrop, backdropShape);
    }

    /** {@code mask-image} に画像かグラデーションのレイヤーがあるか。 */
    static boolean hasMask(LayoutBox box) {
        return factsOf(box.style).hasMask();
    }

    private static boolean styleHasMask(com.nishiyu.enderframelib.core.css.ComputedStyle style) {
        if (BoxDecorations.clipsText(style)) return true; // background-clip: text は文字のアルファを写すマスク
        for (BoxDecorations.BackgroundLayer layer : BoxDecorations.maskLayers(style)) {
            if (layer.url() != null || layer.isGradient()) return true;
        }
        return false;
    }

    /**
     * 子孫に {@code mix-blend-mode} を持つ箱があるか。混合は自分を含む最も近いスタッキングコンテキストの中で起こるので、
     * 途中にスタッキングコンテキストを作る子孫があれば、その箱自身の混合までを数え、中は数えない（その箱が閉じ込める）。
     */
    private boolean hasBlendDescendant(LayoutBox box) {
        Boolean cached = blendDescendants.get(box);
        if (cached != null) return cached;
        boolean found = false;
        for (LayoutBox child : box.children) {
            if (child.display.isNone()) continue;
            if (!"normal".equals(child.style.keyword("mix-blend-mode", "normal"))) {
                found = true;
                break;
            }
            if (!createsStackingContext(child) && hasBlendDescendant(child)) {
                found = true;
                break;
            }
        }
        blendDescendants.put(box, found);
        return found;
    }

    static boolean isNone(String value) {
        return value == null || value.isBlank() || "none".equalsIgnoreCase(value.trim());
    }

    /** スタッキングコンテキスト（または疑似コンテキスト）の中の層。番号は付録 E の手順。 */
    static final class PaintLayers {
        final List<PaintNode> negative = new ArrayList<>();   // 2: 負の z-index のコンテキスト
        final List<PaintNode> blocks = new ArrayList<>();     // 4: 通常フローのブロックレベル
        final List<PaintNode> floats = new ArrayList<>();     // 5: float
        final List<PaintNode> inlines = new ArrayList<>();    // 7: インラインレベル
        final List<PaintNode> positioned = new ArrayList<>(); // 8: z-index: auto / 0 の位置指定要素とコンテキスト
        final List<PaintNode> positive = new ArrayList<>();   // 9: 正の z-index のコンテキスト
        final List<RenderTask> after = new ArrayList<>();     // スクロールバー（中身より上）
    }

    /**
     * {@code layers} が null なら箱自身だけ（中身は今の層に流れている）。{@code layerSpec} があれば、この箱は
     * レイヤーに描いて合成する（スタッキングコンテキストだけが持つ）。
     */
    record PaintNode(RenderTask self, boolean stackingContext, int z, PaintLayers layers, LayerSpec layerSpec, LayoutBox layerBox) {
        PaintNode(RenderTask self, boolean stackingContext, int z, PaintLayers layers) {
            this(self, stackingContext, z, layers, null, null);
        }

        PaintNode(RenderTask self, boolean stackingContext, int z) {
            this(self, stackingContext, z, new PaintLayers(), null, null);
        }

        static PaintNode context(RenderTask self, int z, LayerSpec layerSpec, LayoutBox layerBox) {
            return new PaintNode(self, true, z, new PaintLayers(), layerSpec, layerBox);
        }
    }

    /** 切らない軸のクリップの端（描画とヒットテストの座標に十分遠い値）。 */
    static final float UNCLIPPED = 1.0e6f;

    /**
     * 中身に掛けるクリップ（パディングボックスの角丸矩形と祖先のクリップの交わり）。
     *
     * {@code overflow} はロングハンドの {@code overflow-x} / {@code overflow-y} から読む（ショートハンドは
     * 算出の段で展開済み）。片方の軸だけが切るときはもう片方は祖先のクリップのまま。
     *
     * 何が壊れていたか: 以前はショートハンドの {@code overflow} だけを読んでいたので、展開後は常に
     * visible 扱いになり、{@code overflow: hidden} も {@code overflow-y: auto} も子を切らなかった。
     */
    ClipRect computeChildClipRect(LayoutBox box, ClipRect parentClip) {
        String tag = box.node.tag;
        boolean isRootElement = "root".equals(tag) || "html".equals(tag) || "body".equals(tag);
        if (isRootElement) return parentClip;

        boolean panZoom = "true".equalsIgnoreCase(box.node.attrs.get("data-pan-zoom"));
        StyleFacts facts = factsOf(box.style);
        boolean clipX = panZoom || !"visible".equals(facts.overflowX());
        boolean clipY = panZoom || !"visible".equals(facts.overflowY());
        if (!clipX && !clipY) return parentClip;

        float minX = clipX ? box.x + box.bL : -UNCLIPPED;
        float minY = clipY ? box.y + box.bT : -UNCLIPPED;
        float maxX = clipX ? box.x + box.w - box.bR : UNCLIPPED;
        float maxY = clipY ? box.y + box.h - box.bB : UNCLIPPED;

        // 内側の角は外側の半径から隣り合う辺の太さを引いたもの（角丸は両軸を切るときだけ）
        float[] own = {minX, minY, maxX, maxY, 0, 0, 0, 0};
        if (clipX && clipY) {
            float[] r = LengthResolver.cornerRadiiXY(box.style, box.w, box.h, rootW, rootH);
            boolean elliptical = false;
            for (int i = 0; i < 4; i++) elliptical |= Math.round(r[i]) != Math.round(r[4 + i]);
            if (!elliptical) {
                own[4] = Math.min(r[0], r[4]) - Math.max(box.bT, box.bL);
                own[5] = Math.min(r[1], r[5]) - Math.max(box.bT, box.bR);
                own[6] = Math.min(r[2], r[6]) - Math.max(box.bB, box.bR);
                own[7] = Math.min(r[3], r[7]) - Math.max(box.bB, box.bL);
            } else {
                // 楕円の角は、内側の角を軸ごとに縮める（横は左右の枠線、縦は上下の枠線の太さ）
                own = java.util.Arrays.copyOf(own, 12);
                float[] bx = {box.bL, box.bR, box.bR, box.bL}, by = {box.bT, box.bT, box.bB, box.bB};
                for (int i = 0; i < 4; i++) {
                    own[4 + i] = Math.max(0, r[i] - bx[i]);
                    own[8 + i] = Math.max(0, r[4 + i] - by[i]);
                }
            }
        }
        ClipRect merged = ClipRect.fromArray(BoxDecorations.intersectClip(parentClip == null ? null : parentClip.toArray(), own));
        // 合成した矩形に、合成する前のこの箱の矩形を持ち主（この箱）つきで足す。箱が動く（変形する）と、切り口も一緒に動く
        return merged.withChain(parentClip == null ? List.of(new ClipChainPart(own, box.node.key())) : parentClip.chainPlus(own, box.node.key()));
    }

    /**
     * {@code overflow-x} / {@code overflow-y} の使用値。片方が visible / clip でもう片方がそうでなければ、
     * visible は auto、clip は hidden として扱う（CSS Overflow 3 §3.1）。
     */
    static String[] overflowPair(LayoutBox box) {
        StyleFacts f = factsOf(box.style);
        return new String[]{f.overflowX(), f.overflowY()};
    }

    private static String[] overflowPair(com.nishiyu.enderframelib.core.css.ComputedStyle style) {
        String x = style.overflowX().cssName();
        String y = style.overflowY().cssName();
        boolean xPlain = "visible".equals(x) || "clip".equals(x);
        boolean yPlain = "visible".equals(y) || "clip".equals(y);
        if (xPlain != yPlain) {
            if (xPlain) x = "visible".equals(x) ? "auto" : "hidden";
            else y = "visible".equals(y) ? "auto" : "hidden";
        }
        return new String[]{x, y};
    }

    static boolean isScrollbarOverflow(String overflow) {
        return "scroll".equals(overflow) || "auto".equals(overflow);
    }

    /**
     * 子の項目をまとめて動かすための、見えない節（箱の下に置く。クリックも受けない）。移動量はあとから
     * {@code UiView#setPaintOffset} で足す。id が持ち主の節の id と違うので、持ち主の節の検索には現れない。
     */
    void addMovingNode(List<DisplayItem> elements, String id, LayoutBox box, RenderMeta meta) {
        ItemProps events = new ItemProps();
        events.parentId(box.node.key());
        events.pointerEventsNone();
        addElement(elements, id, id, ElementType.NODE, box.x, box.y, box.w, box.h, "", 0, 0, events, 0, 1f, 0, 0, 1f, 1f, 0, 0,
                AnimationSpec.NONE, meta == null ? RenderMeta.EMPTY : new RenderMeta(null, null, null, 12.0f, 0.5f, 0.5f, 1, false));
    }

    void drawScrollbarsIfNeeded(List<DisplayItem> elements, LayoutBox box, ClipRect clipRect) {
        if (box.style.keywordOf(CssProperty.SCROLLBAR_WIDTH, CssKeywords.ScrollbarWidth.class) == CssKeywords.ScrollbarWidth.NONE) return;

        float scrollW = box.scrollWidth;
        float scrollH = box.scrollHeight;
        float curX = scrollState.getScrollX(box.node);
        float curY = scrollState.getScrollY(box.node);

        ItemProps sbEvents = new ItemProps();
        sbEvents.parentId(box.node.key());
        ScrollContent content = scrollContentOf.get(box);
        ClipRect sbClip = clipRect;
        RenderMeta sbMeta = new RenderMeta(sbClip, new Corners(2, 2, 2, 2), null, 12.0f, 0.5f, 0.5f, 1, false);

        int barThickness = 4;
        if (scrollH > box.h + 2 && box.h > 12) {
            float trackX = box.x + box.w - box.bR - barThickness - 2;
            float trackY = box.y + box.bT + 2;
            float trackH = box.h - box.bT - box.bB - 4;
            float thumbH = Math.max(12, trackH * (box.h / scrollH));
            float maxScrollY = Math.max(1f, scrollH - box.h);
            float thumbY = trackY + (trackH - thumbH) * Math.clamp(curY / maxScrollY, 0f, 1f);

            addElement(elements, box.node.key(), box.node.key() + "_sb_vt", ElementType.RECT, trackX, trackY, barThickness, trackH, "", 0, 0x33000000, sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, sbMeta);
            ItemProps thumbEvents = sbEvents;
            if (content != null) { // つまみは、スクロール量に比例して動く節の下に置く（スクロールのたびに作り直さない）
                content.thumbVId = box.node.key() + "_scrollv";
                content.ky = (trackH - thumbH) / maxScrollY;
                addMovingNode(elements, content.thumbVId, box, sbMeta);
                thumbEvents = new ItemProps();
                thumbEvents.parentId(content.thumbVId);
            }
            addElement(elements, box.node.key(), box.node.key() + "_sb_v", ElementType.RECT, trackX, thumbY, barThickness, thumbH, "", 0, 0x88CCCCCC, thumbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, sbMeta);
        }
        if (scrollW > box.w + 2 && box.w > 12) {
            float trackX = box.x + box.bL + 2;
            float trackY = box.y + box.h - box.bB - barThickness - 2;
            float trackW = box.w - box.bL - box.bR - 4;
            float thumbW = Math.max(12, trackW * (box.w / scrollW));
            float maxScrollX = Math.max(1f, scrollW - box.w);
            float thumbX = trackX + (trackW - thumbW) * Math.clamp(curX / maxScrollX, 0f, 1f);

            addElement(elements, box.node.key(), box.node.key() + "_sb_ht", ElementType.RECT, trackX, trackY, trackW, barThickness, "", 0, 0x33000000, sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, sbMeta);
            ItemProps thumbEvents = sbEvents;
            if (content != null) {
                content.thumbHId = box.node.key() + "_scrollh";
                content.kx = (trackW - thumbW) / maxScrollX;
                addMovingNode(elements, content.thumbHId, box, sbMeta);
                thumbEvents = new ItemProps();
                thumbEvents.parentId(content.thumbHId);
            }
            addElement(elements, box.node.key(), box.node.key() + "_sb_h", ElementType.RECT, thumbX, trackY, thumbW, barThickness, "", 0, 0x88CCCCCC, thumbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, sbMeta);
        }
    }

    /**
     * {@code box} は箱自身を描くタスクだけが持つ（スクロールバーは null）。{@code layerStart} はレイヤーの開始の印
     * （次に出る錨から {@link #LAYER_END} までがレイヤーの中身）、{@code layerEnd} は終了の印。印は項目を出さない。
     */
    record RenderTask(LayoutBox box, Consumer<List<DisplayItem>> drawAction, LayerSpec layerStart, boolean layerEnd, LayoutBox layerBox) {
        static final RenderTask LAYER_END = new RenderTask(null, elements -> {}, null, true, null);

        RenderTask(LayoutBox box, Consumer<List<DisplayItem>> drawAction) {
            this(box, drawAction, null, false, null);
        }

        /** {@code layerBox} は、レイヤーの終わりにマスクの項目を足すときの持ち主の箱。 */
        static RenderTask layerStart(LayerSpec spec, LayoutBox layerBox) {
            return new RenderTask(null, elements -> {}, spec, false, layerBox);
        }
    }
}
