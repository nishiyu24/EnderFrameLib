package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 箱の項目の記憶（{@link BoxMemo}）の検証（ENGINE_REARCHITECTURE_PLAN C0 ②。{@code Shortcut.VERIFY_MEMO}）。
 * 記憶の項目をそのまま出す箱を、記憶を使わずに作り直して比べる。
 *
 * <p>比べる形（正規形）は、「箱の描画入力だけで決まる値」に限る。次の 4 つは、記憶が意図的に近似する・後から書かれる
 * パラメータ化された値なので比べない（§1.2 原則 2）:</p>
 * <ul>
 *   <li>{@code cell}: 位置・寸法・色・不透明度・変形。スクロール・補間・差分パッチでその場で書き換わる。</li>
 *   <li>{@code meta.hints.layer}: 層の範囲（{@code span}）は、表示リストをまとめる段が後から付ける。</li>
 *   <li>{@code meta.clipRect} の位置: スクロールの中身は、スクロール量ぶんだけ動いた位置のクリップを持つ。大きさは比べる。</li>
 *   <li>浮動小数は 0.001 以内で同じとみなす（原点の違う経路で 1 ULP ずれる）。</li>
 * </ul>
 */
final class BoxMemoVerifier {
    private BoxMemoVerifier() {}

    private static final float TOLERANCE = 1e-3f;

    /** @param draw 箱の項目を渡した一覧へ作り直す処理（記憶を引かない版）。呼び出し元の一覧には触れない */
    static void check(List<DisplayItem> elements, BoxMemo m, LayoutBox box, java.util.function.Consumer<List<DisplayItem>> draw, AtomicInteger fragIds) {
        int savedFrag = fragIds.get();
        fragIds.set(0);
        PaintSupport.CaptureList scratch = new PaintSupport.CaptureList(m.items.size() + 4);
        if (elements instanceof PaintSupport.CaptureList cap) scratch.setOrigin(cap.originX, cap.originY); // 親相対の原点は呼び出し元と同じ
        try {
            draw.accept(scratch);
        } finally {
            fragIds.set(savedFrag);
        }
        String diff = firstDifference(m.items, scratch);
        if (diff != null) throw new AssertionError("箱の項目の記憶が古い: box=" + box.node.tag + "#" + box.node.key() + " " + diff);
    }

    /** 正規形で違う最初の所（無ければ null）。テストが直接呼ぶ。同じ場合（ほとんど）は文字列を作らない速い比較だけで済ませる。 */
    static String firstDifference(List<DisplayItem> remembered, List<DisplayItem> fresh) {
        if (remembered.size() != fresh.size()) return "個数 記憶=" + remembered.size() + " 作り直し=" + fresh.size();
        boolean same = true;
        for (int i = 0; i < fresh.size() && same; i++) same = same(remembered.get(i), fresh.get(i), "");
        if (same) return null;
        for (int i = 0; i < fresh.size(); i++) {
            StringBuilder sb = new StringBuilder();
            diff(sb, "item[" + i + "]", remembered.get(i), fresh.get(i));
            if (sb.length() > 0) return sb.toString();
        }
        return null;
    }

    /** 記録クラスごとの、比べる欄の読み口（反射の呼び出しを毎回探さない）。 */
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, java.lang.reflect.Method[]> ACCESSORS = new java.util.concurrent.ConcurrentHashMap<>();

    private static java.lang.reflect.Method[] accessorsOf(Class<?> c) {
        return ACCESSORS.computeIfAbsent(c, k -> {
            var comps = k.getRecordComponents();
            java.lang.reflect.Method[] out = new java.lang.reflect.Method[comps.length];
            for (int i = 0; i < comps.length; i++) { out[i] = comps[i].getAccessor(); out[i].setAccessible(true); }
            return out;
        });
    }

