package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.layout.LayoutBox;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * S7 ①: L5（{@code core.paint.*} 配下の全クラス・ラムダ・内部クラス）から L4（{@link LayoutBox} およびその内部型）の
 * インスタンス欄への {@code putfield} と、可変操作メソッドの呼び出しが 0 件であることをバイトコード走査で固定する。
 */
class LayoutBoxReadOnlyFromPaintRatchetTest {

    private static final String LAYOUT_BOX_PREFIX = "com/nishiyu/enderframelib/core/layout/LayoutBox";
    private static final Set<String> MUTATING_METHODS = Set.of(
            "withPaintBounds", "clearDlSlice", "setDevicePixelRatio",
            "updateStyle", "touchPaint", "touchSubtree");

    @Test
    void paintLayerHasZeroBytecodeWritesOrMutationsOnLayoutBox() throws Exception {
        Path classesRoot = Path.of(DisplayListBuilder.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path paintDir = classesRoot.resolve("com/nishiyu/enderframelib/core/paint");
        assertTrue(Files.isDirectory(paintDir), "core.paint のコンパイル済みクラスが見つからない: " + paintDir);

        List<String> hits = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(paintDir)) {
            for (Path cls : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                scanned++;
                scanClass(Files.readAllBytes(cls), classesRoot.relativize(cls).toString().replace('\\', '/'), hits);
            }
        }
        assertTrue(scanned >= 30, "走査したクラス数が少なすぎる（検査器の故障）: " + scanned);
        assertTrue(hits.isEmpty(), "L5 (core.paint.*) から LayoutBox への書き込み・可変呼び出しは禁止:\n  " + String.join("\n  ", hits));
    }

    /** 検査器が空振りしないことを確かめる（ダミーの書き込みと可変呼び出しを両方捕まえる）。 */
    @Test
    void detectorCatchesSyntheticWriteAndMutatingCall() throws Exception {
        Path testClassesRoot = Path.of(SyntheticBadPaintWriter.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path badClass = testClassesRoot.resolve(SyntheticBadPaintWriter.class.getName().replace('.', '/') + ".class");
        List<String> hits = new ArrayList<>();
        scanClass(Files.readAllBytes(badClass), "SyntheticBadPaintWriter.class", hits);
        assertTrue(hits.stream().anyMatch(h -> h.contains("putfield") && h.contains("style")), "putfield 検出漏れ: " + hits);
        assertTrue(hits.stream().anyMatch(h -> h.contains("invoke") && h.contains("setDevicePixelRatio")), "可変呼び出し検出漏れ: " + hits);
    }

    @SuppressWarnings("unused")
    private static final class SyntheticBadPaintWriter {
        static void bad(LayoutBox b) {
            b.style = b.style;
            b.setDevicePixelRatio(2f);
        }
    }

    private static boolean isMutatingLayoutBoxMethod(String name) {
        return MUTATING_METHODS.contains(name)
                || name.startsWith("invalidate")
                || name.startsWith("reset")
                || name.startsWith("clear");
    }

    private static void scanClass(byte[] bytes, String classLabel, List<String> hits) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) return;
        in.readUnsignedShort(); // minor
        in.readUnsignedShort(); // major

