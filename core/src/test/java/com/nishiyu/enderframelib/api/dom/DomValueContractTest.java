package com.nishiyu.enderframelib.api.dom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code api.dom} の値の型（DOMRect・DOMException・FormData・MediaQueryList）の契約（ENGINE_REARCHITECTURE_PLAN T4）。 */
class DomValueContractTest {

    @Test
    void domRectEdgesEmptinessAndIntersection() {
        DOMRect r = new DOMRect(10, 20, 30, 40);
        assertEquals(10, r.left());
        assertEquals(20, r.top());
        assertEquals(40, r.right());
        assertEquals(60, r.bottom());
        assertFalse(r.isEmpty());
        assertTrue(DOMRect.EMPTY.isEmpty());
        assertTrue(new DOMRect(0, 0, 5, 0).isEmpty(), "高さ 0 は空");

        assertEquals(new DOMRect(25, 30, 15, 30), r.intersect(new DOMRect(25, 30, 100, 100)));
        assertEquals(r.intersect(new DOMRect(25, 30, 100, 100)), new DOMRect(25, 30, 100, 100).intersect(r), "交わりは順序に依らない");
        DOMRect apart = r.intersect(new DOMRect(100, 100, 5, 5));
        assertTrue(apart.isEmpty(), "重ならなければ幅・高さ 0");
        assertEquals(0, apart.width());
        assertEquals(0, apart.height());
        assertTrue(r.intersect(new DOMRect(40, 20, 10, 10)).isEmpty(), "辺で接するだけなら空");
    }

    @Test
    void domExceptionDerivesTheLegacyCodeFromTheName() {
        DOMException e = new DOMException("bad", "NotFoundError");
        assertEquals("NotFoundError", e.getName());
        assertEquals(DOMException.NOT_FOUND_ERR, e.getCode());
        assertEquals("bad", e.getMessage());
        assertEquals(8, DOMException.NOT_FOUND_ERR, "WHATWG DOM の表の値");
        assertEquals(12, new DOMException("", "SyntaxError").getCode());
        assertEquals(20, new DOMException("", "AbortError").getCode());
        assertEquals(0, new DOMException("", "QuotaExceededLikeButUnknown").getCode(), "表に無い名前は 0");
        assertEquals(0, new DOMException("", null).getCode());
        DOMException plain = new DOMException("x");
        assertEquals("Error", plain.getName());
        assertEquals(0, plain.getCode());
        assertEquals(DOMException.INVALID_STATE_ERR, new DOMException("m", "Custom", DOMException.INVALID_STATE_ERR).getCode(), "明示した code が優先");
        assertTrue(e instanceof RuntimeException, "検査例外ではない");
    }

    @Test
    void formDataKeepsInsertionOrderAndSetReplacesInPlace() {
        FormData f = new FormData();
        assertTrue(f.isEmpty());
        f.append("a", "1");
        f.append("b", "2");
        f.append("a", "3");
        assertEquals("1", f.get("a"), "get は最初の値");
        assertEquals(List.of("1", "3"), f.getAll("a"));
        assertTrue(f.has("b"));
        assertNull(f.get("zz"));
        assertEquals(List.of(), f.getAll("zz"));

        f.set("a", "9"); // 最初の a の位置で置き換え、残りの a は消える
        assertEquals(List.of("a", "b"), f.getKeys());
        assertEquals(List.of("9", "2"), f.getValues());
        f.set("c", "new"); // 無ければ末尾に足す
        assertEquals(List.of("a", "b", "c"), f.getKeys());

        f.delete("b");
        assertFalse(f.has("b"));
        assertEquals(2, f.size());

        List<String> iterated = new ArrayList<>();
        for (FormData.Entry e : f) iterated.add(e.name() + "=" + e.value());
        assertEquals(List.of("a=9", "c=new"), iterated);
        assertThrows(UnsupportedOperationException.class, () -> f.getEntries().clear(), "取り出した一覧から中身を変えられない");
        assertThrows(UnsupportedOperationException.class, () -> f.getAll("a").add("x"));

        f.append(null, null);
        assertEquals("", f.get(""), "null の名前・値は空文字");
        f.append("file", "data", "a.txt");
        assertEquals("a.txt", f.getEntries().get(f.size() - 1).filename());
    }

    @Test
    void mediaQueryListIsAValue() {
        assertEquals(new MediaQueryList("(min-width: 10px)", true), new MediaQueryList("(min-width: 10px)", true));
        assertFalse(new MediaQueryList("print", false).matches());
    }
}
