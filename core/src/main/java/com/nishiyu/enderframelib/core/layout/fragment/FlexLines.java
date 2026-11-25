package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.css.CssLength;
import com.nishiyu.enderframelib.core.css.FlexGridStyle;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.CssKeywords;
import com.nishiyu.enderframelib.core.css.model.CssKeywords.Alignment;
import com.nishiyu.enderframelib.core.css.model.CssKeywords.FlexWrap;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;

import java.util.ArrayList;
import java.util.List;

/**
 * flex の行の解決（Engine v3 M2b-2。CSS Flexbox §9）。軸は論理（主軸・交差軸）で、{@code row} / {@code column}・
 * {@code *-reverse}・{@code flex-wrap}（{@code wrap-reverse} を含む）を 1 つの手順で扱う。
 *
 * <ol>
 *   <li>項目ごとに flex base size と仮の主軸の大きさ（{@link #rowBase} / {@link #columnBase}。内在サイズは {@link LayoutCache#minMax}、
 *       column の内容の高さは自分の {@code height} を掛けない pass 1 の整形）</li>
 *   <li>行に分ける（主軸の大きさ、高さが不定の column は {@code max-height}）・行ごとに柔軟な長さを解く（§9.7）</li>
 *   <li>解いた主軸の大きさで測る・行の交差軸の大きさ（ベースライン揃えは上と下を別に数える）・{@code align-content}</li>
 *   <li>stretch（確定した交差軸の大きさで測り直す）・主軸の auto 余白と {@code justify-content}・交差軸の auto 余白と {@code align-self}</li>
 * </ol>
 *
 * <p>状態は 1 回の整形の間だけで、結果は項目の {@code mainPos} / {@code crossPos} と {@link #baseline}。</p>
 */
final class FlexLines {
    private final Style st;
    private final boolean row, reverse, wrap, wrapReverse, rtl;
    /** 内容ボックスの幅・高さ（高さは不定なら NaN）と、高さが不定のときに内容の高さを挟む min / max。 */
    private final float cw, ch, minCH, maxCH;
    private final float mainGap, crossGap;
    private final LayoutSession s;
    private final LayoutCache cache;
    private final List<List<FlexItem>> lines = new ArrayList<>();
    private float[] lineCross;
    /** 主軸・交差軸の内容ボックスの大きさ（{@link #run} の後）。 */
    float innerMain, innerCross;
    /** 容器の最初のベースライン（内容ボックスの上端から。項目が無ければ NaN）。 */
    float baseline = Float.NaN;

    FlexLines(Style st, float cw, float ch, float minCH, float maxCH, LayoutSession s) {
        this.st = st;
        FlexGridStyle fg = st.flexGrid;
        this.row = fg.flexDirection == null || fg.flexDirection.isRow();
        this.reverse = fg.flexDirection != null && fg.flexDirection.isReverse();
        this.wrap = fg.flexWrap == FlexWrap.WRAP || fg.flexWrap == FlexWrap.WRAP_REVERSE;
        this.wrapReverse = fg.flexWrap == FlexWrap.WRAP_REVERSE;
        this.rtl = st.facts.rtl;
        this.cw = cw;
        this.ch = ch;
        this.minCH = minCH;
        this.maxCH = maxCH;
        float colGap = gap(st, fg.columnGap, cw, s), rowGap = gap(st, fg.rowGap, ch, s);
        this.mainGap = row ? colGap : rowGap;
        this.crossGap = row ? rowGap : colGap;
        this.s = s;
        this.cache = s.cache();
    }

    boolean row() { return row; }

