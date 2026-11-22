package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.*;

import com.nishiyu.enderframelib.core.paint.CssAnimator;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * レイアウト更新ごとに1回生成されるヒットテスト用インデックス（STRUCTURE_PLAN 目標配置）。
 * 対話可能要素の事前抽出、Z順序の保持、親チェーンの事前解決、clipRect判定を行い、
 * 毎フレームの全要素スキャン・HashMapアロケーションを排除する。
 */
public class HitTestIndex {

    public static class Entry {
        public final DisplayItem element;
        public final ClipRect clipRect;
        public final List<DisplayItem> transformChain; // ルートから自身までの順序
        /** 画像の透過ヒットテストの閾値（0〜255）。-1 なら矩形だけで判定する（{@code data-hit-alpha} のある {@code <img>}）。 */
        public final int alphaThreshold;
        /** 項目自身が静止している（移動・回転・拡縮・傾き・遷移・アニメが無い）。逆変換が恒等なので、アニメ状態を引かずに済む。 */
        final boolean selfStatic;
        /** 親相対座標のとき、祖先の静的な NODE 原点の合計（格子索引のページ座標計算用）。 */
        final float staticOriginX, staticOriginY;
        /** クリップを連鎖の部品ごとに当てるか（祖先が動きうると合成済みの矩形は作ったときの位置のまま。入れ子のスクロールの中身が親に当たった。T2）。 */
        boolean clipByParts;

        public Entry(DisplayItem element, ClipRect clipRect, List<DisplayItem> transformChain) {
            this(element, clipRect, transformChain, isStatic(element, PaintOffsets.EMPTY));
        }

        Entry(DisplayItem element, ClipRect clipRect, List<DisplayItem> transformChain, boolean selfStatic) {
            this.element = element;
            this.clipRect = clipRect;
            this.transformChain = transformChain;
            this.alphaThreshold = alphaThresholdOf(element);
            this.selfStatic = selfStatic;
            float ox = 0f, oy = 0f;
            for (int i = 0, cn = transformChain.size() - 1; i < cn; i++) {
                DisplayItem anc = transformChain.get(i);
                if (anc.type() == ElementType.NODE && anc.hints().relativeOrigin()) {
                    ox += anc.x();
                    oy += anc.y();
                }
            }
            this.staticOriginX = ox;
            this.staticOriginY = oy;
        }
    }

    private static int alphaThresholdOf(DisplayItem el) {
        if (el.type() != ElementType.IMAGE || el.events() == null) return -1;
        String v = el.events().get("hit-alpha");
        if (v == null) return -1;
        try {
            return Math.max(0, Math.min(255, Integer.parseInt(v.trim())));
        } catch (NumberFormatException e) {
            return 128;
        }
    }

    /** 透過ヒットテストが画像のマスクを引く先。null なら（画像が引けないときも）矩形で判定する。 */
    private volatile AlphaMaskProvider alphaProvider;

    public void alphaMaskProvider(AlphaMaskProvider provider) {
        this.alphaProvider = provider;
    }

    /** 動いている項目の姿勢を評価する時刻（ms）。描いたフレームの時刻を渡す（既定の壁時計は、時刻を壁時計で進めるホストでだけ一致する。T2）。 */
    private volatile java.util.function.LongSupplier animationClock = System::currentTimeMillis;

    public void animationClock(java.util.function.LongSupplier clock) {
        this.animationClock = clock != null ? clock : System::currentTimeMillis;
    }

    private final List<Entry> interactiveEntries; // z順序の逆順（上位優先）
    /** {@link #interactiveEntries} の配列版（添字 = 上から数えた順位）。 */
    private Entry[] entryArr = new Entry[0];
    // ---- 空間ハッシュ（一様格子）。動かない項目だけを載せ、動きうる項目・巨大な項目は「いつも候補」に回す
    private float gridX0, gridY0, cellSize = 1f;
    private int cols, rows;
    private int[] cellStart = new int[]{0};
    private int[] cellItems = new int[0];
    /** いつも候補にする項目の順位（昇順）。動きうる項目と、格子の 1/4 以上を覆う項目。 */
    private int[] always = new int[0];
    private final List<Entry> scrollEntries = new ArrayList<>(); // スクロールする箱。z順序の逆順
    private final Long2ObjectOpenHashMap<DisplayItem> elementMap;
    private final PaintOffsets offsets;
    /** 描画の上だけで動きうる節（スクロールの中身・つまみ・ドラッグ中のウィンドウ）の鍵。静止格子へ載せない。 */
    private final LongOpenHashSet dynamicKeys = new LongOpenHashSet();
    private final java.util.Set<String> dynamicIds;

