package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.css.CssLength;
import com.nishiyu.enderframelib.core.css.Style;

/**
 * 表の列幅（Engine v3 M2b-4。CSS 2.2 §17.5.2。旧 {@code TableLayoutEngine} の列幅の計算の移植）。配列だけを読み書きする純粋な関数で、
 * セルを測るつなぎ目（内在サイズ）は呼び出し側（{@link TableFlow}）が {@link LayoutCache} から渡す。
 *
 * <ul>
 *   <li>自動レイアウト: 列ごとの最小・最大の幅（列要素の幅・1 列のセルの寄与、複数列のセルは足りない分を最大幅の比で配る）。表の幅が
 *       最大の和以上なら余りを幅の指定が無い列へ最大幅の比で配り、最小と最大の間なら各列を同じ割合だけ最大へ近づける。</li>
 *   <li>{@code width: N%} の列は表の幅の割合（最小幅を下回らない。割合の和が 100 を超えれば 100 に縮める）で、残りを他の列が分ける。</li>
 *   <li>{@code table-layout: fixed}: 列要素と 1 行目のセルの幅で決め、幅の無い列で残りを等分する。</li>
 * </ul>
 */
final class TableColumns {
    private TableColumns() { }

    /** px で確定する {@code width}（auto・%・内容のキーワードは NaN）。列要素の幅・セルの幅の指定の有無に使う。 */
    static float fixedWidth(Style st, LayoutSession s) {
        if (!CssLength.isDefinite(st.box.width, st.box.exprs)) return Float.NaN;
        return st.px(st.box.width, st.box.exprs, 0, s.viewportW(), s.viewportH());
    }

    /** {@code width: N%}（N は 0 以上）。% 以外・負・auto は -1。 */
    static float percentWidth(Style st) {
        long w = st.box.width;
        if (!CssLength.isPercent(w)) return -1f;
        float p = CssLength.percentValue(w);
        return p >= 0 && Float.isFinite(p) ? p : -1f;
    }

    /** 自動レイアウトの列ごとの最小・最大の幅（セルの寄与 {@code minW} / {@code maxW} は呼び出し側が置く）。 */
    static void contributions(TableStructure t, float hSpacing, float[] min, float[] max, boolean[] specified, LayoutSession s) {
        for (int c = 0; c < t.columns.size() && c < min.length; c++) {
            float w = fixedWidth(t.columns.get(c).style, s);
            if (Float.isNaN(w)) continue;
            min[c] = Math.max(min[c], w);
            max[c] = Math.max(max[c], w);
            specified[c] = true;
        }
        for (TableStructure.Cell c : t.cells) {
            if (c.colSpan != 1) continue;
            min[c.col] = Math.max(min[c.col], c.minW);
            max[c.col] = Math.max(max[c.col], c.maxW);
            specified[c.col] |= !Float.isNaN(fixedWidth(c.box.style, s));
        }
        // 複数列にまたがるセル: 足りない分を、またがる列の最大幅の比で配る（全部 0 なら等分）
        for (TableStructure.Cell c : t.cells) {
            if (c.colSpan == 1) continue;
            float spacing = hSpacing * (c.colSpan - 1);
            growSpan(min, c.col, c.colSpan, c.minW - spacing, max);
            growSpan(max, c.col, c.colSpan, c.maxW - spacing, max);
        }
        for (int c = 0; c < max.length; c++) {
            if (t.collapsedColumns[c]) min[c] = max[c] = 0;
            max[c] = Math.max(max[c], min[c]);
        }
    }

    private static void growSpan(float[] target, int start, int span, float needed, float[] weights) {
        int end = Math.min(target.length, start + span);
        float have = 0, weight = 0;
        for (int c = start; c < end; c++) {
            have += target[c];
            weight += weights[c];
        }
        float deficit = needed - have;
        if (deficit <= 0 || end <= start) return;
        for (int c = start; c < end; c++) target[c] += weight > 0 ? deficit * (weights[c] / weight) : deficit / (end - start);
    }

    /** 列ごとの {@code width: N%}（無い列は -1）。割合の合計が 100 を超えれば 100 に縮める。 */
    static float[] percents(TableStructure t, int n) {
        float[] percent = new float[n];
        java.util.Arrays.fill(percent, -1f);
        boolean any = false;
        for (int c = 0; c < t.columns.size() && c < n; c++) {
            float p = percentWidth(t.columns.get(c).style);
            if (p >= 0) {
                percent[c] = p;
                any = true;
            }
        }
        for (TableStructure.Cell c : t.cells) {
            if (c.colSpan != 1) continue;
            float p = percentWidth(c.box.style);
            if (p >= 0) {
                percent[c.col] = Math.max(percent[c.col], p);
                any = true;
            }
        }
        if (!any) return percent;
        float total = 0;
        for (float p : percent) if (p > 0) total += p;
        if (total > 100) for (int c = 0; c < n; c++) if (percent[c] > 0) percent[c] *= 100f / total;
        return percent;
    }

    /** 割合の列が自分の最大幅を収め、割合を持たない列が残りの割合の分に収まる、列の和の最小の幅。 */
    static float percentRequiredWidth(float[] percent, float[] max) {
        float required = 0, autoMax = 0, pctSum = 0;
        for (int c = 0; c < percent.length; c++) {
            if (percent[c] > 0) {
                required = Math.max(required, max[c] * 100f / percent[c]);
                pctSum += percent[c];
            } else if (percent[c] < 0) {
                autoMax += max[c];
            }
        }
        if (pctSum > 0 && pctSum < 100 && autoMax > 0) required = Math.max(required, autoMax * 100f / (100f - pctSum));
        return required;
    }