    /** 全手順を解き、内容の高さ（内容ボックス）を返す。 */
    float run(List<FlexItem> items) {
        for (FlexItem it : items) {
            if (row) rowBase(it);
            else columnBase(it);
            it.hyp = clamp(it.base, it.minMain, it.maxMain);
        }
        buildLines(items);
        innerMain = row ? cw : !Float.isNaN(ch) ? ch : clamp(longestLine(), minCH, maxCH);
        for (List<FlexItem> line : lines) {
            resolveFlexible(line, innerMain);
            for (FlexItem it : line) measureMain(it);
        }
        int n = lines.size();
        lineCross = new float[n];
        float[] lineBase = new float[n];
        float sum = crossGap * Math.max(0, n - 1);
        for (int i = 0; i < n; i++) {
            float[] cb = lineCross(lines.get(i));
            lineCross[i] = cb[0];
            lineBase[i] = cb[1];
            sum += cb[0];
        }
        innerCross = !row ? cw : !Float.isNaN(ch) ? ch : clamp(sum, minCH, maxCH);
        if (!wrap && n == 1) lineCross[0] = innerCross; // 1 行の容器の行は容器の大きさ（§9.4 の 15）
        float[] linePos = alignContent(sum);
        for (int i = 0; i < n; i++) {
            List<FlexItem> line = lines.get(i);
            stretch(line, lineCross[i]);
            placeMain(line);
            for (FlexItem it : line) placeCross(it, linePos[i], lineCross[i], lineBase[i]);
        }
        containerBaseline();
        return row ? innerCross : innerMain;
    }

    // ---- 1. 基準の大きさ ----

    private void rowBase(FlexItem it) {
        Style cst = it.box.style;
        BoxGeometry ig = it.g;
        float avail = Math.max(0, cw - ig.mL - ig.mR);
        float specW = ig.specifiedWidth(it.box, cw, avail, s);
        long fb = cst.flexGrid.flexBasis;
        MinMaxSizes mm = null;
        if (basisDefinite(cst, fb, cw)) {
            float b = LayoutUnit.snap(cst.gridPx(fb, cw, s.viewportW(), s.viewportH()));
            it.base = Math.max(ig.hdec(), cst.facts.borderBox ? b : b + ig.hdec());
        } else if (CssLength.isAutoOrUndeclared(fb) && !Float.isNaN(specW)) { // 幅を見るのは flex-basis: auto だけ
            it.base = specW;
        } else if (CssLength.isAutoOrUndeclared(fb) && !Float.isNaN(ratioBase(it))) { // aspect-ratio と確定した交差軸の大きさ（§9.2 3B）
            it.base = ratioBase(it);
        } else {
            mm = cache.minMax(it.box, s); // 内容基準の基準の大きさ（仮のレイアウトをしない）
            it.base = intrinsicBasis(fb, cst, mm, avail);
        }
        it.maxMain = BoxGeometry.definite(cst, cst.box.maxWidth, cw) ? ig.clampWidth(cst, Float.MAX_VALUE, cw, s) : Float.MAX_VALUE;
        if (BoxGeometry.definite(cst, cst.box.minWidth, cw)) {
            it.minMain = ig.clampWidth(cst, 0, cw, s);
        } else if (overflowVisible(cst)) { // 自動の最小サイズ: min-content（指定の幅・最大の幅があればそれ以下。§4.5）
            if (mm == null) mm = cache.minMax(it.box, s);
            float m = Float.isNaN(specW) ? mm.min : Math.min(mm.min, specW);
            it.minMain = Math.max(ig.hdec(), Math.min(m, it.maxMain));
        } else {
            it.minMain = ig.hdec();
        }
    }

    /**
     * {@code aspect-ratio} を持ち幅が auto の row の項目の、確定した交差軸の大きさ（指定の高さ、または 1 行の容器の確定した高さへの
     * stretch）を比で移した基準の大きさ（CSS Flexbox §9.2 3B。旧 {@code FlexLayoutEngine.ratioBaseSize}）。当たらなければ NaN。
     */
    private float ratioBase(FlexItem it) {
        Style cst = it.box.style;
        BoxGeometry ig = it.g;
        if (!(BoxGeometry.aspectRatio(cst) > 0) || !BoxGeometry.autoWidth(cst)) return Float.NaN;
        float h = ig.specifiedHeight(cst, ch, s);
        if (Float.isNaN(h)) {
            if (wrap || Float.isNaN(ch) || it.align != Alignment.STRETCH || it.autoCS || it.autoCE) return Float.NaN;
            h = Math.max(0, ch - it.mCS - it.mCE);
        }
        return ig.ratioWidth(cst, ig.clampHeight(cst, h, ch, s), cw, s);
    }