    public HitTestIndex(List<DisplayItem> elements) {
        this(elements, java.util.Set.of(), PaintOffsets.EMPTY);
    }

    /** {@code dynamicIds} は、描画の上だけで移動量が変わりうる項目の id。 */
    public HitTestIndex(List<DisplayItem> elements, java.util.Set<String> dynamicIds) {
        this(elements, dynamicIds, PaintOffsets.EMPTY);
    }

    /** 描画上の平行移動表 {@code offsets} を参照するヒットテスト索引を作る。 */
    public HitTestIndex(List<DisplayItem> elements, java.util.Set<String> dynamicIds, PaintOffsets offsets) {
        this.offsets = offsets != null ? offsets : PaintOffsets.EMPTY;
        this.dynamicIds = java.util.Set.copyOf(dynamicIds);
        for (String id : this.dynamicIds) dynamicKeys.add(DisplayItem.keyOf(id));
        if (!this.offsets.isEmpty()) {
            synchronized (this.offsets) {
                for (int i = 0, n = this.offsets.size(); i < n; i++) dynamicKeys.add(this.offsets.keyAt(i));
            }
        }
        if (elements == null || elements.isEmpty()) {
            this.interactiveEntries = Collections.emptyList();
            this.elementMap = new Long2ObjectOpenHashMap<>(0);
            buildGrid();
            return;
        }

        this.elementMap = new Long2ObjectOpenHashMap<>(elements.size() * 2);
        for (DisplayItem el : elements) {
            long k = el.key();
            elementMap.put(k, el);
            if (el.id() != null && !el.id().isEmpty()) {
                long strKey = DisplayItem.keyOf(el.id());
                if (strKey != k) elementMap.putIfAbsent(strKey, el);
                if (this.dynamicIds.contains(el.id()) || this.offsets.hasOffset(el)) dynamicKeys.add(k);
            }
        }

        List<Entry> entries = new ArrayList<>();
        // elements は描画順（下層から上層）なので、逆順で探索できるように保持
        for (int i = elements.size() - 1; i >= 0; i--) {
            DisplayItem el = elements.get(i);
            boolean stat = isStatic(el, this.offsets) && !dynamicKeys.contains(el.key());
            if (el.hints().scrollable() && !isPointerEventsNone(el)) {
                scrollEntries.add(prepareClip(new Entry(el, el.meta() != null ? el.meta().clipRect() : null, buildTransformChain(el, elementMap), stat)));
            }
            if (isIgnoredElement(el)) continue;
            if (!isInteractiveOrOpaque(el, elementMap)) continue;

            List<DisplayItem> chain = buildTransformChain(el, elementMap);
            ClipRect clip = el.meta() != null ? el.meta().clipRect() : null;
            entries.add(prepareClip(new Entry(el, clip, chain, stat)));
        }
        this.interactiveEntries = entries;
        buildGrid();
    }

    /** クリップの部品の持ち主の鍵 → 持ち主の変形の連鎖（自分か祖先が動きうるときだけ。動かなければ空）。索引を作るときに揃え、以後は読むだけ。 */
    private final Long2ObjectOpenHashMap<List<DisplayItem>> ownerChains = new Long2ObjectOpenHashMap<>();

    private boolean moving(List<DisplayItem> chain, int upTo) {
        for (int i = 0; i < upTo; i++) if (!isStatic(chain.get(i), offsets) || dynamicKeys.contains(chain.get(i).key())) return true;
        return false;
    }

    /** 祖先に動きうる項目があれば、クリップを部品ごとに当てる印を付け、部品の持ち主の連鎖を用意する。 */
    private Entry prepareClip(Entry e) {
        ClipRect c = e.clipRect;
        if (c == null || c.chain().isEmpty() || !moving(e.transformChain, e.transformChain.size() - 1)) return e;
        for (int i = 0, n = c.chain().size(); i < n; i++) {
            String owner = c.chain().get(i).ownerId();
            long k = owner == null ? 0 : DisplayItem.keyOf(owner);
            DisplayItem o = owner == null || ownerChains.containsKey(k) ? null : elementMap.get(k);
            if (o == null) continue; // 持ち主が無い・表示リストに無い: フレームの空間のまま比べる
            List<DisplayItem> oc = buildTransformChain(o, elementMap);
            ownerChains.put(k, moving(oc, oc.size()) ? oc : List.of());
        }
        e.clipByParts = true;
        return e;
    }

