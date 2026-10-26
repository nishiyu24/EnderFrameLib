package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.layout.BlockLayoutEngine.FlowState;
import com.nishiyu.enderframelib.core.layout.BlockLayoutEngine.InlineLine;
import com.nishiyu.enderframelib.core.layout.BlockLayoutEngine.LineRun;
import com.nishiyu.enderframelib.core.layout.BlockLayoutEngine.Placement;
import com.nishiyu.enderframelib.core.layout.LayoutBox.Display.Inner;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutEngineState;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;

/**
 * 段組（multicol。CSS Multicol 1）。{@code column-count} / {@code column-width} / {@code columns} を持つ
 * ブロックコンテナの中身を、列に流し込む。
 *
 * 通常のブロックフロー（{@link BlockLayoutEngine}）を列の幅で 1 回組み、その結果の「分けられる単位」
 * （行・分けられないブロック）を上から詰めて列に割り当てる。単位の位置は列ごとの移動量
 * ({@link LayoutBox#columnShifts}) として持ち、配置（arrange）のときに足す。
 * <ul>
 *   <li>列の数と幅は §3（{@code column-count} と {@code column-width} の組み合わせ、{@code column-gap} の既定は 1em）</li>
 *   <li>{@code column-fill: balance}（既定）は、列数に収まる最小の高さを二分探索で求める。高さが決まっていて
 *       {@code column-fill: auto} なら、その高さまで順に埋める</li>
 *   <li>{@code column-span: all} のブロックは全幅に置き、その前後は別々の段組として組む</li>
 *   <li>{@code column-rule}: 内容のある列の間に線を引く（{@link LayoutBox#columnRules}）</li>
 * </ul>
 *
 * 断片化は、行の間と、枠線・余白・背景を持たないブロックの子の間で起きる（そうでないブロックは 1 つの単位）。
 * LIMITATION(制約): 枠線・背景・余白を持つブロックは途中で分けない（高さが列を超えるなら溢れる）。
 * {@code break-before} / {@code break-after}、{@code orphans} / {@code widows}、{@code direction: rtl} の列の向き、
 * 段組の中の float は扱わない（float は最初の列の位置のまま）。{@code break-inside: avoid} は分けない単位にする。
 */
final class MulticolLayout {

    private MulticolLayout() {}

    /** 列の数・幅・間隔。 */
    record Geometry(int count, float width, float gap) {}

    /** 1 つの段組、または全幅の {@code column-span: all} のブロック。 */
    static final class Segment {
        /** 全幅のブロックならそれ。段組なら null。 */
        LayoutBox spanner;
        FlowState flow;
        /** 内容ボックスの上端から、この段組（またはブロックのマージンボックス）の上端まで。 */
        float top;
        float height;
        /** 内容のある列の数。 */
        int columns = 1;
    }

    /** 段組コンテナの組版結果。 */
    static final class State implements LayoutEngineState {
        final List<Segment> segments = new ArrayList<>();
        Geometry geometry = new Geometry(1, 0, 0);
        float innerW;
        float height;
        /** 移動量を入れた箱（次の組み直しの前に空にする）。 */
        final List<LayoutBox> shifted = new ArrayList<>();
    }

    /** 分けられる単位。{@code key} は持ち主の箱の {@code columnShifts} の鍵（{@link Placement} か {@link InlineLine}）。 */
    private record Atom(Object key, LayoutBox owner, float top, float bottom) {}

    // ==========================================
    // 判定と列の幾何
    // ==========================================

    static boolean isMulticol(LayoutBox box) {
        if (box.style == null || box.display == null || box.node.nodeType != NodeType.ELEMENT) return false;
        Inner inner = box.display.inner();
        if (inner != Inner.FLOW && inner != Inner.FLOW_ROOT) return false;
        return countOf(box.style) > 0 || !isAuto(box.style.get(CssProperty.COLUMN_WIDTH));
    }

    private static boolean isAuto(String v) {
        return v == null || v.isBlank() || "auto".equalsIgnoreCase(v.trim());
    }