    /** 内容基準の基準の大きさ: {@code min-content} / {@code fit-content} の語ならその内在サイズ、他は max-content。 */
    private static float intrinsicBasis(long fb, Style cst, MinMaxSizes mm, float avail) {
        if (CssLength.isKeyword(fb, CssLength.KW_MIN_CONTENT)) return mm.min;
        if (CssLength.isKeyword(fb, CssLength.KW_FIT_CONTENT)) return Math.min(mm.max, Math.max(mm.min, avail));
        if (!CssLength.isDeclared(fb) || CssLength.isAuto(fb)) { // auto で幅も内在サイズの語
            long w = cst.box.width;
            if (CssLength.isKeyword(w, CssLength.KW_MIN_CONTENT)) return mm.min;
        }
        return mm.max;
    }

    private void columnBase(FlexItem it) {
        Style cst = it.box.style;
        BoxGeometry ig = it.g;
        float avail = Math.max(0, cw - ig.mL - ig.mR);
        float specW = ig.specifiedWidth(it.box, cw, avail, s);
        if (!Float.isNaN(specW)) {
            it.crossW = ig.clampWidth(cst, specW, cw, s);
        } else if (it.align == Alignment.STRETCH && !it.autoCross() && !wrap) { // 1 行なら行の大きさ＝容器の幅まで先に伸ばす
            it.crossW = ig.clampWidth(cst, avail, cw, s);
        } else { // 縮小して合わせる（折り返す column の stretch は行の大きさが決まってから伸ばす）
            MinMaxSizes mm = cache.minMax(it.box, s);
            it.crossW = ig.clampWidth(cst, Math.min(mm.max, Math.max(mm.min, avail)), cw, s);
        }
        it.crossW = LayoutUnit.snap(it.crossW);
        // pass 1: 幅を確定し、高さは自分の height を掛けない内容から（内容基準の基準の大きさ・自動の最小サイズ）
        it.frag = cache.layout(it.box, itemSpace(it, it.crossW, Float.NaN, ConstraintSpace.CONTENT_BLOCK_SIZE), it.slots, s);
        float content = it.frag.height;
        long fb = cst.flexGrid.flexBasis;
        float specH = ig.specifiedHeight(cst, ch, s);
        // 表の height は表の格子の箱の高さで、項目（表の外枠の箱）はキャプションをその外に足す（M2b-4。CSS Tables 3 §3.10）
        if (!Float.isNaN(specH) && it.box.kind.tableContainer()) specH += TableFlow.captionExtent(it.frag, s);
        if (basisDefinite(cst, fb, ch)) {
            float b = LayoutUnit.snap(cst.gridPx(fb, Float.isFinite(ch) ? ch : 0, s.viewportW(), s.viewportH()));
            it.base = Math.max(ig.vdec(), cst.facts.borderBox ? b : b + ig.vdec());
        } else {
            // 高さを見るのは flex-basis: auto だけ（content・基準の無い % は内容から。CSS Flexbox §9.2 3.E）
            it.base = CssLength.isAutoOrUndeclared(fb) && !Float.isNaN(specH) ? specH : content;
        }
        it.maxMain = BoxGeometry.definite(cst, cst.box.maxHeight, ch) ? ig.clampHeight(cst, Float.MAX_VALUE, ch, s) : Float.MAX_VALUE;
        if (BoxGeometry.definite(cst, cst.box.minHeight, ch)) {
            it.minMain = ig.clampHeight(cst, 0, ch, s);
        } else if (overflowVisible(cst)) {
            float m = Float.isNaN(specH) ? content : Math.min(content, specH);
            it.minMain = Math.max(ig.vdec(), Math.min(m, it.maxMain));
        } else {
            it.minMain = ig.vdec();
        }
    }

    // ---- 2. 行と柔軟な長さ ----

