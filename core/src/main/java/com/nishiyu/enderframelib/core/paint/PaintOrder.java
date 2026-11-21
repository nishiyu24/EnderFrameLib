package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.BlendMode;
import com.nishiyu.enderframelib.core.paint.model.ClipShape;
import com.nishiyu.enderframelib.core.paint.model.FilterOp;
import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.css.model.CssKeywords;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.layout.BlockLayoutEngine;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.Corners;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.css.ColorParser;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import com.nishiyu.enderframelib.core.paint.model.*;
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
public final class PaintOrder {
    /** 箱 1 つ自身（背景・枠・部品）を描画項目にする。 */
    @FunctionalInterface
    interface BoxPainter {
        void draw(List<DisplayItem> elements, LayoutBox box, ClipRect clipRect, String parentId);
    }

    private int rootW;
    private int rootH;
    private final ScrollState scrollState;
    private final AtomicInteger fragIds;
    private final BoxPainter painter;
    /**
     * 箱が中身に掛けるクリップ（祖先のクリップと自分の overflow の交わり。null なら切らない）。
     * 絶対配置の箱は木の親ではなく包含ブロックの値を引く。
     */
    private final Map<LayoutBox, ClipRect> contentClips = new IdentityHashMap<>();
    /**
     * 値として等しいクリップを同じ実体にそろえる表。箱ごとの断片の記憶（{@code FragmentPaint}）はクリップを覚えていて、
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
        /** 作ったときのつまみの位置の、丸めの端数（{@code 位置 - round(位置)}）。平行移動を作り直しと同じ丸めにする。 */
        float fx0, fy0;
        /** 中身の中の sticky の箱（B12。スクロールだけの更新で節に貼り付きのずれの増減を足す）。 */
        List<com.nishiyu.enderframelib.core.paint.model.StickyShift> stickies = List.of();

