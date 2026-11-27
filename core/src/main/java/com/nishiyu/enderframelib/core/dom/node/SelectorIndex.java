package com.nishiyu.enderframelib.core.dom.node;

import com.nishiyu.enderframelib.core.kernel.memory.AtomTable;

import java.util.regex.Pattern;

/**
 * セレクタ照合の足切りに使う、節ごとの索引（M5-1。計画書 §3.7）。
 *
 * <ul>
 *   <li><b>class の atom</b>（{@link HtmlNode#classAtoms}）: class 属性の語を atom にしたもの。照合は整数の比較で済む。</li>
 *   <li><b>自分の Bloom</b>（{@link HtmlNode#selfBloom}）: タグ・id・class の atom の 64-bit Bloom。複合セレクタが要る
 *       ビットを含まなければ、その節には確実に一致しない。</li>
 *   <li><b>子孫の Bloom</b>（{@link HtmlNode#subtreeBloom}）: 自分と子の並びに載った子孫の自分の Bloom の和の<b>上位集合</b>
 *       （子孫が外れても落とさない。偽陽性だけを許す）。{@code :has()} の足切りに使う。</li>
 * </ul>
 *
 * <p>どれも属性の変化（{@link AttributeMap} が id / class の変化を知らせる）と子の並びへの追加のたびにその場で保つので、
 * 節の番号（{@link NodeIds}）の有無に依らず、切り離された木・仮想の節でも正しい。以前は番号を振った節だけが
 * {@code DocumentArena} のオフヒープの列に持ち、木の形の写しと二重に同期していた。</p>
 */
public final class SelectorIndex {
    private static final Pattern WHITESPACE_SPLIT = Pattern.compile("\\s+");
    private static final int[] NO_ATOMS = new int[0];

    private SelectorIndex() {}

    /** 作りたての節（属性はまだ無い）。タグだけの Bloom を持つ。 */
    static void init(HtmlNode n) {
        long self = AtomTable.bloomBits(AtomTable.shared().intern(n.tag));
        n.classAtoms = NO_ATOMS;
        n.selfBloom = self;
        n.subtreeBloom = self;
    }

    /** id / class 属性が変わった。class の atom と自分の Bloom を作り直し、子孫の Bloom の上位集合を祖先へ広げる。 */
    static void identityChanged(HtmlNode n) {
        AtomTable atoms = AtomTable.shared();
        String cls = n.attrs.get("class");
        int[] classAtoms;
        if (cls == null || cls.isBlank()) {
            classAtoms = NO_ATOMS;
        } else {
            String[] parts = WHITESPACE_SPLIT.split(cls.trim());
            classAtoms = new int[parts.length];
            for (int i = 0; i < parts.length; i++) classAtoms[i] = atoms.intern(parts[i]);
        }
        String id = n.attrs.get("id");
        long self = AtomTable.bloomBits(atoms.intern(n.tag)) | AtomTable.bloomBits(id == null ? 0 : atoms.intern(id));
        for (int c : classAtoms) self |= AtomTable.bloomBits(c);
        n.classAtoms = classAtoms;
        n.selfBloom = self;
        orUp(n, self);
    }

    /** {@code child} の部分木が {@code parent} の子の並びに加わった。子孫の Bloom を祖先へ広げる。 */
    static void attached(HtmlNode parent, HtmlNode child) {
        orUp(parent, child.subtreeBloom);
    }

    /**
     * {@code start} から子の並びを根へ向かって、子孫の Bloom に {@code bits} を足す。親の子孫の Bloom は子のものを
     * 常に含む（足すのは追加と属性の変化のときだけ）ので、既に含んでいる祖先で止めてよい。
     */
    private static void orUp(HtmlNode start, long bits) {
        for (HtmlNode cur = start; cur != null; cur = cur.listed ? cur.parent : null) {
            long before = cur.subtreeBloom;
            if ((before | bits) == before) return;
            cur.subtreeBloom = before | bits;
        }
    }

    // ------------------------------------------------------------------
    // 照合からの問い合わせ
    // ------------------------------------------------------------------

    /** 複合セレクタが要るビット {@code need} を自分の Bloom が含まない（確実に一致しない）か。 */
    public static boolean rejectsSelf(HtmlNode n, long need) {
        return need != 0L && !AtomTable.mayContain(n.selfBloom, need);
    }

    /** 子孫に {@code need} を含む節が確実に無いか（{@code :has()} の足切り）。 */
    public static boolean rejectsDescendants(HtmlNode n, long need) {
        return need != 0L && !AtomTable.mayContain(n.subtreeBloom, need);
    }

    /** class 属性の語に {@code atom} があるか。 */
    public static boolean hasClassAtom(HtmlNode n, int atom) {
        int[] atoms = n.classAtoms;
        for (int i = 0; i < atoms.length; i++) {
            if (atoms[i] == atom) return true;
        }
        return false;
    }

    /** class 属性の語に {@code className} があるか（辞書に無い名前は、どの節にも付いていない）。 */
    public static boolean hasClass(HtmlNode n, String className) {
        if (className == null) return false;
        int atom = AtomTable.shared().lookup(className.trim());
        return atom != AtomTable.NONE && hasClassAtom(n, atom);
    }

    /** 自分の Bloom（検査用）。 */
    public static long selfBloom(HtmlNode n) { return n.selfBloom; }

    /** 子孫の Bloom（検査用）。 */
    public static long subtreeBloom(HtmlNode n) { return n.subtreeBloom; }

    /** class の atom の写し（検査用）。 */
    public static int[] classAtoms(HtmlNode n) { return n.classAtoms.clone(); }
}