    private void buildLines(List<FlexItem> items) {
        float limit = row ? cw : !Float.isNaN(ch) ? ch : maxCH < Float.MAX_VALUE / 8 ? maxCH : Float.NaN;
        if (!wrap || Float.isNaN(limit)) {
            if (!items.isEmpty()) lines.add(new ArrayList<>(items));
            return;
        }
        List<FlexItem> line = new ArrayList<>();
        float used = 0;
        for (FlexItem it : items) {
            float outer = it.hyp + it.outerMain();
            if (!line.isEmpty() && used + mainGap + outer > limit + 0.01f) {
                lines.add(line);
                line = new ArrayList<>();
                used = 0;
            }
            used += (line.isEmpty() ? 0 : mainGap) + outer;
            line.add(it);
        }
        if (!line.isEmpty()) lines.add(line);
    }

    /** 仮の主軸の大きさの和が最も大きい行（column の高さが不定のときの内容の高さ）。 */
    private float longestLine() {
        float max = 0;
        for (List<FlexItem> line : lines) {
            float used = mainGap * Math.max(0, line.size() - 1);
            for (FlexItem it : line) used += it.hyp + it.outerMain();
            max = Math.max(max, used);
        }
        return max;
    }

    /** 柔軟な長さの解決（CSS Flexbox §9.7。凍結を繰り返し、min/max の違反を解く）。結果は {@code target}。 */
    static void resolveFlexible(List<FlexItem> line, float avail, float gapW) {
        float gaps = gapW * Math.max(0, line.size() - 1);
        float used = gaps;
        for (FlexItem it : line) used += it.hyp + it.outerMain();
        boolean growing = used < avail;
        float initialFree = avail - gaps;
        for (FlexItem it : line) {
            initialFree -= it.outerMain() + it.base;
            float factor = growing ? it.grow : it.shrink;
            it.frozen = factor == 0 || (growing ? it.base > it.hyp : it.base < it.hyp);
            it.target = it.frozen ? it.hyp : it.base;
        }
        for (int iter = 0; iter <= line.size(); iter++) {
            float free = avail - gaps, sumGrow = 0, sumScaled = 0;
            boolean anyOpen = false;
            for (FlexItem it : line) {
                free -= it.outerMain() + (it.frozen ? it.target : it.base);
                if (!it.frozen) {
                    anyOpen = true;
                    sumGrow += it.grow;
                    sumScaled += it.shrink * it.base;
                }
            }
            if (!anyOpen) break;
            if (growing && sumGrow < 1) free = Math.min(free, initialFree * sumGrow);
            float violation = 0;
            for (FlexItem it : line) {
                if (it.frozen) continue;
                float t = it.base;
                if (growing && sumGrow > 0) t += free * it.grow / sumGrow;
                else if (!growing && sumScaled > 0) t += free * it.shrink * it.base / sumScaled;
                float c = clamp(t, it.minMain, it.maxMain);
                violation += c - t;
                it.raw = t;
                it.target = c;
            }
            for (FlexItem it : line) { // 違反の総和の向きで凍結する（0 なら全部、正なら min 違反、負なら max 違反）
                if (it.frozen) continue;
                if (violation == 0 || (violation > 0 && it.target > it.raw) || (violation < 0 && it.target < it.raw)) it.frozen = true;
            }
        }
        for (FlexItem it : line) it.target = LayoutUnit.snap(it.target);
    }

    private void resolveFlexible(List<FlexItem> line, float avail) {
        resolveFlexible(line, avail, mainGap);
    }

    // ---- 3. 測る・行の交差軸 ----

    /** 解いた主軸の大きさで測る（row: 幅を確定し高さは内容から／column: 幅と高さを確定）。 */
    private void measureMain(FlexItem it) {
        if (row) {
            it.frag = cache.layout(it.box, itemSpace(it, it.target, Float.NaN, 0), it.slots, s);
        } else if (it.target != it.frag.height) {
            // 内容の高さのままなら pass 1 の断片を使う（高さが不定の column の項目の高さは、指定があっても子の % の基準として
            // 確定しない。WPT percentage-heights-017）
            it.frag = cache.layout(it.box, itemSpace(it, it.crossW, it.target, 0), it.slots, s);
        }
    }

