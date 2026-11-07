package com.nishiyu.enderframelib.core.text.font;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 手組みの GSUB バイナリで、合字・拡張 lookup・単一/複数置換・機能の選択を確かめる。 */
class GsubTableTest {

    private static final int F = 10, I = 11, FI = 100, FFI = 101, SMALL_F = 200, A = 20;

    private static final class W {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        W u16(int... v) { for (int x : v) { o.write(x >>> 8); o.write(x); } return this; }
        W u32(int x) { u16(x >>> 16, x & 0xFFFF); return this; }
        W tag(String t) { for (char c : t.toCharArray()) o.write(c); return this; }
        int pos() { return o.size(); }
    }

    /** liga→lookup0（Type 7 → Type 4: ffi, fi）、smcp→lookup1（Type 1: f→200）、ccmp→lookup2（Type 2: a→[21,22]）。 */
    private static byte[] buildGsub() {
        W w = new W();
        w.u16(1, 0, 10, 34, 72);                         // header
        w.u16(1).tag("DFLT").u16(8);                     // ScriptList
        w.u16(4, 0);                                     // Script: defaultLangSys@4, 0 langs
        w.u16(0, 0xFFFF, 3, 0, 1, 2);                    // LangSys
        assertEquals(34, w.pos());
        w.u16(3).tag("liga").u16(20).tag("smcp").u16(26).tag("ccmp").u16(32); // FeatureList
        w.u16(0, 1, 0).u16(0, 1, 1).u16(0, 1, 2);        // Features
        assertEquals(72, w.pos());
        w.u16(3, 8, 58, 80);                             // LookupList
        // lookup0 @rel8: Extension
        w.u16(7, 0, 1, 8);
        w.u16(1, 4).u32(8);                              // ext subtable → LigatureSubst at +8
        // LigatureSubst (34 bytes)
        w.u16(1, 8, 1, 14);
        w.u16(1, 1, F);                                  // coverage {f}
        w.u16(2, 6, 14);                                 // LigatureSet: A@6, B@14
        w.u16(FFI, 3, F, I);                             // ffi
        w.u16(FI, 2, I);                                 // fi
        // lookup1 @rel58: SingleSubst format 2
        w.u16(1, 0, 1, 8);
        w.u16(2, 8, 1, SMALL_F);
        w.u16(1, 1, F);
        // lookup2 @rel80: MultipleSubst
        w.u16(2, 0, 1, 8);
        w.u16(1, 8, 1, 14);
        w.u16(1, 1, A);
        w.u16(2, 21, 22);
        return w.o.toByteArray();
    }

    @Test
    void ligaturesPreferLongestListedFirst() {
        GsubTable t = GsubTable.parse(buildGsub());
        assertFalse(t.isEmpty());
        assertArrayEquals(new int[] {FFI}, t.apply(new int[] {F, F, I}));
        assertArrayEquals(new int[] {FI}, t.apply(new int[] {F, I}));
        assertArrayEquals(new int[] {1, FI, 2, FFI, 3}, t.apply(new int[] {1, F, I, 2, F, F, I, 3}));
    }

    @Test
    void unmatchedInputIsReturnedUnchangedAsSameArray() {
        GsubTable t = GsubTable.parse(buildGsub());
        int[] in = {F, 99, F};
        assertSame(in, t.apply(in));
        int[] tail = {1, 2, F}; // 末尾の f（後続が無く合字不可）で範囲外参照しない
        assertSame(tail, t.apply(tail));
    }

    @Test
    void multipleSubstitutionExpandsGlyphs() {
        GsubTable t = GsubTable.parse(buildGsub());
        assertArrayEquals(new int[] {5, 21, 22, 6}, t.apply(new int[] {5, A, 6}));
        // 出力が入力より大きく伸びても壊れない
        int[] many = new int[100];
        java.util.Arrays.fill(many, A);
        assertEquals(200, t.apply(many).length);
    }

    @Test
    void featureSelectionControlsWhichLookupsRun() {
        byte[] gsub = buildGsub();
        GsubTable onlySmcp = GsubTable.parse(gsub, Set.of("smcp"));
        assertArrayEquals(new int[] {SMALL_F, I}, onlySmcp.apply(new int[] {F, I}));
        GsubTable none = GsubTable.parse(gsub, Set.of("zzzz"));
        assertTrue(none.isEmpty());
        assertArrayEquals(new int[] {F, I}, none.apply(new int[] {F, I}));
    }

    @Test
    void lookupsRunInIndexOrder() {
        // liga(0) の後に smcp(1)：fi は先に合字になるので smcp の f→SMALL_F は掛からない
        GsubTable both = GsubTable.parse(buildGsub(), Set.of("liga", "smcp"));
        assertArrayEquals(new int[] {FI}, both.apply(new int[] {F, I}));
        assertArrayEquals(new int[] {SMALL_F}, both.apply(new int[] {F}));
    }

    @Test
    void emptyOrCorruptTablesAreIgnored() {
        assertTrue(GsubTable.parse(null).isEmpty());
        assertTrue(GsubTable.parse(new byte[3]).isEmpty());
        byte[] bad = buildGsub();
        for (int i = 10; i < bad.length; i++) bad[i] = (byte) 0xFF;
        GsubTable t = GsubTable.parse(bad);
        int[] in = {F, I};
        assertArrayEquals(in, t.apply(in)); // 例外を出さず、そのまま返す
        assertEquals(0, GsubTable.NONE.apply(new int[0]).length);
    }

    @Test
    void truncatedTableNeverThrows() {
        byte[] full = buildGsub();
        for (int len = 0; len < full.length; len++) {
            GsubTable t = GsubTable.parse(java.util.Arrays.copyOf(full, len));
            assertNotNull(t.apply(new int[] {F, F, I, A}));
        }
    }
}
