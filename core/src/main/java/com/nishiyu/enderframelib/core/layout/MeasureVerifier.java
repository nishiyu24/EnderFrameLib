package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 測定の記憶の検証（ENGINE_REARCHITECTURE_PLAN C0 ②。{@code Shortcut.VERIFY_MEMO}）。
 *
 * <p>記憶が当たって測定を飛ばした箱を、記憶を使わずに測り直して自然出力を比べる。測り直しは部分木の箱の出力と測定の記憶を
 * 書き換えるので、前に退避して後で戻す（検証の有無で以後の動きは変わらない）。記憶の欄は反射で集めるので、足した欄も自動で対象になる。
 * テストでだけ立てる（{@code costGate}・計測では無効）。</p>
 */
final class MeasureVerifier {
    private MeasureVerifier() {}

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    private static final Field[] MEMO_FIELDS = memoFields();

    /** 検証の測り直しの最中か（この間は入れ子の検証と、記録に伴う祖先の無効化の伝播をしない）。 */
    static boolean active() { return DEPTH.get()[0] > 0; }

    static void verify(LayoutBox box, ConstraintSpace space, LayoutContext ctx, String how) {
        MeasureOutputs expected = MeasureOutputs.capture(box);
        List<Saved> saved = new ArrayList<>();
        save(box, saved);
        LayoutContext fresh = LayoutReads.freshContext(ctx, false);
        MeasureOutputs actual;
        DEPTH.get()[0]++;
        int vSlot = MeasureReads.pushMeasuring(null);
        try {
            BoxMeasurer.measure(box, space, fresh);
            actual = MeasureOutputs.capture(box);
        } finally {
            MeasureReads.popMeasuring(vSlot);
            DEPTH.get()[0]--;
            for (Saved s : saved) s.restore();
        }
        // sizingMode は親が決める入力（子の記憶の鍵ではない）なので比べない
        if (!expected.matchesNatural(actual) || !expected.matchesBox(actual)) {
            throw new AssertionError("測定の記憶（" + how + "）が古い: 箱 " + box.node.key() + " 記憶=" + expected + " 測り直し=" + actual);
        }
    }

    private static void save(LayoutBox b, List<Saved> out) {
        out.add(new Saved(b));
        for (int i = 0, n = b.children.size(); i < n; i++) save(b.children.get(i), out);
    }

    private static Field[] memoFields() {
        List<Field> out = new ArrayList<>();
        for (Field f : MeasureMemo.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            out.add(f);
        }
        return out.toArray(new Field[0]);
    }

    /** 測り直しの前の、箱の出力・描画の印・測定の記憶。 */
    private static final class Saved {
        private final LayoutBox box;
        private final MeasureOutputs outputs;
        private final long paintStamp;
        private final Object[] memoValues = new Object[MEMO_FIELDS.length];
        private final Object slots;

        Saved(LayoutBox box) {
            this.box = box;
            this.outputs = MeasureOutputs.capture(box);
            this.paintStamp = box.paintStamp;
            try {
                for (int i = 0; i < MEMO_FIELDS.length; i++) memoValues[i] = MEMO_FIELDS[i].get(box.measureMemo);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            this.slots = box.measureMemo.snapshotSlots();
        }

        void restore() {
            outputs.apply(box);
            box.paintStamp = paintStamp;
            try {
                for (int i = 0; i < MEMO_FIELDS.length; i++) MEMO_FIELDS[i].set(box.measureMemo, memoValues[i]);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            box.measureMemo.restoreSlots(slots);
            box.measureMemo.invalidateMinContentCache();
        }
    }
}
