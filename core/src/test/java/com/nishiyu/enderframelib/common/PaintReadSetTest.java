package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.PaintBoxInputs;
import com.nishiyu.enderframelib.core.layout.PaintStamps;
import com.nishiyu.enderframelib.core.paint.DisplayListBuilder;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * 描画が読む箱の欄の洗い出し（ENGINE_REARCHITECTURE_PLAN C7 ③）。箱の欄を 1 つずつ摂動し、記憶なしの新しい描画器で描き直して、
 * 項目が変わった欄を「描画が読む欄」と実測で決める。その全てが、署名の欄（{@link PaintStamps#SIGNATURE_FIELDS}）か、
 * 別の経路で見張る欄（{@link PaintStamps#COVERED_ELSEWHERE}、理由つき）に入っていることを確かめる。署名の欄を足し忘れる
 * 〔古い項目が出る〕不具合を、人の目でなく機械で見つけるための検査。署名の各欄が本当に署名を変えることも確かめる。
 */
class PaintReadSetTest {
    private static final String HTML = """
            <style>
              body { margin: 0; font-size: 14px }
              .card { padding: 6px; margin: 3px; border: 1px solid #888; background: #eef; border-radius: 6px }
              .sc { height: 40px; overflow: auto }
              .rel { position: relative; top: 2px; box-shadow: 1px 1px 3px #000 }
              .fl { float: left; width: 50px }
              table { border-collapse: collapse } td { border: 1px solid #444; padding: 2px }
              p::first-line { color: #a00 }
              .cols { column-count: 2; column-rule: 1px solid #888 }
              ul { margin: 0 }
              .g { display: grid; grid-template-columns: 1fr 1fr; gap: 4px }
            </style>
            <div class="card">one <span>two <b>three</b></span> four</div>
            <p>first line text that wraps around a little so that it has more than one line in it</p>
            <div class="card sc"><div>r1</div><div>r2</div><div>r3</div><div>r4</div><div>r5</div></div>
            <div class="card rel">rel</div>
            <div class="fl">float</div><div class="card">after float</div>
            <table><tr><td>a</td><td>b</td></tr><tr><td>c</td><td>d</td></tr></table>
            <div class="cols">column text column text column text column text column text column text</div>
            <ul><li>item one</li><li>item two</li></ul>
            <div class="g"><div>g1</div><div>g2</div></div>
            <fieldset><legend>legend</legend>inside</fieldset>
            <div style="position:absolute; left:10px; top:300px">abs</div>
            """;

    private static String sig(DisplayItem i) {
        return i.id() + "|" + i.type() + "|" + i.x() + "," + i.y() + "," + i.width() + "," + i.height() + "|" + i.text() + "|" + i.color()
                + "|" + i.bgColor() + "|" + i.radius() + "|" + i.opacity() + "|" + i.translateX() + "," + i.translateY() + "|" + i.meta();
    }

    private static String render(LayoutBox root, StandaloneView view) {
        DisplayListBuilder fresh = new DisplayListBuilder(320, 600, view.ui().document().sheet.keyframes, view.ui().scrollState());
        StringBuilder sb = new StringBuilder();
        try {
            for (DisplayItem i : fresh.render(root, false)) sb.append(sig(i)).append('\n');
        } catch (RuntimeException e) {
            sb.append("EX ").append(e);
        }
        return sb.toString();
    }

    private static void boxes(LayoutBox b, List<LayoutBox> out) {
        out.add(b);
        for (LayoutBox c : b.children) boxes(c, out);
    }

    /** 摂動した値（元と必ず違う）。摂動できない欄は null。 */
    private static Object perturbed(Object v, Class<?> t) {
        if (t == float.class) return Float.isNaN((Float) v) ? 1f : (Float) v + 3.5f;
        if (t == double.class) return (Double) v + 3.5;
        if (t == int.class) return (Integer) v + 1;
        if (t == long.class) return (Long) v + 1;
        if (t == boolean.class) return !(Boolean) v;
        if (t == String.class) return v == null ? "x" : v + "x";
        if (t == float[].class) {
            if (v == null) return null;
            float[] a = ((float[]) v).clone();
            if (a.length == 0) return null;
            a[0] += 1f;
            return a;
        }
        if (t.isEnum()) {
            for (Object o : t.getEnumConstants()) if (o != v) return o;
        }
        return null;
    }

    private static List<Field> inputFields() {
        List<Field> out = new ArrayList<>();
        for (Field f : LayoutBox.class.getDeclaredFields()) {
            int m = f.getModifiers();
            if (Modifier.isStatic(m) || Modifier.isFinal(m)) continue;
            f.setAccessible(true);
            out.add(f);
        }
        return out;
    }

    @Test
    void everyFieldThePaintReadsIsDeclared() throws Exception {
        StandaloneView view = new StandaloneView(HTML, 320, 600, FontResolver.FALLBACK);
        view.ui().flushInvalidation();
        LayoutBox root = view.ui().layoutRoot();
        List<LayoutBox> all = new ArrayList<>();
        boxes(root, all);
        String base = render(root, view);
        assertTrue(!base.startsWith("EX"), base);
        Map<String, Integer> read = new TreeMap<>();
        for (Field f : inputFields()) {
            for (LayoutBox b : all) {
                Object old = f.get(b);
                Object nv = perturbed(old, f.getType());
                if (nv == null) continue;
                f.set(b, nv);
                try {
                    if (!render(root, view).equals(base)) read.merge(f.getName(), 1, Integer::sum);
                } finally {
                    f.set(b, old);
                }
            }
        }
        Set<String> undeclared = new TreeSet<>(read.keySet());
        undeclared.removeAll(PaintStamps.SIGNATURE_FIELDS);
        undeclared.removeAll(PaintStamps.COVERED_ELSEWHERE.keySet());
        System.out.println("PAINT-READ-SET " + read);
        assertTrue(undeclared.isEmpty(), "描画が読むのに、署名にも別の経路にも宣言がない欄: " + undeclared);
    }

    @Test
    void everySignatureFieldChangesTheSignature() throws Exception {
        StandaloneView view = new StandaloneView(HTML, 320, 600, FontResolver.FALLBACK);
        view.ui().flushInvalidation();
        List<LayoutBox> all = new ArrayList<>();
        boxes(view.ui().layoutRoot(), all);
        LayoutBox target = null;
        for (LayoutBox b : all) {
            if (!b.isAnonymous && b.node != null && "div".equals(b.node.tag)) { target = b; break; }
        }
        assertTrue(target != null);
        long base = PaintStamps.signatureOf(target);
        Set<String> dead = new TreeSet<>();
        for (Field f : inputFields()) {
            if (!PaintStamps.SIGNATURE_FIELDS.contains(f.getName())) continue;
            Object old = f.get(target);
            Object nv = perturbed(old, f.getType());
            if (nv == null && f.getType() == float[].class) nv = new float[] {1f, 2f};
            if (nv == null && f.getType() == float[][].class) nv = new float[][] {{1f, 2f}};
            if (nv == null) { dead.add(f.getName() + "(摂動不能)"); continue; }
            f.set(target, nv);
            try {
                if (PaintStamps.signatureOf(target) == base) dead.add(f.getName());
            } finally {
                f.set(target, old);
            }
        }
        assertEquals(Set.of(), dead, "署名の欄に挙げたのに、署名を変えない欄");
    }

    @Test
    void paintBoxInputsMatchesFragmentPaintMemoKeys() throws Exception {
        Class<?> memoCls = Class.forName("com.nishiyu.enderframelib.core.paint.FragmentPaint$Memo");
        Set<String> declaredMemoFields = new TreeSet<>();
        for (Field f : memoCls.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) declaredMemoFields.add(f.getName());
        }
        Set<String> bookkeeping = Set.of(
                "frag", "box", "childClip", "childParentId", "pass", "owners",
                "kidCount", "childPox", "childPoy", "kidsOwners", "epoch",
                "childContextSet", "selfVpSensitive", "subtreeVpSensitive",
                "allKidsMemoized", "hasFirstLine", "kidsHaveScrolls", "kidsSkipped",
                "kidMemos", "kidIndex", "ownScrolls");
        Set<String> keyFields = PaintBoxInputs.memoKeyFields();
        Set<String> intersection = new TreeSet<>(keyFields);
        intersection.retainAll(bookkeeping);
        assertEquals(Set.of(), intersection, "memoKeyFields and bookkeeping must be disjoint");

        Set<String> missingInMemo = new TreeSet<>(keyFields);
        missingInMemo.removeAll(declaredMemoFields);
        assertEquals(Set.of(), missingInMemo, "PaintBoxInputs declares missing FragmentPaint.Memo fields");

        Set<String> union = new TreeSet<>(keyFields);
        union.addAll(bookkeeping);
        assertEquals(declaredMemoFields, union, "FragmentPaint.Memo fields must partition into PaintBoxInputs keys + bookkeeping");

        Set<String> boxFields = new TreeSet<>();
        for (Field f : LayoutBox.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) boxFields.add(f.getName());
        }
        for (PaintBoxInputs.Entry e : PaintBoxInputs.ALL) {
            if (!e.name().contains(":")) {
                assertTrue(boxFields.contains(e.name()), "PaintBoxInputs references missing LayoutBox field: " + e.name());
            }
        }
    }
}