        ScrollContent(LayoutBox box, String contentId, float sx0, float sy0) {
            this.box = box;
            this.contentId = contentId;
            this.sx0 = sx0;
            this.sy0 = sy0;
        }
    }

    private final java.util.IdentityHashMap<LayoutBox, ScrollContent> scrollContentOf = new java.util.IdentityHashMap<>();
    private final List<ScrollContent> scrollContents = new ArrayList<>();
    private List<com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo> cachedScrollInfos;
    private List<com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo> prevScrollInfos;

    // ==========================================
    // 部分木の保持（チャンク）
    // ==========================================
    //
    // 描画は毎回、全ての箱を分類して描画順のタスクを作り直す。1 か所の文字を変えただけでも、5000 行のリストなら 5000 行ぶんの
    // 分類木・タスク・ラムダを作り、断片の記憶と突き合わせる（局所更新の約 6 割）。
    // そこで、小さくて「純粋なフロー」の部分木（スタッキングコンテキスト・位置指定・float・スクロール・アニメーション・表・入力部品を
    // 含まない）の項目を、層（blocks / inlines）ごとに保持する。部分木のどの箱にも変更印が付いておらず、親・クリップ・親の id・
    // 箱の数が同じなら、分類も箱の照合も飛ばして、保持した項目をそのまま出す。
    // 描画順の決まり方は変えない: 部分木の項目は、分類したときと同じ層の同じ位置に出る。


    private final ItemProps scratchMovingProps = new ItemProps();
    private final ItemProps scratchSbProps = new ItemProps();
    private final ItemProps scratchThumbProps = new ItemProps();
    private boolean parentRelative;
    void setParentRelative(boolean parentRelative) {
        this.parentRelative = parentRelative;
    }

    static boolean boxHasOwnNode(LayoutBox b) {
        return b != null && !b.isAnonymous && b.w() > 0 && b.h() > 0 && b.node != null
                && !"root".equals(b.node.tag) && !"#text".equals(b.node.tag);
    }

    /** 項目が親として辿る実 {@code ElementType.NODE} を持つ祖先の箱（匿名・root・0サイズ箱はスキップ）。 */
    static LayoutBox effectiveParentNodeBox(LayoutBox box, String parentId) {
        if (parentId == null || box == null) return null;
        for (LayoutBox a = box.parent; a != null; a = a.parent) {
            if (a.node != null && "root".equals(a.node.tag)) return null;
            if (a.isAnonymous) continue;
            // 窓で項目を省いた祖先（paintCulled）の NODE は表示リストに無い。子はその親を引けず根の座標で描かれるので、原点は 0（絶対）
            return boxHasOwnNode(a) && !a.paintCulled ? a : null;
        }
        return null;
    }

    /** 箱が中身の節を持つスクロールコンテナなら、その記録。 */
    ScrollContent scrollContentOf(LayoutBox box) {
        return scrollContentOf.get(box);
    }

    ClipRect contentClipOf(LayoutBox box) {
        if (box == null) return null;
        if (contentClips.containsKey(box)) return contentClips.get(box);
        ClipRect parentClip = box.parent != null ? contentClipOf(box.parent) : null;
        ClipRect computed = computeChildClipRect(box, parentClip);
        ClipRect res = computed == parentClip ? parentClip : canonical(computed);
        contentClips.put(box, res);
        return res;
    }

    void clearClips() {
        contentClips.clear();
    }

    /** 直近の {@link #paintTasks} で中身の節を作ったスクロールコンテナの記録（描いたあとに読む）。 */
    List<com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo> scrollContentInfos() {
        List<com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo> cached = cachedScrollInfos;
        if (cached != null) return cached;
        int n = scrollContents.size();
        if (n == 0) return cachedScrollInfos = List.of();
        List<com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo> prev = prevScrollInfos;
        if (prev != null && prev.size() == n) {
            boolean allSame = true;
            for (int i = 0; i < n; i++) {
                ScrollContent c = scrollContents.get(i);
                com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo p = prev.get(i);
                if (c.box.node != p.node()
                        || !c.contentId.equals(p.contentId())
                        || !java.util.Objects.equals(c.thumbVId, p.thumbVId())
                        || !java.util.Objects.equals(c.thumbHId, p.thumbHId())
                        || Float.compare(c.sx0, p.sx0()) != 0
                        || Float.compare(c.sy0, p.sy0()) != 0
                        || Float.compare(c.kx, p.kx()) != 0
                        || Float.compare(c.ky, p.ky()) != 0
                        || Float.compare(c.fx0, p.fx0()) != 0
                        || Float.compare(c.fy0, p.fy0()) != 0
                        || !c.stickies.equals(p.stickies())) {
                    allSame = false;
                    break;
                }
            }
            if (allSame) return cachedScrollInfos = prev;
        }
        List<com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ScrollContent c = scrollContents.get(i);
            com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo item = null;
            if (prev != null) {
                for (int j = 0, pn = prev.size(); j < pn; j++) {
                    com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo p = prev.get(j);
                    if (c.box.node == p.node() && c.contentId.equals(p.contentId())
                            && java.util.Objects.equals(c.thumbVId, p.thumbVId())
                            && java.util.Objects.equals(c.thumbHId, p.thumbHId())
                            && Float.compare(c.sx0, p.sx0()) == 0
                            && Float.compare(c.sy0, p.sy0()) == 0
                            && Float.compare(c.kx, p.kx()) == 0
                            && Float.compare(c.ky, p.ky()) == 0
                            && Float.compare(c.fx0, p.fx0()) == 0
                            && Float.compare(c.fy0, p.fy0()) == 0
                            && c.stickies.equals(p.stickies())) {
                        item = p;
                        break;
                    }
                }
            }
            if (item == null) {
                item = new com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo(c.box.node, c.contentId, c.thumbVId, c.thumbHId,
                        c.sx0, c.sy0, c.kx, c.ky, c.fx0, c.fy0, c.stickies);
            }
            out.add(item);
        }
        return cachedScrollInfos = prevScrollInfos = java.util.Collections.unmodifiableList(out);
    }

    /**
     * スクロールするだけで中身が正しく動くコンテナか: スクロールコンテナで、中身に sticky・固定配置・包含ブロックがコンテナの外の
     * 絶対配置（スクロールに動かされない）が無い。そうした子孫があると、中身の節を動かすと位置が合わないので、従来の作り直しにする。
     */

    private boolean scrollsAsOneNode(LayoutBox box) {
        if (box.isAnonymous || box.w() <= 0 || box.h() <= 0 || !box.isScrollContainer()) return false;
        if (box.isVirtualizedFlow() || box.subtreeHasContentAuto()) return false;
        String tag = box.node.tag;
        if ("root".equals(tag) || "html".equals(tag) || "body".equals(tag)) return false;
        // 子孫の全走査になる（大きいスクロールコンテナでは毎回の作り直しの負担）。子の増減や、固定・粘着・絶対配置・包含ブロックに効くスタイルが変わると構造の世代が進むので、進むまで保持する
        long struct = LayoutBox.structEpoch(), container = LayoutBox.containerEpoch();
        ScrollVerdict known = unscrollableVerdict.get(box);
        if (known == null || known.struct != struct || (known.sawAbsolute && known.container != container)) {
            if (unscrollableVerdict.size() > 1024) unscrollableVerdict.clear(); // 消えた箱の分が溜まり続けないように
            scanSawAbsolute = false;
            scanStickies = null;
            boolean bad = hasUnscrollableDescendant(box, box, false);
            known = new ScrollVerdict(!bad, scanSawAbsolute, struct, container, scanStickies == null ? List.of() : List.copyOf(scanStickies));
            unscrollableVerdict.put(box, known);
        }
        lastStickyShifts = List.of();
        if (!known.oneNode) return false;
        if (!known.stickies.isEmpty()) {
            // sticky の箱は、今の配置で式が使えるか（いちばん近いスクロールコンテナ・上の inset だけ・貼り付けたあとに動いていない）を毎回確かめる
            List<com.nishiyu.enderframelib.core.paint.model.StickyShift> shifts = new ArrayList<>(known.stickies.size());
            for (LayoutBox s : known.stickies) {
                float[] p = boxHasOwnNode(s) ? s.stickyScrollParams(box, rootW, rootH) : null;
                if (p == null) return false;
                var shift = new com.nishiyu.enderframelib.core.paint.model.StickyShift(s.node.key(), p[0], p[1], p[2], p[3], p[4], p[5] != 0f, s.stickyDy());
                if (Float.floatToIntBits(shift.dyAt(0f)) != Float.floatToIntBits(s.stickyDy())) return false; // 式がレイアウトと合わない
                shifts.add(shift);
            }
            lastStickyShifts = shifts;
        }
        return true;
    }

    private record ScrollVerdict(boolean oneNode, boolean sawAbsolute, long struct, long container, List<LayoutBox> stickies) {}

    /** {@link #scrollsAsOneNode} が直近に true を返した箱の sticky の式（{@code ScrollContent} を作るときに渡す）。 */
    private List<com.nishiyu.enderframelib.core.paint.model.StickyShift> lastStickyShifts = List.of();
    private List<LayoutBox> scanStickies;

    private final java.util.IdentityHashMap<LayoutBox, ScrollVerdict> unscrollableVerdict = new java.util.IdentityHashMap<>();
    private boolean scanSawAbsolute;

    private boolean hasUnscrollableDescendant(LayoutBox container, LayoutBox box, boolean underSticky) {
        if (box.contentAutoSkipped || box.isVirtualizedFlow()) return box.isVirtualizedFlow();
        for (LayoutBox child : box.children) {
            if (child.display() != null && child.display().isNone()) continue;
            if (child.subtreeHasContentAuto()) return true;
            if (child.isFixed()) return true;
            // sticky は、上の inset だけで、いちばん近いスクロールコンテナがこのコンテナで、sticky の中の sticky でなければ、
            // 節の移動に貼り付きのずれの増減を足して表せる（B12）。それ以外は従来どおり作り直す
            if (child.isSticky()) {
                if (!com.nishiyu.enderframelib.core.kernel.Shortcut.STICKY_IN_SCROLL_NODE.on() || underSticky || child.stickyScrollParams(container, rootW, rootH) == null) return true;
                if (scanStickies == null) scanStickies = new ArrayList<>(2);
                scanStickies.add(child);
                if (hasUnscrollableDescendant(container, child, true)) return true;
                continue;
            }
            if (child.isAbsolute()) {
                scanSawAbsolute = true;
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
            if (hasUnscrollableDescendant(container, child, underSticky)) return true;
        }
        return false;
    }

    /** ビューポートの大きさが変わった。 */
    void setViewport(int rootW, int rootH) {
        this.rootW = rootW;
        this.rootH = rootH;
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
        return box.style.containsKey(CssPropertyIndex.BACKGROUND_COLOR) || box.style.containsKey(CssPropertyIndex.BACKGROUND);
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
    /** 1 回の描画の前処理（部分木の記録・スクロールの節・クリップの表を空にする）。{@link #paintTasks} と断片の経路が共有する。 */
    void beginPass() {
        fragIds.set(0);
        scrollContentOf.clear();
        scrollContents.clear();
        if (cachedScrollInfos != null) prevScrollInfos = cachedScrollInfos;
        cachedScrollInfos = null;
        contentClips.clear();
        blendDescendants.clear();
    }

    /** {@link #isTopLayer} の外向けの口（部分木の組み直しの対象から外すため）。 */
    boolean isTopLayerBox(LayoutBox box) {
        return isTopLayer(box);
    }

    /**
     * Top Layer の箱（{@code dialog:modal} と表示中の {@code popover}。UA シートが {@link #TOP_LAYER_Z} を与える固定配置）。
     * 祖先のスタッキングコンテキストの外へ出て、文書の根のコンテキストの最前面に描かれる（HTML §4.11 / CSS Positioned Layout 3 §5）。
     */
    static final int TOP_LAYER_Z = Integer.MAX_VALUE;

    void drawBackdrop(List<DisplayItem> elements, LayoutBox box) {
        int color = ColorParser.parseColor(box.backdropStyle.getOrDefault(CssPropertyIndex.BACKGROUND_COLOR, "transparent"));
        float opacity = box.backdropStyle.number(CssPropertyIndex.OPACITY, 1.0f);
        if ((color >>> 24) == 0 || opacity <= 0f) return;
        int nodeHandle = box.node != null ? box.node.paintHandle() : 0;
        ItemProps props = new ItemProps();
        props.parentId(box.node.key(), nodeHandle > 0 ? DisplayItem.nodeKeyOf(nodeHandle) : 0L);
        PaintSupport.CaptureList cap = (parentRelative && boxHasOwnNode(box) && elements instanceof PaintSupport.CaptureList c) ? c : null;
        int prevOx = cap != null ? cap.originX : 0, prevOy = cap != null ? cap.originY : 0;
        if (cap != null) cap.setOrigin(Math.round(box.x()), Math.round(box.y()));
        try {
            // 固定配置の箱と同じく、ビューポートに張り付く（スクロールしない）。クリップはかけない
            addElement(elements, box.node.key(), box.node.key() + "_backdrop", ElementType.RECT, 0, 0, rootW, rootH, "", 0, color,
                    props, 0, opacity, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, null, 0L);
        } finally {
            if (cap != null) cap.setOrigin(prevOx, prevOy);
        }
    }

    private boolean isTopLayer(LayoutBox box) {
        return box.isFixed() && box.zIndex() == TOP_LAYER_Z && !isZIndexAuto(box);
    }

    /**
     * 子を描画の層に振り分ける。
     *
     * @param sc    いちばん近い本物のスタッキングコンテキストの層（負・0・正の z-index と位置指定要素が入る）
     * @param local 今いる層（float・位置指定要素・不可分なインラインは自分の中身だけを持つ疑似コンテキストを作る）
     */
    /** 子へ渡す文脈（中身に掛けるクリップ・子の項目の親の id）。 */
    static final class ChildContext { ClipRect clip; String parentId; }

    /**
     * 箱 {@code box} の中身の文脈を決める。クリップの表（{@code contentClips}）とスクロールの節の登録も行う（子を分類・描画する前に呼ぶ）。
     * 値の入力部品（子を持たない）には呼ばない。{@link #collectChildren} と断片の経路が共有する。
     */
    ChildContext enterChildren(LayoutBox box, ClipRect clip, String parentId, ChildContext into) {
        String tag = box.node.tag;
        ClipRect computedClip = computeChildClipRect(box, clip);
        // 切らない箱は親のクリップをそのまま返す（すでにそろえてある）。新しく作った分だけそろえる
        ClipRect childClip = computedClip == clip ? clip : canonical(computedClip);
        contentClips.put(box, childClip);
        // 匿名ボックスは描くものを持たない。子の持ち主は匿名ボックスの外の要素のまま
        String childParent = "root".equals(tag) ? null : box.isAnonymous ? parentId : box.node.key();
        if (com.nishiyu.enderframelib.core.kernel.Shortcut.SCROLL_CONTENT_NODES.on() && scrollsAsOneNode(box)) {
            // 中身は専用の節の下に置く: スクロールではこの節だけを動かせばよい（箱自身の背景・枠・つまみは動かさない）
            ScrollContent content = new ScrollContent(box, box.node.scrollContentKey(),
                    scrollState.getScrollX(box.node), scrollState.getScrollY(box.node));
            content.stickies = lastStickyShifts;
            scrollContentOf.put(box, content);
            scrollContents.add(content);
            childParent = content.contentId;
        }
        into.clip = childClip;
        into.parentId = childParent;
        return into;
    }

    /** 断片の記憶が、部分木の中で登録されたスクロールの節を記録・再登録するための口。 */
    int scrollContentCount() { return scrollContents.size(); }

    List<ScrollContent> scrollContentsSince(int from) { return new ArrayList<>(scrollContents.subList(from, scrollContents.size())); }

    void reregister(ScrollContent c) {
        scrollContents.add(c);
        scrollContentOf.put(c.box, c);
    }

    /** 箱のスクロールバーを描く（中身の層の後ろに出す）か。 */
    static boolean wantsScrollbars(LayoutBox box) {
        return !"root".equals(box.node.tag) && box.w() > 0 && box.h() > 0 && factsOf(box.style).scrollbars() && !isInvisible(box);
    }

    /**
     * 子に掛かるクリップ。overflow が切るのは自分が包含ブロックの祖先になっている子孫だけ（CSS 2.2 §11.1.1）なので、
     * 絶対配置の箱は木の親ではなく包含ブロックが中身に掛けるクリップを使う。固定配置（包含ブロックがビューポート）は切られない。
     *
     * 何が壊れていたか: 以前は親のクリップを木のすべての子へそのまま渡していたので、位置指定の無い
     * {@code overflow: hidden} の箱の中のドロップダウンやモーダル（absolute / fixed）が箱の枠で切り落とされていた。
     */
    ClipRect clipForChild(LayoutBox child, ClipRect flowClip) {
        if (!child.isAbsolute()) return flowClip;
        LayoutBox cb = child.findContainingBlock();
        if (cb == null) return null;
        return contentClips.containsKey(cb) ? contentClips.get(cb) : flowClip;
    }

    /** 箱自身の項目を持つか（大きさがあり、匿名でない）。 */
    static boolean hasSelf(LayoutBox box) {
        return box.w() > 0 && box.h() > 0 && !box.isAnonymous;
    }

    /** 箱自身の項目を {@code elements} へ描く（箱ごとの記憶つきの描画）。 */
    void drawSelf(List<DisplayItem> elements, LayoutBox box, ClipRect clip, String parentId) {
        painter.draw(elements, box, clip, parentId);
    }

    static boolean isPositioned(LayoutBox box) {
        return box.isRelative() || box.isAbsolute() || box.isSticky();
    }

    static boolean isZIndexAuto(LayoutBox box) {
        return factsOf(box.style).zAuto();
    }

    /**
     * 算出スタイルだけから決まる、描画順の分類に使う値。箱ごとに十数回の文字列引きをしていた所を、スタイルごとに一度で済ませる
     * （算出スタイルは不変で、箱の木が変わっても同じインスタンスなら使い回せる）。
     */
    record StyleFacts(boolean displayNone, boolean zAuto, float opacity, boolean stackingByStyle, boolean hasMask,
                      String overflowX, String overflowY, boolean scrollbars, boolean hasAnimation,
                      boolean hasMovingProps) {
    }

    /** 診断: {@link StyleFacts} を新しく算出した回数（キャッシュに当たらなかった回数）。 */
    static final java.util.concurrent.atomic.LongAdder STAT_FACTS_COMPUTED = new java.util.concurrent.atomic.LongAdder();

    public static boolean hasMovingProps(com.nishiyu.enderframelib.core.css.ComputedStyle style) {
        if (style == null) return false;
        if (style.derivedFacts() instanceof StyleFacts cached) return cached.hasMovingProps();
        return style.get(CssPropertyIndex.ANIMATION) != null
                || style.get(CssPropertyIndex.ANIMATION_NAME) != null
                || hasTransformOrFilterProps(style);
    }

    private static boolean hasTransformOrFilterProps(com.nishiyu.enderframelib.core.css.ComputedStyle style) {
        return !isNone(style.get(CssPropertyIndex.TRANSFORM))
                || !isNone(style.get(CssPropertyIndex.TRANSLATE))
                || !isNone(style.get(CssPropertyIndex.ROTATE))
                || !isNone(style.get(CssPropertyIndex.SCALE))
                || !isNone(style.get(CssPropertyIndex.FILTER))
                || !isNone(style.get(CssPropertyIndex.PERSPECTIVE));
    }

    static StyleFacts factsOf(com.nishiyu.enderframelib.core.css.ComputedStyle style) {
        if (style.derivedFacts() instanceof StyleFacts cached) return cached;
        STAT_FACTS_COMPUTED.increment();
        String z = style.get(CssPropertyIndex.Z_INDEX);
        boolean zAuto = z == null || z.isBlank() || "auto".equalsIgnoreCase(z.trim());
        boolean mask = styleHasMask(style);
        boolean stacking = !isNone(style.get(CssPropertyIndex.TRANSFORM)) || !isNone(style.get(CssPropertyIndex.FILTER))
                || !isNone(style.get(CssPropertyIndex.CLIP_PATH)) || !isNone(style.get(CssPropertyIndex.BACKDROP_FILTER)) || mask
                || style.keywordOf(CssPropertyIndex.ISOLATION, CssKeywords.Isolation.class) == CssKeywords.Isolation.ISOLATE
                || !"normal".equals(style.keyword(CssPropertyIndex.MIX_BLEND_MODE, "normal"));
        String[] overflow = overflowPair(style);
        boolean hasAnimation = style.get(CssPropertyIndex.ANIMATION) != null || style.get(CssPropertyIndex.ANIMATION_NAME) != null;
        boolean hasMovingProps = hasAnimation || hasTransformOrFilterProps(style);
        StyleFacts facts = new StyleFacts("none".equals(style.getOrDefault(CssPropertyIndex.DISPLAY, "block")), zAuto,
                style.number(CssPropertyIndex.OPACITY, 1f), stacking, mask, overflow[0], overflow[1],
                isScrollbarOverflow(overflow[0]) || isScrollbarOverflow(overflow[1]),
                hasAnimation, hasMovingProps);
        style.derivedFacts(facts);
        return facts;
    }

    /**
     * スタッキングコンテキストを作るか（CSS 2.2 §9.9.1 と CSS Positioned Layout 3 / Color 4 /
     * Compositing 1 / Transforms 1 の追加分）。
     */
    boolean createsStackingContext(LayoutBox box) {
        return createsStackingContext(box, box != null ? box.style : null);
    }

    boolean createsStackingContext(LayoutBox box, com.nishiyu.enderframelib.core.css.ComputedStyle style) {
        if (box == null || style == null) return false;
        StyleFacts f = factsOf(style);
        boolean zAuto = f.zAuto();
        if (isPositioned(box) && !zAuto) return true;
        if (box.isFixed() || box.isSticky()) return true;
        if (!zAuto && isFlexOrGridItem(box)) return true;
        if (f.opacity() < 1f) return true;
        // opacity を動かすアニメーションは、静的な値が 1 でも動いている間スタッキングコンテキストを作る（CSS Positioned Layout 3 §2）
        if (animatesOpacity != null && animatesOpacity.test(box)) return true;
        // transform・filter・clip-path・mask・backdrop-filter・isolation・mix-blend-mode（CSS Masking 1 / Filter Effects 2 / Compositing 1）
        return f.stackingByStyle();
    }

    /**
     * スタッキングコンテキストの箱をレイヤーに描いて合成する指定。グループの不透明度・{@code filter}・{@code mix-blend-mode}、
     * 子孫の混合を閉じ込める必要があるとき（{@code isolation: isolate} か、{@code mix-blend-mode} の子孫を含む）に作る。
     * どれも無ければ null（直に描く）。{@code span} は {@link DisplayListBuilder} が項目を出し終えてから埋める。
     */
    LayerSpec layerSpecFor(LayoutBox box) {
        float opacity = box.style.number(CssPropertyIndex.OPACITY, 1f);
        boolean opacityMoves = animatesOpacity.test(box);
        List<FilterOp> filters = FilterOp.parse(box.style.get(CssPropertyIndex.FILTER), new FilterOp.Context(rootW, rootH,
                box.style.fontSizePx(), box.style.rootFontSizePx(), box.style.color(CssPropertyIndex.COLOR, 0xFF000000)));
        BlendMode blend = BlendMode.fromCss(box.style.keyword(CssPropertyIndex.MIX_BLEND_MODE, "normal"));
        boolean isolate = hasBlendDescendant(box);

        float fontPx = box.style.fontSizePx(), rootFontPx = box.style.rootFontSizePx();
        boolean rel = parentRelative && boxHasOwnNode(box);
        float lx = rel ? box.x() - Math.round(box.x()) : box.x();
        float ly = rel ? box.y() - Math.round(box.y()) : box.y();
        float[] border = {lx, ly, box.w(), box.h()};
        float[] padding = {lx + box.bL(), ly + box.bT(), Math.max(0, box.w() - box.bL() - box.bR()), Math.max(0, box.h() - box.bT() - box.bB())};
        float[] content = {padding[0] + box.pL(), padding[1] + box.pT(), Math.max(0, padding[2] - box.pL() - box.pR()), Math.max(0, padding[3] - box.pT() - box.pB())};
        ClipShape clip = ClipShape.parse(box.style.get(CssPropertyIndex.CLIP_PATH),
                ClipShape.Context.of(border, padding, content, rootW, rootH, fontPx, rootFontPx));

        boolean masked = hasMask(box);
        boolean luminance = masked && !BoxDecorations.maskModes(box.style).isEmpty()
                && "luminance".equals(BoxDecorations.maskModes(box.style).getFirst());

        List<FilterOp> backdrop = FilterOp.parse(box.style.get(CssPropertyIndex.BACKDROP_FILTER), new FilterOp.Context(rootW, rootH, fontPx, rootFontPx,
                box.style.color(CssPropertyIndex.COLOR, 0xFF000000)));
        ClipShape backdropShape = null;
        if (!backdrop.isEmpty()) {
            float[] r = LengthResolver.cornerRadiiXYShared(box.axisStyle(), box.w(), box.h(), rootW, rootH);
            float[] d = new float[12];
            d[0] = lx;
            d[1] = ly;
            d[2] = lx + box.w();
            d[3] = ly + box.h();
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
        for (BoxDecorations.BackgroundLayer layer : BoxDecorations.maskLayersView(style, 1.0)) {
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
            if (child.display().isNone()) continue;
            if (!"normal".equals(child.style.keyword(CssPropertyIndex.MIX_BLEND_MODE, "normal"))) {
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
        return value == null || "none".equals(value) || value.isBlank() || "none".equalsIgnoreCase(value.trim());
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
    private static final class ChildClipCache {
        ClipRect parent;
        float minX, minY, maxX, maxY;
        float rx0, rx1, rx2, rx3, ry0, ry1, ry2, ry3;
        boolean elliptical;
        int ox, oy;
        String ownerKey;
        ClipRect result;
    }

    private final java.util.Map<LayoutBox, ChildClipCache> childClipCache = new java.util.IdentityHashMap<>();

    ClipRect computeChildClipRect(LayoutBox box, ClipRect parentClip) {
        String tag = box.node.tag;
        boolean isRootElement = "root".equals(tag) || "html".equals(tag) || "body".equals(tag);
        if (isRootElement) return parentClip;

        boolean panZoom = "true".equalsIgnoreCase(box.node.attrs.get("data-pan-zoom"));
        StyleFacts facts = factsOf(box.axisStyle());
        boolean clipX = panZoom || !"visible".equals(facts.overflowX());
        boolean clipY = panZoom || !"visible".equals(facts.overflowY());
        if (!clipX && !clipY) return parentClip;

        float minX = clipX ? box.x() + box.bL() : -UNCLIPPED;
        float minY = clipY ? box.y() + box.bT() : -UNCLIPPED;
        float maxX = clipX ? box.x() + box.w() - box.bR() : UNCLIPPED;
        float maxY = clipY ? box.y() + box.h() - box.bB() : UNCLIPPED;

        // 内側の角は外側の半径から隣り合う辺の太さを引いたもの（角丸は両軸を切るときだけ）
        float rx0 = 0f, rx1 = 0f, rx2 = 0f, rx3 = 0f;
        float ry0 = 0f, ry1 = 0f, ry2 = 0f, ry3 = 0f;
        boolean elliptical = false;
        if (clipX && clipY) {
            float[] r = LengthResolver.cornerRadiiXYShared(box.axisStyle(), box.w(), box.h(), rootW, rootH);
            for (int i = 0; i < 4; i++) elliptical |= Math.round(r[i]) != Math.round(r[4 + i]);
            if (!elliptical) {
                rx0 = Math.min(r[0], r[4]) - Math.max(box.bT(), box.bL());
                rx1 = Math.min(r[1], r[5]) - Math.max(box.bT(), box.bR());
                rx2 = Math.min(r[2], r[6]) - Math.max(box.bB(), box.bR());
                rx3 = Math.min(r[3], r[7]) - Math.max(box.bB(), box.bL());
            } else {
                // 楕円の角は、内側の角を軸ごとに縮める（横は左右の枠線、縦は上下の枠線の太さ）
                rx0 = Math.max(0f, r[0] - box.bL()); ry0 = Math.max(0f, r[4] - box.bT());
                rx1 = Math.max(0f, r[1] - box.bR()); ry1 = Math.max(0f, r[5] - box.bT());
                rx2 = Math.max(0f, r[2] - box.bR()); ry2 = Math.max(0f, r[6] - box.bB());
                rx3 = Math.max(0f, r[3] - box.bL()); ry3 = Math.max(0f, r[7] - box.bB());
            }
        }
        int ox = parentRelative && boxHasOwnNode(box) && clipX ? Math.round(box.x()) : 0;
        int oy = parentRelative && boxHasOwnNode(box) && clipY ? Math.round(box.y()) : 0;
        String ownerKey = box.node.key();
        ChildClipCache cc = childClipCache.get(box);
        if (cc != null && cc.result != null
                && java.util.Objects.equals(cc.parent, parentClip)
                && Float.compare(cc.minX, minX) == 0 && Float.compare(cc.minY, minY) == 0
                && Float.compare(cc.maxX, maxX) == 0 && Float.compare(cc.maxY, maxY) == 0
                && Float.compare(cc.rx0, rx0) == 0 && Float.compare(cc.rx1, rx1) == 0
                && Float.compare(cc.rx2, rx2) == 0 && Float.compare(cc.rx3, rx3) == 0
                && cc.elliptical == elliptical
                && (!elliptical || (Float.compare(cc.ry0, ry0) == 0 && Float.compare(cc.ry1, ry1) == 0
                        && Float.compare(cc.ry2, ry2) == 0 && Float.compare(cc.ry3, ry3) == 0))
                && cc.ox == ox && cc.oy == oy
                && java.util.Objects.equals(cc.ownerKey, ownerKey)) {
            return cc.result;
        }
        float[] chainOwn = elliptical
                ? new float[]{minX - ox, minY - oy, maxX - ox, maxY - oy, rx0, rx1, rx2, rx3, ry0, ry1, ry2, ry3}
                : new float[]{minX - ox, minY - oy, maxX - ox, maxY - oy, rx0, rx1, rx2, rx3};
        // 合成した矩形に、合成する前のこの箱の矩形を持ち主（この箱）つきで足す。箱が動く（変形する）と、切り口も一緒に動く
        ClipRect out = BoxDecorations.intersectClipRect(
                parentClip, minX, minY, maxX, maxY, rx0, rx1, rx2, rx3, ry0, ry1, ry2, ry3,
                elliptical, null, ClipRect.appendChain(parentClip, chainOwn, ownerKey, ox, oy));
        if (cc == null) {
            cc = new ChildClipCache();
            childClipCache.put(box, cc);
        }
        cc.parent = parentClip;
        cc.minX = minX; cc.minY = minY; cc.maxX = maxX; cc.maxY = maxY;
        cc.rx0 = rx0; cc.rx1 = rx1; cc.rx2 = rx2; cc.rx3 = rx3;
        cc.ry0 = ry0; cc.ry1 = ry1; cc.ry2 = ry2; cc.ry3 = ry3;
        cc.elliptical = elliptical;
        cc.ox = ox; cc.oy = oy;
        cc.ownerKey = ownerKey;
        cc.result = out;
        return out;
    }

    /**
     * {@code overflow-x} / {@code overflow-y} の使用値。片方が visible / clip でもう片方がそうでなければ、
     * visible は auto、clip は hidden として扱う（CSS Overflow 3 §3.1）。
     */
    static String[] overflowPair(LayoutBox box) {
        StyleFacts f = factsOf(box.axisStyle());
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

    private static final RenderMeta MOVING_NODE_META = new RenderMeta(null, null, null, 12.0f, 0.5f, 0.5f, 1, false);
    private final java.util.Map<LayoutBox, MovingBoxCache> movingNodeCache = new java.util.IdentityHashMap<>();

    private static final class MovingBoxCache {
        RenderMeta contentMeta;
        DisplayItem contentItem;
        RenderMeta thumbVMeta;
        DisplayItem thumbVNodeItem;
        RenderMeta thumbHMeta;
        DisplayItem thumbHNodeItem;
        RenderMeta sbMeta;
        RenderMeta thumbTrackMetaV;
        RenderMeta thumbTrackMetaH;
    }

    private MovingBoxCache movingCacheOf(LayoutBox box) {
        if (movingNodeCache.size() > 1024) movingNodeCache.clear();
        MovingBoxCache c = movingNodeCache.get(box);
        if (c == null) {
            c = new MovingBoxCache();
            movingNodeCache.put(box, c);
        }
        return c;
    }

    /**
     * 子の項目をまとめて動かすための、見えない節（箱の下に置く。クリックも受けない）。移動量はあとから
     * {@code UiView#setPaintOffset} で足す。id が持ち主の節の id と違うので、持ち主の節の検索には現れない。
     */
    void addMovingNode(List<DisplayItem> elements, String id, LayoutBox box, RenderMeta meta) {
        ItemProps events = scratchMovingProps.reset();
        int nodeHandle = box.node != null ? box.node.paintHandle() : 0;
        long nodeKey = nodeHandle > 0 ? DisplayItem.nodeKeyOf(nodeHandle) : 0L;
        String parentKeyStr = box.node != null ? box.node.key() : id;
        events.parentId(parentKeyStr, nodeKey);
        events.pointerEventsNone();
        boolean isV = id.endsWith("_scrollv");
        boolean isH = !isV && id.endsWith("_scrollh");
        long movingKey = nodeHandle > 0 ? (isV ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLLBAR_NODE_V)
                : isH ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLLBAR_NODE_H)
                : id.endsWith(PaintSceneDriver.SCROLL_CONTENT_SUFFIX) ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLL_CONTENT)
                : DisplayItem.nodeKeyOf(nodeHandle)) : 0L;
        RenderMeta baseMeta = meta == null ? RenderMeta.EMPTY : MOVING_NODE_META;
        MovingBoxCache mc = movingCacheOf(box);
        RenderMeta prevMeta = isV ? mc.thumbVMeta : (isH ? mc.thumbHMeta : mc.contentMeta);
        DisplayItem prevItem = isV ? mc.thumbVNodeItem : (isH ? mc.thumbHNodeItem : mc.contentItem);
        if (prevMeta != null && prevMeta.hints().parentKey() == nodeKey
                && java.util.Objects.equals(prevMeta.hints().parentId(), parentKeyStr)
                && Float.compare(prevMeta.fontHeight(), baseMeta.fontHeight()) == 0) {
            events.hintsReuse(prevMeta.hints());
            baseMeta = prevMeta;
        } else {
            baseMeta = baseMeta.withHints(events.hints());
            if (isV) mc.thumbVMeta = baseMeta;
            else if (isH) mc.thumbHMeta = baseMeta;
            else mc.contentMeta = baseMeta;
        }
        int ox = 0, oy = 0;
        if (elements instanceof PaintSupport.CaptureList cap) {
            ox = cap.originX;
            oy = cap.originY;
        }
        float x = box.x() - ox, y = box.y() - oy;
        int left = Math.round(x), top = Math.round(y);
        int width = Math.round(x + box.w()) - left, height = Math.round(y + box.h()) - top;
        if (prevItem != null && prevItem.x() == left && prevItem.y() == top
                && prevItem.width() == width && prevItem.height() == height
                && prevItem.key() == movingKey && prevItem.meta() == baseMeta && id.equals(prevItem.id())) {
            elements.add(prevItem);
            return;
        }
        int idxBefore = elements.size();
        addElement(elements, id, id, ElementType.NODE, box.x(), box.y(), box.w(), box.h(), "", 0, 0, events, 0, 1f, 0, 0, 1f, 1f, 0, 0,
                AnimationSpec.NONE, baseMeta, movingKey);
        DisplayItem created = elements.get(idxBefore);
        if (isV) mc.thumbVNodeItem = created;
        else if (isH) mc.thumbHNodeItem = created;
        else mc.contentItem = created;
    }

    private static final Corners SCROLLBAR_CORNERS = new Corners(2, 2, 2, 2);

    void drawScrollbarsIfNeeded(List<DisplayItem> elements, LayoutBox box, ClipRect clipRect) {
        if (box.style.keywordOf(CssPropertyIndex.SCROLLBAR_WIDTH, CssKeywords.ScrollbarWidth.class) == CssKeywords.ScrollbarWidth.NONE) return;

        PaintSupport.CaptureList cap = (parentRelative && boxHasOwnNode(box) && elements instanceof PaintSupport.CaptureList c) ? c : null;
        int prevOx = cap != null ? cap.originX : 0, prevOy = cap != null ? cap.originY : 0;
        if (cap != null) cap.setOrigin(Math.round(box.x()), Math.round(box.y()));
        try {
            float scrollW = box.scrollWidth();
            float scrollH = box.scrollHeight();
            float curX = scrollState.getScrollX(box.node);
            float curY = scrollState.getScrollY(box.node);

            int nodeHandle = box.node != null ? box.node.paintHandle() : 0;
            long nodeKey = nodeHandle > 0 ? DisplayItem.nodeKeyOf(nodeHandle) : 0L;
            String ownerId = box.node.key();
            ItemProps sbEvents = scratchSbProps.reset();
            sbEvents.parentId(ownerId, nodeKey);
            ScrollContent content = scrollContentOf.get(box);
            ClipRect sbClip = clipRect;
            MovingBoxCache mc = movingCacheOf(box);
            RenderMeta prevSb = mc.sbMeta;
            RenderHints sbHints = sbEvents.hintsReuse(prevSb != null ? prevSb.hints() : null);
            RenderMeta sbMeta;
            if (prevSb != null && java.util.Objects.equals(prevSb.clipRect(), sbClip) && prevSb.hints() == sbHints) {
                sbMeta = prevSb;
            } else {
                sbMeta = new RenderMeta(sbClip, SCROLLBAR_CORNERS, null, 12.0f, 0f, 0f, 0.5f, 0.5f, 1, false, null, sbHints);
                mc.sbMeta = sbMeta;
            }

            int barThickness = 4;
            if (scrollH > box.h() + 2 && box.h() > 12) {
                float trackX = box.x() + box.w() - box.bR() - barThickness - 2;
                float trackY = box.y() + box.bT() + 2;
                float trackH = box.h() - box.bT() - box.bB() - 4;
                float thumbH = Math.round(Math.max(12, trackH * (box.h() / scrollH))); // 整数: つまみの位置で丸めの高さが揺れない（平行移動と作り直しで同じ大きさ）
                float maxScrollY = Math.max(1f, scrollH - box.h());
                float thumbY = trackY + (trackH - thumbH) * Math.clamp(curY / maxScrollY, 0f, 1f);

                long trackKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLLBAR_TRACK_V) : 0L;
                long thumbKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLLBAR_V) : 0L;
                long nodeVKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLLBAR_NODE_V) : 0L;

                addElement(elements, ownerId, box.node.partKey(12, "_sb_vt"), ElementType.RECT, trackX, trackY, barThickness, trackH, "", 0, 0x33000000, sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, sbMeta, trackKey);
                ItemProps thumbEvents = sbEvents;
                RenderMeta thumbMeta = sbMeta;
                if (content != null) { // つまみは、スクロール量に比例して動く節の下に置く（スクロールのたびに作り直さない）
                    content.thumbVId = box.node.partKey(13, "_scrollv");
                    content.ky = (trackH - thumbH) / maxScrollY;
                    content.fy0 = thumbY - Math.round(thumbY);
                    addMovingNode(elements, content.thumbVId, box, sbMeta);
                    thumbEvents = scratchThumbProps.reset();
                    thumbEvents.parentId(content.thumbVId, nodeVKey);
                    RenderMeta prevTV = mc.thumbTrackMetaV;
                    RenderHints tvHints = thumbEvents.hintsReuse(prevTV != null ? prevTV.hints() : null);
                    thumbEvents.reuseCandidate(tvHints);
                    if (prevTV != null && java.util.Objects.equals(prevTV.clipRect(), sbClip) && prevTV.hints() == tvHints) {
                        thumbMeta = prevTV;
                    } else {
                        thumbMeta = new RenderMeta(sbClip, SCROLLBAR_CORNERS, null, 12.0f, 0f, 0f, 0.5f, 0.5f, 1, false, null, tvHints);
                        mc.thumbTrackMetaV = thumbMeta;
                    }
                }
                addElement(elements, ownerId, box.node.partKey(14, "_sb_v"), ElementType.RECT, trackX, thumbY, barThickness, thumbH, "", 0, 0x88CCCCCC, thumbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, thumbMeta, thumbKey);
            }
            if (scrollW > box.w() + 2 && box.w() > 12) {
                float trackX = box.x() + box.bL() + 2;
                float trackY = box.y() + box.h() - box.bB() - barThickness - 2;
                float trackW = box.w() - box.bL() - box.bR() - 4;
                float thumbW = Math.round(Math.max(12, trackW * (box.w() / scrollW)));
                float maxScrollX = Math.max(1f, scrollW - box.w());
                float thumbX = trackX + (trackW - thumbW) * Math.clamp(curX / maxScrollX, 0f, 1f);

                long trackKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLLBAR_TRACK_H) : 0L;
                long thumbKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLLBAR_H) : 0L;
                long nodeHKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLLBAR_NODE_H) : 0L;

                addElement(elements, ownerId, box.node.partKey(15, "_sb_ht"), ElementType.RECT, trackX, trackY, trackW, barThickness, "", 0, 0x33000000, sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, sbMeta, trackKey);
                ItemProps thumbEvents = sbEvents;
                RenderMeta thumbMeta = sbMeta;
                if (content != null) {
                    content.thumbHId = box.node.partKey(16, "_scrollh");
                    content.kx = (trackW - thumbW) / maxScrollX;
                    content.fx0 = thumbX - Math.round(thumbX);
                    addMovingNode(elements, content.thumbHId, box, sbMeta);
                    thumbEvents = scratchThumbProps.reset();
                    thumbEvents.parentId(content.thumbHId, nodeHKey);
                    RenderMeta prevTH = mc.thumbTrackMetaH;
                    RenderHints thHints = thumbEvents.hintsReuse(prevTH != null ? prevTH.hints() : null);
                    thumbEvents.reuseCandidate(thHints);
                    if (prevTH != null && java.util.Objects.equals(prevTH.clipRect(), sbClip) && prevTH.hints() == thHints) {
                        thumbMeta = prevTH;
                    } else {
                        thumbMeta = new RenderMeta(sbClip, SCROLLBAR_CORNERS, null, 12.0f, 0f, 0f, 0.5f, 0.5f, 1, false, null, thHints);
                        mc.thumbTrackMetaH = thumbMeta;
                    }
                }
                addElement(elements, ownerId, box.node.partKey(17, "_sb_h"), ElementType.RECT, thumbX, trackY, thumbW, barThickness, "", 0, 0x88CCCCCC, thumbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, thumbMeta, thumbKey);
            }
        } finally {
            if (cap != null) cap.setOrigin(prevOx, prevOy);
        }
    }

}