    /** 行の {交差軸の大きさ, ベースラインより上の最大}。ベースラインで揃える項目は上と下を別に数える（row のみ）。 */
    private float[] lineCross(List<FlexItem> line) {
        float cross = 0, above = 0, below = 0;
        for (FlexItem it : line) {
            float outer = it.outerCross(row);
            if (row && it.align == Alignment.BASELINE && !it.autoCross()) {
                float b = it.mCS + itemBaseline(it.frag);
                it.baseline = b;
                above = Math.max(above, b);
                below = Math.max(below, outer - b);
            } else {
                cross = Math.max(cross, outer);
            }
        }
        return new float[]{Math.max(cross, above + below), above};
    }

    /** 項目のベースライン（border-box の上端から）。行を持たなければ border-box の下端から合成する。 */
    static float itemBaseline(LayoutFragment f) {
        float b = InlineFlow.baselineOf(f, true);
        return Float.isNaN(b) ? f.height : b;
    }

    /** 行の交差軸の位置（{@code align-content}。折り返す容器だけ）。{@code used} は行と間隔の和。 */
    private float[] alignContent(float used) {
        int n = lines.size();
        float[] pos = new float[n];
        float start = 0, between = crossGap;
        if (wrap && n > 0) {
            FlexGridStyle fg = st.flexGrid;
            Alignment ac = fg.alignContent == null ? Alignment.NORMAL : fg.alignContent;
            float free = innerCross - used;
            if ((ac == Alignment.NORMAL || ac == Alignment.STRETCH) && free > 0) {
                for (int i = 0; i < n; i++) lineCross[i] += free / n;
                free = 0;
            }
            if (free < -0.01f && fg.alignContentSafe) ac = Alignment.START;
            // wrap-reverse は交差軸の始点が下（右）。start / end は書字方向の向きなので、下の裏返しと打ち消すよう入れ替える
            if (wrapReverse) ac = ac == Alignment.START ? Alignment.END : ac == Alignment.END ? Alignment.START : ac;
            float[] d = distribute(ac, free, n, crossGap);
            start = d[0];
            between = d[1];
        }
        float p = start;
        for (int i = 0; i < n; i++) {
            pos[i] = wrapReverse ? innerCross - p - lineCross[i] : p;
            p += lineCross[i] + between;
        }
        return pos;
    }

    // ---- 4. stretch と位置 ----

    /** {@code align-self: stretch} で交差軸の大きさが auto の項目を、行の交差軸の大きさで測り直す（§9.4 の 11）。 */
    private void stretch(List<FlexItem> line, float cross) {
        for (FlexItem it : line) {
            if (it.align != Alignment.STRETCH || it.autoCross()) continue;
            Style cst = it.box.style;
            if (row) {
                if (BoxGeometry.definite(cst, cst.box.height, ch)) continue;
                float t = LayoutUnit.snap(it.g.clampHeight(cst, Math.max(0, cross - it.mCS - it.mCE), ch, s));
                if (t != it.frag.height) it.frag = cache.layout(it.box, itemSpace(it, it.target, t, 0), it.slots, s);
            } else {
                if (BoxGeometry.definite(cst, cst.box.width, cw) || !CssLength.isAutoOrUndeclared(cst.box.width)) continue;
                float t = LayoutUnit.snap(it.g.clampWidth(cst, Math.max(0, cross - it.mCS - it.mCE), cw, s));
                if (t != it.crossW) {
                    it.crossW = t;
                    it.frag = cache.layout(it.box, itemSpace(it, t, it.target, 0), it.slots, s);
                }
            }
        }
    }

    /** 主軸の位置: auto 余白が正の余りを先に吸い（§8.1）、無ければ {@code justify-content}。{@code *-reverse} は裏返す。 */
    private void placeMain(List<FlexItem> line) {
        int n = line.size();
        float used = mainGap * Math.max(0, n - 1);
        int autos = 0;
        for (FlexItem it : line) {
            used += it.mainSize(row) + it.outerMain();
            if (it.autoMS) autos++;
            if (it.autoME) autos++;
        }
        float free = innerMain - used;
        float autoM = autos > 0 && free > 0 ? free / autos : 0;
        float start = 0, between = mainGap;
        if (autos == 0) {
            FlexGridStyle fg = st.flexGrid;
            Alignment jc = fg.justifyContent == null ? Alignment.NORMAL : fg.justifyContent;
            if (free < -0.01f && fg.justifyContentSafe) jc = Alignment.START;
            float[] d = distribute(flexRelative(jc), free, n, mainGap);
            start = d[0];
            between = d[1];
        }
        float pos = start;
        for (FlexItem it : line) {
            if (it.autoMS) it.mMS = autoM;
            if (it.autoME) it.mME = autoM;
            float outer = it.mainSize(row) + it.outerMain();
            float mb = reverse ? innerMain - pos - outer : pos; // 余白の箱の主軸の始点（物理の左・上）
            it.mainPos = mb + it.mMS;
            pos += outer + between;
        }
    }