    /** 割合の列を表の幅の割合（最小幅を下回らない）にし、残りを割合を持たない列に {@link #distribute} で配る。 */
    static float[] distributePercent(float[] min, float[] max, boolean[] specified, float[] percent, float target) {
        int n = min.length;
        boolean any = false;
        for (float p : percent) any |= p >= 0;
        if (!any) return distribute(min, max, specified, target);
        float[] widths = new float[n];
        float used = 0;
        int autoCount = 0;
        for (int c = 0; c < n; c++) {
            if (percent[c] >= 0) {
                widths[c] = Math.max(min[c], target * percent[c] / 100f);
                used += widths[c];
            } else {
                autoCount++;
            }
        }
        if (autoCount == 0) { // 全列が割合: 合計が表の幅に満たなければ割合の比で配る
            if (used < target && used > 0) {
                float scale = target / used;
                for (int c = 0; c < n; c++) widths[c] *= scale;
            }
            return widths;
        }
        int[] idx = new int[autoCount];
        float[] aMin = new float[autoCount], aMax = new float[autoCount];
        boolean[] aSpec = new boolean[autoCount];
        int k = 0;
        for (int c = 0; c < n; c++) {
            if (percent[c] >= 0) continue;
            idx[k] = c;
            aMin[k] = min[c];
            aMax[k] = max[c];
            aSpec[k++] = specified[c];
        }
        float[] a = distribute(aMin, aMax, aSpec, Math.max(sum(aMin), target - used));
        for (int i = 0; i < autoCount; i++) widths[idx[i]] = a[i];
        return widths;
    }

    /** 表の幅を列に配る（最大の和以上なら余りを幅の指定が無い列へ、最小と最大の間なら補間、それ未満なら最小）。 */
    static float[] distribute(float[] min, float[] max, boolean[] specified, float target) {
        int n = min.length;
        float[] widths = new float[n];
        float sumMin = sum(min), sumMax = sum(max);
        if (target >= sumMax) {
            System.arraycopy(max, 0, widths, 0, n);
            float extra = target - sumMax;
            if (extra <= 0 || n == 0) return widths;
            boolean hasAuto = false;
            for (boolean sp : specified) hasAuto |= !sp;
            float weight = 0;
            int count = 0;
            for (int c = 0; c < n; c++) {
                if (hasAuto && specified[c]) continue;
                weight += max[c];
                count++;
            }
            for (int c = 0; c < n; c++) {
                if (hasAuto && specified[c]) continue;
                widths[c] += weight > 0 ? extra * (max[c] / weight) : extra / count;
            }
        } else if (target > sumMin && sumMax > sumMin) {
            float ratio = (target - sumMin) / (sumMax - sumMin);
            for (int c = 0; c < n; c++) widths[c] = min[c] + (max[c] - min[c]) * ratio;
        } else {
            System.arraycopy(min, 0, widths, 0, n);
        }
        return widths;
    }

    /**
     * {@code table-layout: fixed}: 列要素と 1 行目のセルの幅で列を決め、幅の無い列で残りを等分する（CSS 2.2 §17.5.2.1）。
     * {@code target} は列に配る幅（表の内容の幅 − 列の間隔）。セルの幅の指定は border-box へ直して数える（{@code cellOuter}）。
     */
    static float[] fixed(TableStructure t, float target, float hSpacing, java.util.function.ToDoubleFunction<TableStructure.Cell> cellOuter,
                         LayoutSession s) {
        int n = t.columnCount;
        float[] widths = new float[n];
        boolean[] known = new boolean[n];
        for (int c = 0; c < t.columns.size() && c < n; c++) {
            Style cs = t.columns.get(c).style;
            float p = percentWidth(cs);
            float w = fixedWidth(cs, s); // 列は padding・枠線を持たない
            if (!Float.isNaN(w)) {
                widths[c] = w;
                known[c] = true;
            } else if (p >= 0) {
                widths[c] = target * p / 100f;
                known[c] = true;
            }
        }
        for (TableStructure.Cell cell : t.cells) {
            if (cell.row != 0) continue;
            float p = percentWidth(cell.box.style);
            if (p >= 0 && cell.colSpan == 1) {
                if (!known[cell.col]) {
                    widths[cell.col] = target * p / 100f;
                    known[cell.col] = true;
                }
                continue;
            }
            float outer = (float) cellOuter.applyAsDouble(cell);
            if (Float.isNaN(outer)) continue;
            float spacing = hSpacing * (cell.colSpan - 1);
            for (int c = cell.col; c < cell.col + cell.colSpan && c < n; c++) {
                if (known[c]) continue;
                widths[c] = (outer - spacing) / cell.colSpan;
                known[c] = true;
            }
        }
        float used = 0;
        int unknown = 0;
        for (int c = 0; c < n; c++) {
            if (known[c]) used += widths[c];
            else unknown++;
        }
        float rest = Math.max(0, target - used);
        if (unknown > 0) {
            for (int c = 0; c < n; c++) if (!known[c]) widths[c] = rest / unknown;
        } else if (n > 0 && rest > 0) {
            for (int c = 0; c < n; c++) widths[c] += rest / n;
        }
        return widths;
    }

    static float sum(float[] values) {
        float s = 0;
        for (float v : values) s += v;
        return s;
    }
}