    /**
     * 点がクリップの中か。{@link Entry#clipByParts} なら連鎖の部品ごとに、動く持ち主の部品は点を持ち主の今の姿勢で持ち主の空間へ戻して比べる
     * （部品の矩形は持ち主の空間〔親相対の節なら原点からの相対〕で、持ち主の変形と動く祖先に付いていく。{@link ClipResolver} と同じ約束）。
     */
    private boolean clipContains(Entry e, double px, double py, PrefixCache prefix, double[] tmp) {
        ClipRect c = e.clipRect;
        if (c == null || !e.clipByParts) return c == null || c.contains(px, py);
        for (int i = 0, n = c.chain().size(); i < n; i++) {
            ClipChainPart part = c.chain().get(i);
            if (part.rectLength() < 4) continue;
            double x = px, y = py, ox = part.ownerOriginX(), oy = part.ownerOriginY();
            List<DisplayItem> oc = part.ownerId() == null ? null : ownerChains.get(DisplayItem.keyOf(part.ownerId()));
            if (oc != null && !oc.isEmpty()) {
                prefix.apply(oc, oc.size(), tmp); // 祖先の連鎖の計算は 1 回の問い合わせの中で共有する
                x = tmp[0];
                y = tmp[1];
                DisplayItem o = oc.get(oc.size() - 1);
                if (o.type() == ElementType.NODE && o.hints().relativeOrigin()) ox = oy = 0;
            }
            if (!(x >= part.rectAt(0) + ox && x <= part.rectAt(2) + ox && y >= part.rectAt(1) + oy && y <= part.rectAt(3) + oy)) return false;
        }
        return true;
    }

    /** この索引が動きうる節として扱っている id。 */
    public java.util.Set<String> dynamicIds() {
        return dynamicIds;
    }

    /** 描画側が {@code pointer-events: none} の箱の部品に載せた印（{@link RenderHints#pointerEventsNone()}）。 */
    private static boolean isPointerEventsNone(DisplayItem el) {
        return el.hints().pointerEventsNone();
    }

    /** 装飾だけの部品（影・枠線・輪郭線・背景のレイヤー・プレースホルダ）。クリックを受けず、下の要素を隠さない。 */
    private static final String[] DECORATION_PARTS = {"_shadow", "_ph", "_b_", "_o_", "_bgl", "_bi"};