    /**
     * {@code justify-content} を主軸の向きの語へ寄せる。{@code start} / {@code end} は書字方向・{@code left} / {@code right} は物理の
     * 向きで、{@code *-reverse} では裏返らない（主軸が逆向きなら flex-end / flex-start）。column の主軸はインライン軸と平行でないので
     * {@code left} / {@code right} は start（CSS Box Alignment 3 §6.1）。
     */
    private Alignment flexRelative(Alignment jc) {
        if (!row && (jc == Alignment.LEFT || jc == Alignment.RIGHT)) jc = Alignment.START;
        return switch (jc) {
            case START, LEFT -> reverse ? Alignment.FLEX_END : Alignment.FLEX_START;
            case END, RIGHT -> reverse ? Alignment.FLEX_START : Alignment.FLEX_END;
            default -> jc;
        };
    }

    /** 交差軸の位置: auto 余白が余りを先に吸い、無ければ {@code align-self}。{@code wrap-reverse} は flex 相対の揃えを裏返す。 */
    private void placeCross(FlexItem it, float lineStart, float cross, float lineBase) {
        float outer = it.outerCross(row);
        if (it.autoCross()) {
            float free = Math.max(0, cross - outer);
            if (it.autoCS) it.mCS = it.autoCE ? free / 2 : free;
            if (it.autoCE) it.mCE = it.autoCS ? free / 2 : free;
            it.crossPos = lineStart + it.mCS;
            return;
        }
        float off = switch (it.align) {
            case CENTER -> (cross - outer) / 2;
            case FLEX_END, END, SELF_END -> cross - outer;
            case BASELINE -> row ? lineBase - it.baseline : 0;
            default -> 0;
        };
        boolean flexRel = it.align == Alignment.FLEX_START || it.align == Alignment.FLEX_END || it.align == Alignment.STRETCH
                || (it.align == Alignment.BASELINE && !row);
        if (wrapReverse && flexRel) off = cross - outer - off;
        it.crossPos = lineStart + off + it.mCS;
    }

    /** 容器の最初のベースライン（§8.5）: 最初の行にベースラインで揃える項目があればそれ、無ければ最初の行の最初の項目。 */
    private void containerBaseline() {
        if (lines.isEmpty() || lines.get(0).isEmpty()) return;
        List<FlexItem> first = lines.get(0);
        FlexItem pick = first.get(0);
        if (row) {
            for (FlexItem it : first) {
                if (!Float.isNaN(it.baseline)) {
                    pick = it;
                    break;
                }
            }
        }
        float y = row ? pick.crossPos : pick.mainPos;
        baseline = y + itemBaseline(pick.frag);
    }

    /** {@code direction: rtl} ではインライン軸の始点が右。左から組んだ位置を内容ボックスの中で左右に裏返す（x のみ）。 */
    void mirror(List<FlexItem> items) {
        if (!rtl) return;
        for (FlexItem it : items) {
            float w = it.frag.width;
            if (row) it.mainPos = cw - it.mainPos - w + it.mMS - it.mME;
            else it.crossPos = cw - it.crossPos - w + it.mCS - it.mCE;
        }
    }

    // ---- 共通 ----

    private ConstraintSpace itemSpace(FlexItem it, float fixedW, float fixedH, int extra) {
        float pct = BoxGeometry.usesPercentHeight(it.box.style) ? ch : Float.NaN;
        return s.space(cw, fixedW, fixedH, pct, ConstraintSpace.FORMATTING_ROOT | extra);
    }