    private static int countOf(ComputedStyle style) {
        String v = style.get(CssProperty.COLUMN_COUNT);
        if (isAuto(v)) return 0;
        try {
            return Math.max(0, Math.min(1000, Integer.parseInt(v.trim())));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static Geometry geometry(LayoutBox box, float innerW, float rootW, float rootH) {
        ComputedStyle style = box.style;
        int count = countOf(style);
        float width = isAuto(style.get(CssProperty.COLUMN_WIDTH)) ? 0
                : Math.max(0, style.length(CssProperty.COLUMN_WIDTH, 0, rootW, rootH));
        String gapRaw = style.get(CssProperty.COLUMN_GAP);
        boolean bounded = innerW < LayoutMetrics.UNBOUNDED_WIDTH && !Float.isNaN(innerW);
        float gap = isAuto(gapRaw) || "normal".equalsIgnoreCase(gapRaw.trim()) ? style.fontSizePx()
                : style.length(CssProperty.COLUMN_GAP, bounded ? innerW : 0, rootW, rootH);
        gap = Math.max(0, gap);
        if (!bounded) {
            return new Geometry(Math.max(1, count), width > 0 ? width : innerW, gap);
        }
        int n;
        if (width <= 0) {
            n = Math.max(1, count);
        } else {
            int fit = Math.max(1, (int) Math.floor((innerW + gap) / (width + gap) + 1e-4));
            n = count > 0 ? Math.min(count, fit) : fit;
        }
        return new Geometry(n, Math.max(0, (innerW - (n - 1) * gap) / n), gap);
    }

    /** {@code column-span: all} の、フロー内のブロックレベルの子か。 */
    static boolean isSpanner(LayoutBox child) {
        if (child.isAbsolute || child.display.isNone() || child.isOutsideMarker || child.isRenderedLegend
                || BlockLayoutEngine.isInlineBox(child) || BlockLayoutEngine.isFloating(child)
                || child.node.nodeType != NodeType.ELEMENT || child.style == null) return false;
        return "all".equalsIgnoreCase(child.style.getOrDefault(CssProperty.COLUMN_SPAN, "none").trim());
    }

    // ==========================================
    // 組版（contentBlockSize）
    // ==========================================

    static float contentBlockSize(BlockLayoutEngine engine, LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        float rootW = ctx.viewport().rootW(), rootH = ctx.viewport().rootH();
        float innerW = Math.max(0, box.w - box.horizontalDecoration());
        State state = new State();
        state.innerW = innerW;
        state.geometry = geometry(box, innerW, rootW, rootH);
        clearShifts(box.engineState);
        box.columnShifts = null;

        List<List<LayoutBox>> runs = new ArrayList<>();
        List<LayoutBox> spanners = new ArrayList<>();
        List<LayoutBox> current = new ArrayList<>();
        for (LayoutBox child : box.children) {
            if (isSpanner(child)) {
                runs.add(current);
                spanners.add(child);
                current = new ArrayList<>();
            } else {
                current.add(child);
            }
        }
        runs.add(current);

        float definiteH = spanners.isEmpty() ? definiteContentHeight(box, space, rootW, rootH) : Float.NaN;
        boolean fillAuto = "auto".equalsIgnoreCase(box.style.getOrDefault(CssProperty.COLUMN_FILL, "balance").trim());

        float y = 0;
        for (int i = 0; i < runs.size(); i++) {
            List<LayoutBox> run = runs.get(i);
            if (hasFlow(run)) {
                Segment seg = new Segment();
                seg.top = y;
                seg.flow = engine.buildFlow(box, run, state.geometry.width(), space, ctx);
                fragment(seg, box, state, definiteH, fillAuto);
                state.segments.add(seg);
                y += seg.height;
            }
            if (i < spanners.size()) {
                LayoutBox spanner = spanners.get(i);
                Segment seg = new Segment();
                seg.spanner = spanner;
                seg.top = y;
                float base = space.percentageBlockSize();
                spanner.measure(new ConstraintSpace(innerW, base, FormattingContext.BLOCK), ctx);
                seg.height = spanner.mT + spanner.h + spanner.mB;
                state.segments.add(seg);
                y += seg.height;
            }
        }
        state.height = y;
        box.engineState = state;
        return y;
    }

    private static boolean hasFlow(List<LayoutBox> run) {
        for (LayoutBox c : run) {
            if (!c.display.isNone() && !c.isOutsideMarker && !c.isRenderedLegend) return true;
        }
        return false;
    }

    /** {@code height} が長さで決まっていれば内容ボックスの高さ。そうでなければ NaN。 */
    private static float definiteContentHeight(LayoutBox box, ConstraintSpace space, float rootW, float rootH) {
        String h = box.style.get(CssProperty.HEIGHT);
        if (isAuto(h) || LayoutMetrics.isIntrinsicSizeKeyword(h)) return Float.NaN;
        if (h.contains("%") && space.percentageBlockSize() <= 0) return Float.NaN;
        float v = box.style.length(CssProperty.HEIGHT, space.percentageBlockSize(), rootW, rootH);
        return Math.max(0, box.style.isBorderBox() ? v - box.verticalDecoration() : v);
    }

    /** 1 つの段組を列に割り当て、各単位の移動量を持ち主の箱へ登録する。 */
    private static void fragment(Segment seg, LayoutBox box, State state, float definiteH, boolean fillAuto) {
        Geometry g = state.geometry;
        List<Atom> atoms = new ArrayList<>();
        collect(seg.flow, box, 0, atoms);
        float total = 0;
        for (Atom a : atoms) total = Math.max(total, a.bottom());
        seg.height = total;
        if (atoms.isEmpty()) return;

        float limit;
        if (!Float.isNaN(definiteH) && fillAuto) {
            limit = Math.max(definiteH, 0.01f);
        } else {
            limit = balancedHeight(atoms, total, g.count());
            if (!Float.isNaN(definiteH)) limit = Math.min(limit, Math.max(definiteH, 0.01f));
        }
        float[] cuts = new float[atoms.size()];
        int[] cols = new int[atoms.size()];
        int columns = pack(atoms, limit, cols, cuts);
        float used = 0;
        for (int i = 0; i < atoms.size(); i++) used = Math.max(used, atoms.get(i).bottom() - cuts[i]);
        seg.columns = columns;
        seg.height = used;

        for (int i = 0; i < atoms.size(); i++) {
            if (cols[i] == 0) continue;
            Atom a = atoms.get(i);
            LayoutBox owner = a.owner();
            if (owner.columnShifts == null) {
                owner.columnShifts = new IdentityHashMap<>();
                state.shifted.add(owner);
            }
            owner.columnShifts.put(a.key(), new float[]{cols[i] * (g.width() + g.gap()), -cuts[i]});
        }
        if (!state.shifted.contains(box) && box.columnShifts != null) state.shifted.add(box);
    }

    /** 列数に収まる最小の列の高さ。 */
    private static float balancedHeight(List<Atom> atoms, float total, int count) {
        if (count <= 1) return total;
        float[] scratchCut = new float[atoms.size()];
        int[] scratchCol = new int[atoms.size()];
        float lo = total / count, hi = total;
        if (pack(atoms, lo, scratchCol, scratchCut) <= count) return lo;
        for (int i = 0; i < 24 && hi - lo > 0.01f; i++) {
            float mid = (lo + hi) / 2;
            if (pack(atoms, mid, scratchCol, scratchCut) <= count) hi = mid;
            else lo = mid;
        }
        return hi;
    }

    /**
     * 単位を上から詰めて、列の高さ {@code limit} に収まらなくなったら次の列へ。列に最初に置く単位は
     * 高さが超えてもその列に置く。{@code cols[i]} は列の番号、{@code cuts[i]} はその列の上端にあたる元の y。
     * 戻り値は使った列の数。
     */
    private static int pack(List<Atom> atoms, float limit, int[] cols, float[] cuts) {
        int col = 0;
        float cut = 0;
        boolean placed = false;
        for (int i = 0; i < atoms.size(); i++) {
            Atom a = atoms.get(i);
            if (placed && a.bottom() - cut > limit + 0.01f) {
                col++;
                cut = a.top();
                placed = false;
            }
            cols[i] = col;
            cuts[i] = cut;
            placed = true;
        }
        return col + 1;
    }

    /** 流れの中の分けられる単位を文書順に集める。{@code originY} は {@code flow} の持ち主の内容ボックスの上端（段組の座標）。 */
    private static void collect(FlowState flow, LayoutBox owner, float originY, List<Atom> out) {
        for (Object item : flow.items) {
            if (item instanceof Placement p) {
                LayoutBox b = p.box();
                float top = originY + p.y() + b.mT;
                if (splittable(b)) collect((FlowState) b.engineState, b, top, out);
                else out.add(new Atom(p, owner, top, top + b.h));
            } else if (item instanceof LineRun run) {
                for (InlineLine line : run.lines()) {
                    float top = originY + line.top();
                    out.add(new Atom(line, owner, top, top + line.height()));
                }
            }
        }
    }

    /** 途中で分けてよいブロックか: 通常のブロックフローで、枠線・余白・背景・クリップを持たない。 */
    private static boolean splittable(LayoutBox b) {
        if (!(b.engineState instanceof FlowState fs) || fs.items.isEmpty()) return false;
        if (b.h <= 0 || b.isAbsolute || b.display.isNone() || BlockLayoutEngine.isFloating(b)) return false;
        if (b.display.inner() != Inner.FLOW) return false;
        if (b.bT != 0 || b.bB != 0 || b.pT != 0 || b.pB != 0) return false;
        ComputedStyle s = b.style;
        if (s.overflowX().clips() || s.overflowY().clips()) return false;
        if (visible(s.get("background-color")) || visible(s.get("background-image")) || visible(s.get("box-shadow"))
                || visible(s.get("transform")) || visible(s.get("filter"))) return false;
        String inside = s.getOrDefault("break-inside", "auto").trim().toLowerCase(Locale.ROOT);
        return !inside.startsWith("avoid");
    }

    private static boolean visible(String v) {
        if (v == null || v.isBlank()) return false;
        String t = v.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        return !t.equals("none") && !t.equals("transparent") && !t.equals("rgba(0,0,0,0)") && !t.equals("initial");
    }

    private static void clearShifts(LayoutEngineState previous) {
        if (previous instanceof State s) {
            for (LayoutBox b : s.shifted) b.columnShifts = null;
            s.shifted.clear();
        }
    }

    // ==========================================
    // 配置（arrangeChildren）
    // ==========================================

    /** 段組の中身を配置し、列の間の線の矩形を {@link LayoutBox#columnRules} に入れる。戻り値は文字の最下端。 */
    static float arrange(BlockLayoutEngine engine, LayoutBox box, State state, float innerX, float innerY,
                         LayoutContext ctx) {
        float rootW = ctx.viewport().rootW(), rootH = ctx.viewport().rootH();
        float textBottom = Float.NEGATIVE_INFINITY;
        Geometry g = state.geometry;
        List<float[]> rules = new ArrayList<>();

        float ruleW = 0;
        String ruleStyle = box.style.getOrDefault(CssProperty.COLUMN_RULE_STYLE, "none").trim().toLowerCase(Locale.ROOT);
        if (!ruleStyle.equals("none") && !ruleStyle.equals("hidden")) {
            ruleW = Math.max(0, box.style.length(CssProperty.COLUMN_RULE_WIDTH, 0, rootW, rootH));
        }

        for (Segment seg : state.segments) {
            if (seg.spanner != null) {
                LayoutBox s = seg.spanner;
                s.layout(innerX, innerY + seg.top, state.innerW, s.h, ctx);
                continue;
            }
            float t = engine.arrangeFlow(box, seg.flow, innerX, innerY + seg.top, g.width(), box.columnShifts, ctx);
            textBottom = Math.max(textBottom, t);
            if (ruleW > 0 && seg.height > 0) {
                for (int k = 1; k < seg.columns; k++) {
                    float center = innerX + k * (g.width() + g.gap()) - g.gap() / 2;
                    rules.add(new float[]{center - ruleW / 2, innerY + seg.top, ruleW, seg.height});
                }
            }
        }
        box.columnRules = rules.isEmpty() ? null : rules.toArray(new float[0][]);
        return textBottom;
    }
}