    /**
     * 文字列を作らない比較（{@link #diff} と同じ正規形）。{@code path} は直前の欄の名前で、無視する欄の判定にだけ使う。
     */
    private static boolean same(Object a, Object b, String field) {
        if (field.equals("cell") || field.equals("layer")) return true;
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (field.equals("clipRect")) {
            var x = (com.nishiyu.enderframelib.core.paint.model.ClipRect) a;
            var y = (com.nishiyu.enderframelib.core.paint.model.ClipRect) b;
            return near(x.maxX() - x.minX(), y.maxX() - y.minX()) && near(x.maxY() - x.minY(), y.maxY() - y.minY());
        }
        if (a instanceof Float x && b instanceof Float y) return near(x, y);
        if (a instanceof Double x && b instanceof Double y) return Math.abs(x - y) <= TOLERANCE;
        if (a.getClass().isArray()) {
            if (a instanceof float[] x && b instanceof float[] y) {
                if (x.length != y.length) return false;
                for (int i = 0; i < x.length; i++) if (!near(x[i], y[i])) return false;
                return true;
            }
            return java.util.Objects.deepEquals(a, b);
        }
        if (a.getClass().isRecord() && a.getClass() == b.getClass()) {
            var comps = a.getClass().getRecordComponents();
            var acc = accessorsOf(a.getClass());
            try {
                for (int i = 0; i < acc.length; i++) if (!same(acc[i].invoke(a), acc[i].invoke(b), comps[i].getName())) return false;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            return true;
        }
        return a.equals(b);
    }

    private static void diff(StringBuilder sb, String path, Object a, Object b) {
        if (sb.length() > 0) return;
        if (path.endsWith(".cell") || path.endsWith(".hints.layer")) return;
        if (a == null || b == null) { if (a != b) sb.append(path).append(" 記憶=").append(a).append(" 作り直し=").append(b); return; }
        if (path.endsWith(".clipRect")) { diffClipSize(sb, path, a, b); return; }
        if (a instanceof Float x && b instanceof Float y) { if (!near(x, y)) mismatch(sb, path, a, b); return; }
        if (a instanceof Double x && b instanceof Double y) { if (Math.abs(x - y) > TOLERANCE) mismatch(sb, path, a, b); return; }
        if (a.getClass().isArray()) {
            if (a instanceof float[] x && b instanceof float[] y) {
                boolean same = x.length == y.length;
                for (int i = 0; same && i < x.length; i++) same = near(x[i], y[i]);
                if (!same) mismatch(sb, path, java.util.Arrays.toString(x), java.util.Arrays.toString(y));
            } else if (!java.util.Objects.deepEquals(a, b)) mismatch(sb, path, "配列", "配列");
            return;
        }
        if (a.getClass().isRecord() && a.getClass() == b.getClass()) {
            for (var rc : a.getClass().getRecordComponents()) {
                try {
                    diff(sb, path + "." + rc.getName(), rc.getAccessor().invoke(a), rc.getAccessor().invoke(b));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
            return;
        }
        if (!a.equals(b)) mismatch(sb, path, a, b);
    }

    /** クリップは大きさだけ比べる（位置はスクロール量に従って動くパラメータ）。 */
    private static void diffClipSize(StringBuilder sb, String path, Object a, Object b) {
        com.nishiyu.enderframelib.core.paint.model.ClipRect x = (com.nishiyu.enderframelib.core.paint.model.ClipRect) a;
        com.nishiyu.enderframelib.core.paint.model.ClipRect y = (com.nishiyu.enderframelib.core.paint.model.ClipRect) b;
        if (!near(x.maxX() - x.minX(), y.maxX() - y.minX()) || !near(x.maxY() - x.minY(), y.maxY() - y.minY())) mismatch(sb, path, a, b);
    }

    private static boolean near(float a, float b) {
        return a == b || Math.abs(a - b) <= TOLERANCE;
    }

    private static void mismatch(StringBuilder sb, String path, Object a, Object b) {
        String x = String.valueOf(a), y = String.valueOf(b);
        sb.append(path).append(" 記憶=").append(x.length() > 120 ? x.substring(0, 120) : x)
                .append(" 作り直し=").append(y.length() > 120 ? y.substring(0, 120) : y);
    }
}
