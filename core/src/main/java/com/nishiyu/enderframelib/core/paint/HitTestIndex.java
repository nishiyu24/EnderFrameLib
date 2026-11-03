package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.*;

import com.nishiyu.enderframelib.core.paint.CssAnimator;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

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

        public Entry(DisplayItem element, ClipRect clipRect, List<DisplayItem> transformChain) {
            this.element = element;
            this.clipRect = clipRect;
            this.transformChain = transformChain;
            this.alphaThreshold = alphaThresholdOf(element);
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
    private volatile AlphaMask.Provider alphaProvider;

    public void alphaMaskProvider(AlphaMask.Provider provider) {
        this.alphaProvider = provider;
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

    public HitTestIndex(List<DisplayItem> elements) {
        if (elements == null || elements.isEmpty()) {
            this.interactiveEntries = Collections.emptyList();
            this.elementMap = new Long2ObjectOpenHashMap<>(0);
            buildGrid();
            return;
        }

        this.elementMap = new Long2ObjectOpenHashMap<>(elements.size());
        for (DisplayItem el : elements) {
            elementMap.put(el.key(), el);
        }

        List<Entry> entries = new ArrayList<>();
        // elements は描画順（下層から上層）なので、逆順で探索できるように保持
        for (int i = elements.size() - 1; i >= 0; i--) {
            DisplayItem el = elements.get(i);
            if (el.hints().scrollable() && !isPointerEventsNone(el)) {
                scrollEntries.add(new Entry(el, el.meta() != null ? el.meta().clipRect() : null, buildTransformChain(el, elementMap)));
            }
            if (isIgnoredElement(el)) continue;
            if (!isInteractiveOrOpaque(el, elementMap)) continue;

            List<DisplayItem> chain = buildTransformChain(el, elementMap);
            ClipRect clip = el.meta() != null ? el.meta().clipRect() : null;
            entries.add(new Entry(el, clip, chain));
        }
        this.interactiveEntries = entries;
        buildGrid();
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
        DisplayItem parentAnchor = pid == null ? null : elementMap.get(ItemKeys.of(pid));
        return parentAnchor != null && (parentAnchor.hasHover() || parentAnchor.hasPseudo());
    }

    private static List<DisplayItem> buildTransformChain(DisplayItem el, Long2ObjectOpenHashMap<DisplayItem> map) {
        List<DisplayItem> chain = new ArrayList<>(4);
        DisplayItem curr = el;
        while (curr != null) {
            chain.add(curr);
            String parentId = curr.hints().parentId();
            curr = parentId != null ? map.get(ItemKeys.of(parentId)) : null;
        }
        Collections.reverse(chain);
        return chain;
    }

    public static double[] inverseTransform(double px, double py, DisplayItem el, AnimatedValues anim) {
        double[] out = new double[2];
        inverseInto(px, py, el, anim, out);
        return out;
    }

    /** {@link #inverseTransform} の、結果を {@code out} へ書く版（演算は同一）。 */
    static void inverseInto(double px, double py, DisplayItem el, AnimatedValues anim, double[] out) {
        RenderMeta meta = el.meta();
        float originX = meta != null ? meta.originX() : 0.5f;
        float originY = meta != null ? meta.originY() : 0.5f;

        float cx = el.x() + el.width() * originX;
        float cy = el.y() + el.height() * originY;

        double dx = px - cx - anim.tx();
        double dy = py - cy - anim.ty();
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

        out[0] = dx + cx;
        out[1] = dy + cy;
    }

    /**
     * 点の下の最前面の対話要素。空間ハッシュで候補を絞り、候補は旧実装（{@link #hitTestLinear}）と同じ判定で調べる
     * （上から順に最初に当たったもの）ので結果は同じ。割り当ては呼び出しごとに少数の小さな配列だけ。
     */
    public UIHitResult hitTest(String sessionId, double pixelX, double pixelY) {
        double[] local = new double[2];
        int i = find(sessionId, pixelX, pixelY, local);
        return i < 0 ? null : new UIHitResult(entryArr[i].element, local[0], local[1]);
    }

    /** 点が対話要素の上にあるか（ゲーム側が、クリックを UI に渡すか自分で処理するかを決めるのに使う）。 */
    public boolean isPointerOverUi(String sessionId, double pixelX, double pixelY) {
        return find(sessionId, pixelX, pixelY, new double[2]) >= 0;
    }

    private int find(String sessionId, double px, double py, double[] localOut) {
        int[] items = cellItems;
        int a = 0, aEnd = 0;
        int cell = cellOf(px, py);
        if (cell >= 0) {
            a = cellStart[cell];
            aEnd = cellStart[cell + 1];
        }
        int[] al = always;
        int b = 0;
        AnimatedValues scratch = new AnimatedValues();
        double[] p = new double[2];
        while (a < aEnd || b < al.length) {
            int idx;
            if (b >= al.length || (a < aEnd && items[a] < al[b])) idx = items[a++];
            else idx = al[b++];
            if (hits(entryArr[idx], sessionId, px, py, scratch, p, localOut)) return idx;
        }
        return -1;
    }

    private boolean hits(Entry entry, String sessionId, double pixelX, double pixelY,
                         AnimatedValues scratch, double[] p, double[] localOut) {
        DisplayItem el = entry.element;
        if (entry.clipRect != null && !entry.clipRect.contains(pixelX, pixelY)) return false;
        double x = pixelX, y = pixelY;
        List<DisplayItem> chain = entry.transformChain;
        for (int i = 0, n = chain.size(); i < n; i++) {
            DisplayItem ancestor = chain.get(i);
            inverseInto(x, y, ancestor, CssAnimator.currentValuesInto(sessionId, ancestor, scratch), p);
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
        AlphaMask.Provider provider = alphaProvider;
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
    private static boolean isStatic(DisplayItem it) {
        if (it.translateX() != 0f || it.translateY() != 0f || it.rotate() != 0f || it.hints().skew() != 0f) return false;
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
                if (!isStatic(c)) {
                    ok = false;
                    break;
                }
            }
            DisplayItem el = e.element;
            float x0 = el.x() - 1f, y0 = el.y() - 1f, x1 = el.x() + el.width() + 1f, y1 = el.y() + el.height() + 1f;
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
            int[] r = cellRange(entryArr[i].element);
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
            int[] r = cellRange(entryArr[i].element);
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
    private int[] cellRange(DisplayItem el) {
        int cx0 = clampCell((el.x() - 1f - gridX0) / cellSize, cols), cx1 = clampCell((el.x() + el.width() + 1f - gridX0) / cellSize, cols);
        int cy0 = clampCell((el.y() - 1f - gridY0) / cellSize, rows), cy1 = clampCell((el.y() + el.height() + 1f - gridY0) / cellSize, rows);
        return new int[]{cx0, cy0, cx1, cy1};
    }

    private static int clampCell(float v, int max) {
        return Math.max(0, Math.min(max - 1, (int) Math.floor(v)));
    }

    /** 旧実装（上位から全件の線形探索）。空間ハッシュの結果と同じになることを確かめる基準として残す。 */
    public UIHitResult hitTestLinear(String sessionId, double pixelX, double pixelY) {
        for (Entry entry : interactiveEntries) {
            DisplayItem el = entry.element;

            // クリップ判定 (D-9)
            // entry.clipRect は仮想座標での可視矩形。pixelX, pixelY がクリップ範囲外なら判定除外
            if (entry.clipRect != null && !entry.clipRect.contains(pixelX, pixelY)) {
                continue;
            }

            double px = pixelX;
            double py = pixelY;
            for (DisplayItem ancestor : entry.transformChain) {
                AnimatedValues anim = CssAnimator.getCurrentValues(sessionId, ancestor);
                double[] p = inverseTransform(px, py, ancestor, anim);
                px = p[0];
                py = p[1];
            }

            double localX = px - el.x();
            double localY = py - el.y();

            if (localX >= 0 && localX <= el.width() && localY >= 0 && localY <= el.height()) {
                if (entry.alphaThreshold >= 0 && !alphaHit(entry, localX, localY)) continue;
                return new UIHitResult(el, localX, localY);
            }
        }
        return null;
    }

    /**
     * 点の下にあるスクロールする箱を、手前のものから順に返す。祖先の変換の逆行列を宣言順に当てて箱の局所座標にし、
     * 祖先のクリップ（{@code overflow} / 角丸の外）に隠れた箱は除く。ホイールの宛先を決めるのに使う。
     */
    public List<UIHitResult> scrollContainersAt(String sessionId, double pixelX, double pixelY) {
        List<UIHitResult> out = new ArrayList<>();
        for (Entry entry : scrollEntries) {
            if (entry.clipRect != null && !entry.clipRect.contains(pixelX, pixelY)) continue;
            double px = pixelX, py = pixelY;
            for (DisplayItem ancestor : entry.transformChain) {
                double[] p = inverseTransform(px, py, ancestor, CssAnimator.getCurrentValues(sessionId, ancestor));
                px = p[0];
                py = p[1];
            }
            DisplayItem el = entry.element;
            double localX = px - el.x(), localY = py - el.y();
            if (localX >= 0 && localX <= el.width() && localY >= 0 && localY <= el.height()) {
                out.add(new UIHitResult(el, localX, localY));
            }
        }
        return out;
    }

    public UIHitResult getLocalCoords(String sessionId, DisplayItem el, double pixelX, double pixelY) {
        List<DisplayItem> chain = buildTransformChain(el, elementMap);
        double px = pixelX;
        double py = pixelY;
        for (DisplayItem ancestor : chain) {
            AnimatedValues anim = CssAnimator.getCurrentValues(sessionId, ancestor);
            double[] p = inverseTransform(px, py, ancestor, anim);
            px = p[0];
            py = p[1];
        }
        return new UIHitResult(el, px - el.x(), py - el.y());
    }

    public DisplayItem getElementById(String id) {
        return elementMap.get(ItemKeys.of(id));
    }

    public DisplayItem getElementByKey(long key) {
        return elementMap.get(key);
    }
}
