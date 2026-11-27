package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link SceneLayer} 自身の描画の中身（行に置かれた断片と描画命令）を詰めた不変の列（M3b-4c-1b。計画書 §1.5 決定 O・§3.4）。
 *
 * <p>先頭に行に置かれた断片（文字の語・インライン要素の行ごとの箱。以前の {@code SceneRun[]}）を 1 つ {@value #RUN_WORDS} 語で並べ、
 * 続けて描画命令を並べる。断片は層を作るときに（{@link #ofRuns}）、命令は {@code SceneEmitter} が層の命令を作るときに足す
 * （断片を写し、断片と同じ位置・中身の文字の命令は断片の番号だけを持つ）。</p>
 *
 * <p>命令 1 つを 1 オブジェクトにせず、層ごとに 1 つの {@code int[]}（命令ごとに頭の語・欄の有無の語・座標 4 語・有る欄だけの語。
 * float は生のビット）と、参照の表 {@code Object[]}（文字の中身・属性の表・メタ・アニメーションなど。同じ参照〔文字列は同じ値〕は
 * 1 つの欄を共有する）に詰める。読む側は使い回す {@link Cursor} で 1 つずつ読み、読むたびにオブジェクトを作らない（ID を組み立てる
 * {@link Cursor#id()} だけが文字列を作る）。</p>
 *
 * <p>座標は層自身か、{@link #BASE_ORIGIN_PARENT} なら層の原点の節からの float（丸める前）。命令はフレームの文脈（祖先のクリップ・
 * 親の錨の ID・スクロール量）を持たない。文字列 ID は持たず、持ち主の節の ID と部品の綴り（接尾辞と通し番号）から組み立てる。
 * 文字の命令のメタは箱ごとに 1 つを共有し、行ごとに違うベースライン・行の高さだけを命令の語に持つ（{@link Cursor#hasLine()}）。</p>
 */
public final class DrawOps {
    public static final DrawOps EMPTY = new DrawOps(new int[0], new Object[0], 0, 0);

    /** 原点: 絶対座標（ビューポートの左上）。 */
    public static final byte ORIGIN_ABSOLUTE = 0;
    /** 原点: 祖先の節（変形・不透明度を引き継ぐ錨）の整数の原点。 */
    public static final byte ORIGIN_NODE = 1;
    /** 原点: 層自身の整数の原点。 */
    public static final byte ORIGIN_SELF = 2;
    /** {@link Cursor#flags()} の原点の種類の部分。 */
    public static final int ORIGIN_MASK = 0x3;
    /** 座標の基準が層の原点の節（文字・インラインの断片。スクロールを含まない）。無ければ層自身。 */
    public static final int BASE_ORIGIN_PARENT = 0x4;
    /** 箱の錨（組み立てで親の錨の ID を入れる）。 */
    public static final int ANCHOR = 0x8;
    /** 文字色のアニメーションを祖先から継ぐ（組み立てで祖先から解く）。 */
    public static final int INHERIT_TEXT_COLOR_ANIM = 0x10;

    // ---- 欄の有無の語のビット（語の並びもこの順。無い欄は既定値） ----
    static final int HAS_KEY = 1;              // 2 語（上位・下位）
    static final int HAS_TEXT = 1 << 1;        // 参照。無ければ ""
    static final int HAS_COLOR = 1 << 2;
    static final int HAS_BG = 1 << 3;
    static final int HAS_EVENTS = 1 << 4;      // 参照。無ければ空の表
    static final int HAS_RADIUS = 1 << 5;
    static final int HAS_OPACITY = 1 << 6;     // 無ければ 1
    static final int HAS_TX = 1 << 7;
    static final int HAS_TY = 1 << 8;
    static final int HAS_SX = 1 << 9;          // 無ければ 1
    static final int HAS_SY = 1 << 10;         // 無ければ 1
    static final int HAS_ROT = 1 << 11;
    static final int HAS_TRANS = 1 << 12;
    static final int HAS_ANIM = 1 << 13;       // 参照。無ければ AnimationSpec.NONE
    static final int HAS_META = 1 << 14;       // 参照。無ければ null
    static final int HAS_LINE = 1 << 15;       // 2 語（ベースライン・行の高さ）。メタの値を上書きする
    static final int HAS_BASE = 1 << 16;       // 参照（持ち主の節の ID）
    static final int ID_SAME = 1 << 17;        // ID は持ち主の節の ID
    static final int ID_SUFFIX = 1 << 18;      // 参照。ID は 持ち主 + 接尾辞
    static final int ID_NUMBER = 1 << 19;      // 1 語。ID は 持ち主 + 接尾辞 + 通し番号
    static final int ID_FULL = 1 << 20;        // 参照。ID そのもの（持ち主の ID で始まらないとき）
    static final int HAS_RUN = 1 << 21;        // 座標 4 語・中身・行の値の代わりに断片の番号 1 語（断片と同じ文字の命令）

    /** 断片 1 つの語の数: x・y・w・h・ベースライン（float のビット）・印と {@code ::first-line} の参照・文字の参照。 */
    static final int RUN_WORDS = 7;
    static final int RUN_START = 1, RUN_END = 2;

    static final int ONE = Float.floatToRawIntBits(1f);
    private static final ElementType[] TYPES = ElementType.values();
    private static final ImageFitMode[] FITS = ImageFitMode.values();

    final int[] data;
    final Object[] refs;
    final int count;
    final int runs;

    DrawOps(int[] data, Object[] refs, int count, int runs) {
        this.data = data;
        this.refs = refs;
        this.count = count;
        this.runs = runs;
    }

    /** 断片だけの列（層を作るとき。命令は後で {@link DrawOpsBuilder#beginRuns} から足す）。 */
    public static DrawOps ofRuns(SceneRun[] runs) {
        if (runs == null || runs.length == 0) return EMPTY;
        int n = runs.length;
        int[] d = new int[n * RUN_WORDS];
        List<Object> refs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            SceneRun r = runs[i];
            int fl = -1, tx = -1;
            if (r.firstLineStyle() != null) { fl = refs.size(); refs.add(r.firstLineStyle()); }
            if (r.text() != null) { tx = refs.size(); refs.add(r.text()); }
            writeRun(d, i * RUN_WORDS, r.x(), r.y(), r.w(), r.h(), r.baseline(), r.startEdge(), r.endEdge(), fl, tx);
        }
        return new DrawOps(d, refs.toArray(), 0, n);
    }

    static void writeRun(int[] d, int p, float x, float y, float w, float h, float baseline, boolean start, boolean end,
                                 int firstLineRef, int textRef) {
        d[p] = Float.floatToRawIntBits(x);
        d[p + 1] = Float.floatToRawIntBits(y);
        d[p + 2] = Float.floatToRawIntBits(w);
        d[p + 3] = Float.floatToRawIntBits(h);
        d[p + 4] = Float.floatToRawIntBits(baseline);
        d[p + 5] = (start ? RUN_START : 0) | (end ? RUN_END : 0) | ((firstLineRef + 1) << 2);
        d[p + 6] = textRef;
    }

    /** 命令の数（断片は数えない）。 */
    public int count() {
        return count;
    }

    /** 命令が無いか（断片はあってもよい）。 */
    public boolean isEmpty() {
        return count == 0;
    }

    // ---- 行に置かれた断片（座標は持ち主の原点の箱の左上から） ----

    /** 断片の数。 */
    public int runCount() {
        return runs;
    }

    public float runX(int i) { return Float.intBitsToFloat(data[i * RUN_WORDS]); }
    public float runY(int i) { return Float.intBitsToFloat(data[i * RUN_WORDS + 1]); }
    public float runW(int i) { return Float.intBitsToFloat(data[i * RUN_WORDS + 2]); }
    public float runH(int i) { return Float.intBitsToFloat(data[i * RUN_WORDS + 3]); }
    public float runBaseline(int i) { return Float.intBitsToFloat(data[i * RUN_WORDS + 4]); }
    /** 断片が分割したインラインの箱の始まりの辺を持つか。 */
    public boolean runStartEdge(int i) { return (data[i * RUN_WORDS + 5] & RUN_START) != 0; }
    public boolean runEndEdge(int i) { return (data[i * RUN_WORDS + 5] & RUN_END) != 0; }

    /** 文字の語の断片か（インライン要素の行ごとの箱は偽）。 */
    public boolean runIsText(int i) { return data[i * RUN_WORDS + 6] >= 0; }

    /** 文字の語（インライン要素の箱は null）。 */
    public String runText(int i) {
        int t = data[i * RUN_WORDS + 6];
        return t >= 0 ? (String) refs[t] : null;
    }

    /** 断片に掛かる {@code ::first-line} のスタイル（無ければ null）。 */
    public ComputedStyle runFirstLine(int i) {
        int f = (data[i * RUN_WORDS + 5] >>> 2) - 1;
        return f >= 0 ? (ComputedStyle) refs[f] : null;
    }

    /** 断片を値にしたもの（問い合わせの結果・テスト用。呼ぶたびに作る）。 */
    public SceneRun run(int i) {
        return new SceneRun(runX(i), runY(i), runW(i), runH(i), runText(i), runBaseline(i), runStartEdge(i), runEndEdge(i),
                runFirstLine(i));
    }

    /** 全ての断片を値にした列（テスト用。呼ぶたびに作る）。 */
    public List<SceneRun> runList() {
        List<SceneRun> out = new ArrayList<>(runs);
        for (int i = 0; i < runs; i++) out.add(run(i));
        return out;
    }

    /** 詰めた語の数（調べ用）。 */
    public int words() {
        return data.length;
    }

    /** 参照の表の大きさ（調べ用）。 */
    public int refCount() {
        return refs.length;
    }

    /** 参照の表の {@code i} 番目（調べ用）。 */
    public Object ref(int i) {
        return refs[i];
    }

    /**
     * 同じ命令の列か（語の列が同じで、参照の表が値で等しい）。参照の共有の形が違う等しい列は違うと答えることがある
     * （描き直す範囲が広がるだけで、狭まらない）。
     */
    public boolean sameAs(DrawOps o) {
        if (o == this) return true;
        if (o == null || o.count != count || o.runs != runs || !Arrays.equals(data, o.data) || refs.length != o.refs.length) {
            return false;
        }
        for (int i = 0; i < refs.length; i++) {
            if (!Objects.equals(refs[i], o.refs[i])) return false;
        }
        return true;
    }

    /** 命令を 1 つずつ読む使い回しの読み手。{@link #reset} してから {@link #next} が真の間、今の命令の欄を返す。 */
    public static final class Cursor {
        private int[] d;
        private Object[] r;
        private int left, pos;
        private int head, mask;
        private float x, y, w, h;
        private long key;
        private String text;
        private int color, bgColor, radius, trans;
        private Map<String, String> events;
        private float opacity, tx, ty, sx, sy, rot, baseline, lineHeight;
        private AnimationSpec anim;
        private RenderMeta meta;
        private String base, suffix;
        private int number;

        public Cursor reset(DrawOps ops) {
            return reset(ops.data, ops.refs, ops.count, ops.runs);
        }

        /** 組み立て中の列（{@link DrawOpsBuilder}）を読む。 */
        public Cursor reset(DrawOpsBuilder b) {
            return reset(b.data, b.refs, b.count, b.runs);
        }

        private Cursor reset(int[] d, Object[] r, int count, int runs) {
            this.d = d;
            this.r = r;
            this.left = count;
            this.pos = runs * RUN_WORDS;
            return this;
        }

        /** 次の命令へ進む（無ければ偽。読み終えたら持っている参照を放す）。 */
        @SuppressWarnings("unchecked")
        public boolean next() {
            if (left <= 0) {
                d = null;
                r = null;
                text = null;
                events = null;
                anim = null;
                meta = null;
                base = null;
                suffix = null;
                return false;
            }
            left--;
            int[] a = d;
            int p = pos;
            head = a[p++];
            int m = a[p++];
            mask = m;
            int run = -1;
            if ((m & HAS_RUN) != 0) {
                run = a[p++] * RUN_WORDS;
                x = Float.intBitsToFloat(a[run]);
                y = Float.intBitsToFloat(a[run + 1]);
                w = Float.intBitsToFloat(a[run + 2]);
                h = Float.intBitsToFloat(a[run + 3]);
            } else {
                x = Float.intBitsToFloat(a[p++]);
                y = Float.intBitsToFloat(a[p++]);
                w = Float.intBitsToFloat(a[p++]);
                h = Float.intBitsToFloat(a[p++]);
            }
            if ((m & HAS_KEY) != 0) { key = ((long) a[p] << 32) | (a[p + 1] & 0xFFFFFFFFL); p += 2; } else key = 0L;
            text = (m & HAS_TEXT) != 0 ? (String) r[a[p++]] : "";
            color = (m & HAS_COLOR) != 0 ? a[p++] : 0;
            bgColor = (m & HAS_BG) != 0 ? a[p++] : 0;
            events = (m & HAS_EVENTS) != 0 ? (Map<String, String>) r[a[p++]] : Map.of();
            radius = (m & HAS_RADIUS) != 0 ? a[p++] : 0;
            opacity = (m & HAS_OPACITY) != 0 ? Float.intBitsToFloat(a[p++]) : 1f;
            tx = (m & HAS_TX) != 0 ? Float.intBitsToFloat(a[p++]) : 0f;
            ty = (m & HAS_TY) != 0 ? Float.intBitsToFloat(a[p++]) : 0f;
            sx = (m & HAS_SX) != 0 ? Float.intBitsToFloat(a[p++]) : 1f;
            sy = (m & HAS_SY) != 0 ? Float.intBitsToFloat(a[p++]) : 1f;
            rot = (m & HAS_ROT) != 0 ? Float.intBitsToFloat(a[p++]) : 0f;
            trans = (m & HAS_TRANS) != 0 ? a[p++] : 0;
            anim = (m & HAS_ANIM) != 0 ? (AnimationSpec) r[a[p++]] : AnimationSpec.NONE;
            meta = (m & HAS_META) != 0 ? (RenderMeta) r[a[p++]] : null;
            if ((m & HAS_LINE) != 0) {
                baseline = Float.intBitsToFloat(a[p++]);
                lineHeight = Float.intBitsToFloat(a[p++]);
            }
            if (run >= 0) {
                text = (String) r[a[run + 6]];
                baseline = Float.intBitsToFloat(a[run + 4]);
                lineHeight = h;
            }
            base = (m & HAS_BASE) != 0 ? (String) r[a[p++]] : null;
            suffix = (m & (ID_SUFFIX | ID_FULL)) != 0 ? (String) r[a[p++]] : null;
            number = (m & ID_NUMBER) != 0 ? a[p++] : 0;
            pos = p;
            return true;
        }

        public ElementType type() { return TYPES[head & 0xFF]; }
        /** 原点の種類と座標の基準・錨・祖先の文字色の印（{@link #ORIGIN_MASK} ほか）。 */
        public int flags() { return (head >>> 8) & 0xFF; }
        public byte origin() { return (byte) ((head >>> 8) & ORIGIN_MASK); }
        public ImageFitMode fitMode() { return FITS[(head >>> 16) & 0xF]; }
        public float x() { return x; }
        public float y() { return y; }
        public float w() { return w; }
        public float h() { return h; }
        public long key() { return key; }
        public String text() { return text; }
        public int color() { return color; }
        public int bgColor() { return bgColor; }
        public Map<String, String> events() { return events; }
        public int radius() { return radius; }
        public float opacity() { return opacity; }
        public float tx() { return tx; }
        public float ty() { return ty; }
        public float sx() { return sx; }
        public float sy() { return sy; }
        public float rot() { return rot; }
        public int transitionDuration() { return trans; }
        public AnimationSpec animDef() { return anim; }
        /** 命令のメタ（文字の命令は箱で共有するもので、行の値は {@link #baseline()}・{@link #lineHeight()}）。 */
        public RenderMeta meta() { return meta; }
        /** メタのベースライン・行の高さを命令ごとの値で上書きするか。 */
        public boolean hasLine() { return (mask & (HAS_LINE | HAS_RUN)) != 0; }
        public float baseline() { return baseline; }
        public float lineHeight() { return lineHeight; }
        public String baseNodeId() { return base; }

        /** 描画 ID（持ち主の節の ID と部品の綴りから組み立てる。接尾辞のある ID は呼ぶたびに文字列を作る）。 */
        public String id() {
            int m = mask;
            if ((m & ID_SAME) != 0) return base;
            if ((m & ID_FULL) != 0) return suffix;
            if ((m & ID_SUFFIX) == 0) return null;
            return (m & ID_NUMBER) != 0 ? base + suffix + number : base + suffix;
        }

        /** 命令のメタに行の値を入れたもの（行の値を持たない命令はメタそのもの）。 */
        public RenderMeta lineMeta(RenderMeta m) {
            if (m == null || !hasLine()) return m;
            return new RenderMeta(m.clipRect(), m.borderRadii(), m.circleBorder(), m.fontHeight(), baseline, lineHeight,
                    m.originX(), m.originY(), m.itemCount(), m.interactiveState(), m.roundedBorder(), m.hints());
        }
    }
}