    static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** {@code flex-basis} が確定した長さか（{@code auto} / {@code content} / 内在サイズの語 / 基準の無い % でない）。 */
    static boolean basisDefinite(Style st, long v, float base) {
        if (!CssLength.isDeclared(v) || CssLength.isKeyword(v)) return false;
        if (CssLength.isCalc(v) && CssLength.isIntrinsicSize(v, st.flexGrid.exprs)) return false;
        return !CssLength.hasPercent(v, st.flexGrid.exprs) || Float.isFinite(base);
    }

    static float gap(Style st, long v, float base, LayoutSession s) {
        if (!CssLength.isDeclared(v) || CssLength.isKeyword(v)) return 0;
        if (CssLength.hasPercent(v, st.flexGrid.exprs) && !Float.isFinite(base)) return 0;
        return LayoutUnit.snap(st.gridPx(v, Float.isFinite(base) ? base : 0, s.viewportW(), s.viewportH()));
    }

    static boolean overflowVisible(Style st) {
        Overflow ox = st.box.overflowX, oy = st.box.overflowY;
        return (ox == null || ox == Overflow.VISIBLE) && (oy == null || oy == Overflow.VISIBLE);
    }

    /**
     * 余りの配り方 {先頭のずれ, 項目の間}（{@code justify-content} / {@code align-content}）。既定の揃えは unsafe: はみ出しても
     * center は両側へ・end は始点側へはみ出す。{@code space-*} は余りが負なら先頭詰め、1 項目の around / evenly は中央。
     */
    static float[] distribute(Alignment j, float free, int n, float gapW) {
        if (j == null) return new float[]{0, gapW};
        return switch (j) {
            case CENTER -> new float[]{free / 2f, gapW};
            case END, FLEX_END, RIGHT, SELF_END -> new float[]{free, gapW};
            case SPACE_BETWEEN -> free > 0 && n > 1 ? new float[]{0, gapW + free / (n - 1)} : new float[]{0, gapW};
            case SPACE_AROUND -> free > 0 && n > 0 ? new float[]{free / n / 2f, gapW + free / n} : new float[]{0, gapW};
            case SPACE_EVENLY -> free > 0 ? new float[]{free / (n + 1), gapW + free / (n + 1)} : new float[]{0, gapW};
            default -> new float[]{0, gapW};
        };
    }

    /** 1 つだけの項目として揃えたときの主軸の位置の割合（abspos の子の静的位置。§4.1）。 */
    float staticMainFactor() {
        Alignment jc = st.flexGrid.justifyContent == null ? Alignment.NORMAL : st.flexGrid.justifyContent;
        jc = switch (jc) {
            case SPACE_BETWEEN -> Alignment.FLEX_START;
            case SPACE_AROUND, SPACE_EVENLY -> Alignment.CENTER;
            default -> jc;
        };
        float[] d = distribute(flexRelative(jc), 1, 1, 0);
        float f = reverse ? 1 - d[0] : d[0];
        return row && rtl ? 1 - f : f;
    }

    /** abspos の子を 1 つだけの項目として揃えたときの交差軸の位置の割合。 */
    float staticCrossFactor(BoxNode child) {
        Alignment a = child.style.flexGrid.alignSelf;
        if (a == null || a == Alignment.AUTO) a = st.flexGrid.alignItems;
        float f = switch (a == null ? Alignment.NORMAL : a) {
            case CENTER -> 0.5f;
            case END, SELF_END, FLEX_END -> 1;
            default -> 0;
        };
        boolean flexRel = a == Alignment.FLEX_START || a == Alignment.FLEX_END || a == Alignment.STRETCH || a == Alignment.NORMAL
                || a == Alignment.AUTO || a == null;
        if (wrapReverse && flexRel) f = 1 - f;
        return !row && rtl ? 1 - f : f;
    }

    static boolean isCollapsed(BoxNode b) {
        return b.style.inherited.visibility == com.nishiyu.enderframelib.core.css.model.Visibility.COLLAPSE;
    }

    static boolean borderBox(Style st) {
        return st.box.boxSizing == CssKeywords.BoxSizing.BORDER_BOX;
    }
}