    /**
     * 部品の id は持ち主の id + 接尾辞なので、持ち主の id に "_b_" などが含まれても誤判定しないよう接尾辞だけを見る。
     *
     * 何が壊れていたか: 以前は "_shadow" で終わる id だけを除いていたので、番号付きの影（"_shadow_1" など）が
     * ヒットテストに残り、影の上のカーソルが存在しない id でホバーとクリックを起こしていた。
     */
    private static boolean isIgnoredElement(DisplayItem el) {
        if (el == null) return true;
        if (el.type() == ElementType.NODE) return true;
        if (isPointerEventsNone(el)) return true;

        String id = el.id();
        String part = id.startsWith(el.baseId()) ? id.substring(el.baseId().length()) : id;
        for (String prefix : DECORATION_PARTS) {
            if (part.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean isInteractiveOrOpaque(DisplayItem el, Long2ObjectOpenHashMap<DisplayItem> elementMap) {
        if (isPointerEventsNone(el)) return false;

        ElementType t = el.type();
        boolean isInput = t == ElementType.INPUT || t == ElementType.TEXTAREA || t == ElementType.SELECT
                || t == ElementType.RANGE || t == ElementType.TOGGLE || t == ElementType.CHECKBOX
                || t == ElementType.RADIO || t == ElementType.BUTTON || t == ElementType.A || t == ElementType.SCENE;
        if (isInput) return true;

        // スクロールバー
        if (el.partSuffix().startsWith("_sb_")) return true;

        // テキストノード
        if (t == ElementType.TEXT) return true;

        // ツールチップ・イベントや対話属性がある
        if (el.hints().title() != null) return true;
        if (el.events() != null) {
            for (String key : el.events().keySet()) {
                if (key.startsWith("on") || "draggable".equals(key)) {
                    return true;
                }
            }
        }

        // 置換要素（画像・キャンバス）
        if (t == ElementType.IMAGE || t == ElementType.CANVAS) {
            return true;
        }

        // 不透明な背景色（Alpha > 0）を持つ要素（遮蔽物として下のボタン貫通を防止）
        if ((el.bgColor() & 0xFF000000) != 0) {
            return true;
        }

        String pid = el.hints().parentId();
        DisplayItem parentAnchor = pid == null ? null : elementMap.get(DisplayItem.keyOf(pid));
        return parentAnchor != null && (parentAnchor.hasHover() || parentAnchor.hasPseudo());
    }

    private static List<DisplayItem> buildTransformChain(DisplayItem el, Long2ObjectOpenHashMap<DisplayItem> map) {
        List<DisplayItem> chain = new ArrayList<>(4);
        DisplayItem curr = el;
        while (curr != null) {
            chain.add(curr);
            String parentId = curr.hints().parentId();
            curr = parentId != null ? map.get(DisplayItem.keyOf(parentId)) : null;
        }
        Collections.reverse(chain);
        return chain;
    }

    public static double[] inverseTransform(double px, double py, DisplayItem el, AnimatedValues anim) {
        return inverseTransform(px, py, el, anim, 0f, 0f);
    }

    static double[] inverseTransform(double px, double py, DisplayItem el, AnimatedValues anim, float offX, float offY) {
        double[] out = new double[2];
        inverseInto(px, py, el, anim, offX, offY, out);
        return out;
    }

    /** {@link #inverseTransform} の、結果を {@code out} へ書く版（演算は同一）。 */
    static void inverseInto(double px, double py, DisplayItem el, AnimatedValues anim, double[] out) {
        inverseInto(px, py, el, anim, 0f, 0f, out);
    }

    static void inverseInto(double px, double py, DisplayItem el, AnimatedValues anim, float offX, float offY, double[] out) {
        RenderMeta meta = el.meta();
        float originX = meta != null ? meta.originX() : 0.5f;
        float originY = meta != null ? meta.originY() : 0.5f;
        boolean relOrigin = el.type() == ElementType.NODE && el.hints().relativeOrigin();

        float lx = (relOrigin ? 0f : el.x()) + el.width() * originX;
        float ly = (relOrigin ? 0f : el.y()) + el.height() * originY;
        double shiftX = (relOrigin ? el.x() : 0.0) + anim.tx() + offX;
        double shiftY = (relOrigin ? el.y() : 0.0) + anim.ty() + offY;

        double dx = px - lx - shiftX;
        double dy = py - ly - shiftY;
        if (anim.rot() != 0.0f) {
            double rad = -Math.toRadians(anim.rot());
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);
            double nx = dx * cos - dy * sin;
            double ny = dx * sin + dy * cos;
            dx = nx;
            dy = ny;
        }
        if (anim.skew() != 0.0f) dx -= Math.tan(Math.toRadians(anim.skew())) * dy; // skewX の逆
        if (anim.sx() != 0 && anim.sx() != 1.0f) dx /= anim.sx();
        if (anim.sy() != 0 && anim.sy() != 1.0f) dy /= anim.sy();

        out[0] = dx + lx;
        out[1] = dy + ly;
    }

    /**
     * 点の下の最前面の対話要素。空間ハッシュで候補を絞り、候補は旧実装（{@link #hitTestLinear}）と同じ判定で調べる
     * （上から順に最初に当たったもの）ので結果は同じ。割り当ては呼び出しごとに少数の小さな配列だけ。
     */
    public UIHitResult hitTest(String sessionId, double pixelX, double pixelY) {
        return hitTest(sessionId, pixelX, pixelY, null);
    }

    /** {@code accept} が null でないとき、条件を満たさない項目を飛ばして最前面の対話要素を探す（例: {@code inert} 除外）。 */
    public UIHitResult hitTest(String sessionId, double pixelX, double pixelY,
                               java.util.function.Predicate<DisplayItem> accept) {
        double[] local = new double[2];
        int i = find(sessionId, pixelX, pixelY, local, accept);
        return i < 0 ? null : new UIHitResult(entryArr[i].element, local[0], local[1]);
    }

    /** 点が対話要素の上にあるか（ゲーム側が、クリックを UI に渡すか自分で処理するかを決めるのに使う）。 */
    public boolean isPointerOverUi(String sessionId, double pixelX, double pixelY) {
        return isPointerOverUi(sessionId, pixelX, pixelY, null);
    }

    public boolean isPointerOverUi(String sessionId, double pixelX, double pixelY,
                                   java.util.function.Predicate<DisplayItem> accept) {
        return find(sessionId, pixelX, pixelY, new double[2], accept) >= 0;
    }

    private int find(String sessionId, double px, double py, double[] localOut,
                     java.util.function.Predicate<DisplayItem> accept) {
        int[] items = cellItems;
        int a = 0, aEnd = 0;
        int cell = cellOf(px, py);
        if (cell >= 0) {
            a = cellStart[cell];
            aEnd = cellStart[cell + 1];
        }
        int[] al = always;
        int b = 0;
        PrefixCache prefix = PREFIX.get().reset(this, sessionId, px, py, animationClock.getAsLong()); // スレッドごとに使い回す（問い合わせごとの表の拡張と割り当てを省く）
        AnimatedValues scratch = prefix.scratch;
        double[] p = prefix.pt;
        while (a < aEnd || b < al.length) {
            int idx;
            if (b >= al.length || (a < aEnd && items[a] < al[b])) idx = items[a++];
            else idx = al[b++];
            if (hits(entryArr[idx], sessionId, px, py, scratch, p, localOut, prefix)
                    && (accept == null || accept.test(entryArr[idx].element))) return idx;
        }
        return -1;
    }

    /**
     * 1 回の問い合わせの間だけ、祖先の連鎖を点に当てた結果（その祖先までの逆変換後の座標）を覚える。
     * 動く節（スクロールの中身など）の下の項目は全部が候補になるが、どれも同じ祖先の連鎖を共有するので、
     * 連鎖の計算（祖先ごとのアニメ状態の引き当てを含む）は祖先の数だけで済み、項目ごとには自分の 1 段だけを当てる。
     */
    private static final ThreadLocal<PrefixCache> PREFIX = ThreadLocal.withInitial(PrefixCache::new);

    private static final class PrefixCache {
        HitTestIndex owner;
        String sessionId;
        double px, py;
        /** この問い合わせで姿勢を評価する時刻。 */
        long now;
        final AnimatedValues scratch = new AnimatedValues();
        final double[] pt = new double[2], clipPt = new double[2];
        /** 鍵 → 結果の 2 要素（開地址法。容量は 2 のべき）。 */
        long[] keys = new long[16];
        double[] xs = new double[16], ys = new double[16];
        boolean[] used = new boolean[16];
        int size;
        final double[] tmp = new double[2];

        PrefixCache reset(HitTestIndex owner, String sessionId, double px, double py, long now) {
            this.owner = owner;
            this.sessionId = sessionId;
            this.px = px;
            this.py = py;
            this.now = now;
            if (size > 0) {
                java.util.Arrays.fill(used, false);
                size = 0;
            }
            return this;
        }

        /** {@code chain[0..upTo)} を当てた後の座標を {@code out} へ。 */
        void apply(List<DisplayItem> chain, int upTo, double[] out) {
            if (upTo == 0) {
                out[0] = px;
                out[1] = py;
                return;
            }
            long key = chain.get(upTo - 1).key();
            int mask = keys.length - 1;
            int i = (int) ((key * 0x9E3779B97F4A7C15L) >>> 40) & mask;
            while (used[i]) {
                if (keys[i] == key) {
                    out[0] = xs[i];
                    out[1] = ys[i];
                    return;
                }
                i = (i + 1) & mask;
            }
            apply(chain, upTo - 1, out); // 一段手前までの結果
            DisplayItem ancestor = chain.get(upTo - 1);
            inverseInto(out[0], out[1], ancestor, CssAnimator.valuesAtInto(sessionId, ancestor, scratch, now),
                    owner.offsets.dx(ancestor), owner.offsets.dy(ancestor), tmp);
            out[0] = tmp[0];
            out[1] = tmp[1];
            if (size * 2 >= keys.length) grow();
            insert(key, out[0], out[1]);
        }

        private void insert(long key, double x, double y) {
            int mask = keys.length - 1;
            int i = (int) ((key * 0x9E3779B97F4A7C15L) >>> 40) & mask;
            while (used[i]) i = (i + 1) & mask;
            used[i] = true;
            keys[i] = key;
            xs[i] = x;
            ys[i] = y;
            size++;
        }

        private void grow() {
            long[] ok = keys;
            double[] ox = xs, oy = ys;
            boolean[] ou = used;
            keys = new long[ok.length * 2];
            xs = new double[ok.length * 2];
            ys = new double[ok.length * 2];
            used = new boolean[ok.length * 2];
            size = 0;
            for (int i = 0; i < ok.length; i++) if (ou[i]) insert(ok[i], ox[i], oy[i]);
        }
    }

    private boolean hits(Entry entry, String sessionId, double pixelX, double pixelY,
                         AnimatedValues scratch, double[] p, double[] localOut, PrefixCache prefix) {
        DisplayItem el = entry.element;
        if (entry.clipRect != null && !clipContains(entry, pixelX, pixelY, prefix, prefix.clipPt)) return false;
        List<DisplayItem> chain = entry.transformChain;
        prefix.apply(chain, chain.size() - 1, p); // 親までの連鎖は共有して使い回す
        double x = p[0], y = p[1];
        if (entry.selfStatic) {
            // 静止した項目の逆変換は、中心を引いて足し戻すだけ（inverseInto と同じ演算。状態の引き当てを省く）
            RenderMeta meta = el.meta();
            double cx = el.x() + el.width() * (meta != null ? meta.originX() : 0.5f);
            double cy = el.y() + el.height() * (meta != null ? meta.originY() : 0.5f);
            x = (x - cx) + cx;
            y = (y - cy) + cy;
        } else {
            DisplayItem self = chain.get(chain.size() - 1);
            inverseInto(x, y, self, CssAnimator.valuesAtInto(sessionId, self, scratch, prefix.now), offsets.dx(self), offsets.dy(self), p);
            x = p[0];
            y = p[1];
        }
        double localX = x - el.x();
        double localY = y - el.y();
        if (localX >= 0 && localX <= el.width() && localY >= 0 && localY <= el.height()) {
            if (entry.alphaThreshold >= 0 && !alphaHit(entry, localX, localY)) return false;
            localOut[0] = localX;
            localOut[1] = localY;
            return true;
        }
        return false;
    }

    /** 画像の不透明な画素の上か。マスクが引けない（読み込み中など）ときは矩形の判定のまま当たりとする。 */
    private boolean alphaHit(Entry entry, double localX, double localY) {
        AlphaMaskProvider provider = alphaProvider;
        if (provider == null) return true;
        AlphaMask.Source s = provider.maskFor(entry.element, entry.alphaThreshold);
        return s == null || s.mask().hitsItem(entry.element, localX, localY, s.cssW(), s.cssH());
    }

    private int cellOf(double px, double py) {
        if (cols == 0) return -1;
        double fx = (px - gridX0) / cellSize, fy = (py - gridY0) / cellSize;
        if (!(fx >= 0) || !(fy >= 0)) return -1; // 範囲外・NaN
        int cx = (int) fx, cy = (int) fy;
        if (cx >= cols || cy >= rows) return -1;
        return cy * cols + cx;
    }

    /**
     * 変形・アニメーションの影響を受けない（祖先ごと含めて静止している）項目か。{@code AnimState} の補間値は、
     * {@code transition-duration} が 0 で {@code animation} が無ければ、いつも項目の静的な値（単位変換）になる。
     */
    private static boolean isStatic(DisplayItem it, PaintOffsets offsets) {
        if (it.translateX() != 0f || it.translateY() != 0f || it.rotate() != 0f || it.hints().skew() != 0f) return false;
        if (offsets.dx(it) != 0f || offsets.dy(it) != 0f) return false;
        float sx = it.scaleX(), sy = it.scaleY();
        if ((sx != 0f && sx != 1f) || (sy != 0f && sy != 1f)) return false;
        if (it.transitionDuration() > 0) return false;
        return it.animDef() == null || it.animDef().isEmpty();
    }

    private void buildGrid() {
        int n = interactiveEntries.size();
        entryArr = interactiveEntries.toArray(new Entry[0]);
        boolean[] gridded = new boolean[n];
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        int count = 0;
        for (int i = 0; i < n; i++) {
            Entry e = entryArr[i];
            boolean ok = true;
            for (DisplayItem c : e.transformChain) {
                if (!isStatic(c, offsets) || dynamicKeys.contains(c.key())) {
                    ok = false;
                    break;
                }
            }
            DisplayItem el = e.element;
            float wx = el.x() + e.staticOriginX, wy = el.y() + e.staticOriginY;
            float x0 = wx - 1f, y0 = wy - 1f, x1 = wx + el.width() + 1f, y1 = wy + el.height() + 1f;
            if (!ok || !Float.isFinite(x0 + y0 + x1 + y1) || x1 - x0 > 1e7f || y1 - y0 > 1e7f) continue;
            gridded[i] = true;
            count++;
            minX = Math.min(minX, x0);
            minY = Math.min(minY, y0);
            maxX = Math.max(maxX, x1);
            maxY = Math.max(maxY, y1);
        }
        int[] alwaysTmp = new int[n];
        int alwaysN = 0;
        if (count == 0) {
            cols = rows = 0;
            for (int i = 0; i < n; i++) alwaysTmp[alwaysN++] = i;
            always = java.util.Arrays.copyOf(alwaysTmp, alwaysN);
            return;
        }
        float w = maxX - minX, h = maxY - minY;
        cellSize = Math.max(32f, Math.max(w, h) / 128f);
        gridX0 = minX;
        gridY0 = minY;
        cols = Math.max(1, (int) Math.ceil(w / cellSize));
        rows = Math.max(1, (int) Math.ceil(h / cellSize));
        int total = cols * rows;
        int[] counts = new int[total + 1];
        // 数え上げ → 配置（CSR）。格子の 1/4 以上を覆う項目は、全セルに入れず「いつも候補」にする
        boolean[] big = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (!gridded[i]) continue;
            int[] r = cellRange(entryArr[i]);
            if ((long) (r[2] - r[0] + 1) * (r[3] - r[1] + 1) * 4 > total && total > 16) {
                big[i] = true;
                continue;
            }
            for (int cy = r[1]; cy <= r[3]; cy++) for (int cx = r[0]; cx <= r[2]; cx++) counts[cy * cols + cx + 1]++;
        }
        for (int i = 0; i < total; i++) counts[i + 1] += counts[i];
        cellStart = counts;
        cellItems = new int[counts[total]];
        int[] fill = new int[total];
        for (int i = 0; i < n; i++) {
            if (!gridded[i] || big[i]) {
                alwaysTmp[alwaysN++] = i;
                continue;
            }
            int[] r = cellRange(entryArr[i]);
            for (int cy = r[1]; cy <= r[3]; cy++) {
                for (int cx = r[0]; cx <= r[2]; cx++) {
                    int c = cy * cols + cx;
                    cellItems[cellStart[c] + fill[c]++] = i;
                }
            }
        }
        always = java.util.Arrays.copyOf(alwaysTmp, alwaysN);
    }

    /** 項目の矩形（1 画素広げた）が覆うセルの範囲 {@code {cx0, cy0, cx1, cy1}}。 */
    private int[] cellRange(Entry e) {
        DisplayItem el = e.element;
        float wx = el.x() + e.staticOriginX, wy = el.y() + e.staticOriginY;
        int cx0 = clampCell((wx - 1f - gridX0) / cellSize, cols), cx1 = clampCell((wx + el.width() + 1f - gridX0) / cellSize, cols);
        int cy0 = clampCell((wy - 1f - gridY0) / cellSize, rows), cy1 = clampCell((wy + el.height() + 1f - gridY0) / cellSize, rows);
        return new int[]{cx0, cy0, cx1, cy1};
    }

    private static int clampCell(float v, int max) {
        return Math.max(0, Math.min(max - 1, (int) Math.floor(v)));
    }

    /** 旧実装（上位から全件の線形探索）。空間ハッシュの結果と同じになることを確かめる基準として残す。 */
    public UIHitResult hitTestLinear(String sessionId, double pixelX, double pixelY) {
        return hitTestLinear(sessionId, pixelX, pixelY, null);
    }

    public UIHitResult hitTestLinear(String sessionId, double pixelX, double pixelY,
                                     java.util.function.Predicate<DisplayItem> accept) {
        double[] tmp = null;
        AnimatedValues scratchAnim = null;
        long now = animationClock.getAsLong();
        PrefixCache prefix = PREFIX.get().reset(this, sessionId, pixelX, pixelY, now);
        for (int i = 0, n = interactiveEntries.size(); i < n; i++) {
            Entry entry = interactiveEntries.get(i);
            DisplayItem el = entry.element;

            // クリップ判定 (D-9)。範囲外なら判定除外
            if (entry.clipRect != null && !clipContains(entry, pixelX, pixelY, prefix, prefix.clipPt)) continue;

            double px = pixelX;
            double py = pixelY;
            List<DisplayItem> chain = entry.transformChain;
            for (int j = 0, cn = chain.size(); j < cn; j++) {
                DisplayItem ancestor = chain.get(j);
                if (scratchAnim == null) scratchAnim = new AnimatedValues();
                AnimatedValues anim = CssAnimator.valuesAtInto(sessionId, ancestor, scratchAnim, now);
                if (tmp == null) tmp = new double[2];
                inverseInto(px, py, ancestor, anim, offsets.dx(ancestor), offsets.dy(ancestor), tmp);
                px = tmp[0];
                py = tmp[1];
            }

            double localX = px - el.x();
            double localY = py - el.y();

            if (localX >= 0 && localX <= el.width() && localY >= 0 && localY <= el.height()) {
                if (entry.alphaThreshold >= 0 && !alphaHit(entry, localX, localY)) continue;
                if (accept != null && !accept.test(el)) continue;
                return new UIHitResult(el, localX, localY);
            }
        }
        return null;
    }

    /**
     * 点の下にあるスクロールする箱を、手前のものから順に返す。祖先の変換の逆行列を宣言順に当てて箱の局所座標にし、
     * 祖先のクリップ（{@code overflow} / 角丸の外）に隠れた箱は除く。ホイールの宛先を決めるのに使う。
     */
    /** {@link #scrollContainersAt} の作業用の補間値（呼び出しのたびに確保しない。索引は 1 つの描画スレッドが使う）。 */
    private AnimatedValues scrollScratch;

    public List<UIHitResult> scrollContainersAt(String sessionId, double pixelX, double pixelY) {
        int n = scrollEntries.size();
        if (n == 0) return List.of();
        UIHitResult first = null;
        List<UIHitResult> out = null;
        double[] tmp = null;
        AnimatedValues scratchAnim = scrollScratch; // 補間状態が無い祖先ごとに値を確保しない（B9）
        long now = animationClock.getAsLong();
        PrefixCache prefix = null;
        for (int i = 0; i < n; i++) {
            Entry entry = scrollEntries.get(i);
            if (entry.clipByParts && prefix == null) prefix = PREFIX.get().reset(this, sessionId, pixelX, pixelY, now);
            if (entry.clipRect != null && !clipContains(entry, pixelX, pixelY, prefix, prefix == null ? null : prefix.clipPt)) continue;
            double px = pixelX, py = pixelY;
            List<DisplayItem> chain = entry.transformChain;
            for (int j = 0, cn = chain.size(); j < cn; j++) {
                DisplayItem ancestor = chain.get(j);
                if (tmp == null) tmp = new double[2];
                if (scratchAnim == null) scratchAnim = scrollScratch = new AnimatedValues();
                inverseInto(px, py, ancestor, CssAnimator.valuesAtInto(sessionId, ancestor, scratchAnim, now),
                        offsets.dx(ancestor), offsets.dy(ancestor), tmp);
                px = tmp[0];
                py = tmp[1];
            }
            DisplayItem el = entry.element;
            boolean relSelf = el.type() == ElementType.NODE && el.hints().relativeOrigin();
            double localX = relSelf ? px : px - el.x(), localY = relSelf ? py : py - el.y();
            if (localX >= 0 && localX <= el.width() && localY >= 0 && localY <= el.height()) {
                UIHitResult hit = new UIHitResult(el, localX, localY);
                if (first == null) {
                    first = hit;
                } else {
                    if (out == null) {
                        out = new ArrayList<>(Math.min(4, n - i + 1));
                        out.add(first);
                    }
                    out.add(hit);
                }
            }
        }
        if (out != null) return out;
        return first != null ? List.of(first) : List.of();
    }

    public UIHitResult getLocalCoords(String sessionId, DisplayItem el, double pixelX, double pixelY) {
        List<DisplayItem> chain = buildTransformChain(el, elementMap);
        double px = pixelX;
        double py = pixelY;
        long now = animationClock.getAsLong();
        for (DisplayItem ancestor : chain) {
            AnimatedValues anim = CssAnimator.valuesAtInto(sessionId, ancestor, new AnimatedValues(), now);
            double[] p = inverseTransform(px, py, ancestor, anim, offsets.dx(ancestor), offsets.dy(ancestor));
            px = p[0];
            py = p[1];
        }
        boolean relSelf = el.type() == ElementType.NODE && el.hints().relativeOrigin();
        return new UIHitResult(el, relSelf ? px : px - el.x(), relSelf ? py : py - el.y());
    }

    public DisplayItem getElementById(String id) {
        return elementMap.get(DisplayItem.keyOf(id));
    }

    public DisplayItem getElementByKey(long key) {
        return elementMap.get(key);
    }

    /** ノードハンドルと部品番号から項目を引く（文字列のハッシュを使わない）。 */
    public DisplayItem getElementByHandle(int nodeHandle, int partOrdinal) {
        return elementMap.get(DisplayItem.keyOf(nodeHandle, partOrdinal));
    }

    /** 節自身（錨 NODE）をノードハンドルから引く。 */
    public DisplayItem getElementByHandle(int nodeHandle) {
        return getElementByHandle(nodeHandle, DisplayItem.PART_NODE);
    }
}