        int cpCount = in.readUnsignedShort();
        int[] tags = new int[cpCount];
        String[] utf8 = new String[cpCount];
        int[] idxA = new int[cpCount];
        int[] idxB = new int[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int tag = in.readUnsignedByte();
            tags[i] = tag;
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 3, 4 -> in.readInt();
                case 5, 6 -> { in.readLong(); i++; }
                case 7, 8, 16, 19, 20 -> idxA[i] = in.readUnsignedShort();
                case 9, 10, 11, 12, 17, 18 -> { idxA[i] = in.readUnsignedShort(); idxB[i] = in.readUnsignedShort(); }
                case 15 -> { in.readUnsignedByte(); in.readUnsignedShort(); }
                default -> throw new IOException("Unsupported CP tag " + tag + " in " + classLabel);
            }
        }

        in.readUnsignedShort(); // access_flags
        in.readUnsignedShort(); // this_class
        in.readUnsignedShort(); // super_class
        int ifaces = in.readUnsignedShort();
        for (int i = 0; i < ifaces; i++) in.readUnsignedShort();

        int fields = in.readUnsignedShort();
        for (int i = 0; i < fields; i++) {
            in.readUnsignedShort();
            in.readUnsignedShort();
            in.readUnsignedShort();
            skipAttributes(in);
        }

        int methods = in.readUnsignedShort();
        for (int i = 0; i < methods; i++) {
            in.readUnsignedShort();
            String methodName = utf8[in.readUnsignedShort()];
            in.readUnsignedShort();
            int attrs = in.readUnsignedShort();
            for (int a = 0; a < attrs; a++) {
                String attrName = utf8[in.readUnsignedShort()];
                int attrLen = in.readInt();
                if ("Code".equals(attrName)) {
                    byte[] attrBytes = in.readNBytes(attrLen);
                    scanCodeAttribute(attrBytes, classLabel + "#" + methodName, utf8, idxA, idxB, hits);
                } else {
                    in.skipBytes(attrLen);
                }
            }
        }
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int attrs = in.readUnsignedShort();
        for (int i = 0; i < attrs; i++) {
            in.readUnsignedShort();
            int len = in.readInt();
            in.skipBytes(len);
        }
    }

    private static void scanCodeAttribute(byte[] attr, String loc, String[] utf8, int[] idxA, int[] idxB, List<String> hits) {
        int codeLen = u4(attr, 4);
        int base = 8;
        int end = base + codeLen;
        int pc = 0;
        while (pc < codeLen) {
            int pos = base + pc;
            int op = attr[pos] & 0xFF;
            switch (op) {
                case 0xB5 -> { // putfield
                    int cp = u2(attr, pos + 1);
                    String owner = utf8[idxA[idxA[cp]]];
                    if (owner != null && owner.startsWith(LAYOUT_BOX_PREFIX)) {
                        String field = utf8[idxA[idxB[cp]]];
                        hits.add(loc + " putfield " + owner + "." + field);
                    }
                    pc += 3;
                }
                case 0xB6, 0xB7 -> { // invokevirtual, invokespecial
                    int cp = u2(attr, pos + 1);
                    String owner = utf8[idxA[idxA[cp]]];
                    if (owner != null && owner.startsWith(LAYOUT_BOX_PREFIX)) {
                        String name = utf8[idxA[idxB[cp]]];
                        if (isMutatingLayoutBoxMethod(name)) hits.add(loc + " invoke " + owner + "." + name);
                    }
                    pc += 3;
                }
                case 0xB9 -> { // invokeinterface
                    int cp = u2(attr, pos + 1);
                    String owner = utf8[idxA[idxA[cp]]];
                    if (owner != null && owner.startsWith(LAYOUT_BOX_PREFIX)) {
                        String name = utf8[idxA[idxB[cp]]];
                        if (isMutatingLayoutBoxMethod(name)) hits.add(loc + " invokeinterface " + owner + "." + name);
                    }
                    pc += 5;
                }
                case 0xAA -> { // tableswitch
                    int pad = (4 - ((pc + 1) & 3)) & 3;
                    int p = base + pc + 1 + pad;
                    int low = s4(attr, p + 4);
                    int high = s4(attr, p + 8);
                    pc += 1 + pad + 12 + (high - low + 1) * 4;
                }
                case 0xAB -> { // lookupswitch
                    int pad = (4 - ((pc + 1) & 3)) & 3;
                    int p = base + pc + 1 + pad;
                    int npairs = s4(attr, p + 4);
                    pc += 1 + pad + 8 + npairs * 8;
                }
                case 0xC4 -> { // wide
                    int sub = attr[pos + 1] & 0xFF;
                    pc += (sub == 0x84) ? 6 : 4;
                }
                case 0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3A, 0xA9, 0xBC -> pc += 2;
                case 0x11, 0x13, 0x14, 0x84, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8,
                     0xB2, 0xB3, 0xB4, 0xB8, 0xBB, 0xBD, 0xC0, 0xC1, 0xC6, 0xC7 -> pc += 3;
                case 0xC5 -> pc += 4;
                case 0xBA, 0xC8, 0xC9 -> pc += 5;
                default -> pc += 1;
            }
            if (base + pc > end) throw new IllegalStateException("Code walk overrun in " + loc);
        }
    }

    private static int u2(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    private static int s4(byte[] b, int o) {
        return (b[o] << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static int u4(byte[] b, int o) {
        return s4(b, o);
    }
}
