package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.ValueComputer.CssWideKeyword;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 算出スタイルが持つ宣言値 1 つ。文字列そのものではなく、<b>文字列ごとに 1 つだけ作る不変の値オブジェクト</b>
 * （{@link #of}）で、その文字列から決まる型付きの結果（長さ・色・数値・全域キーワード・単位の有無・{@code var(} の有無・
 * キーワードの列挙型）を最初に引いたときに 1 回だけ解析して覚える。
 *
 * <p>以前は {@code ComputedStyle} が {@code String[]} を持ち、読むたびに文字列をキーにした共有表
 * （{@code TypedValueCache} の {@code ConcurrentHashMap}）でハッシュ引きして型付きの値へ直していた。
 * 値を最初から正規の 1 個のオブジェクトにすれば、型付きの読み取りは<b>フィールドを引くだけ</b>になり、
 * 等しさは参照の比較（同じ文字列は同じオブジェクト）で済む。</p>
 *
 * <p>正規化の表は弱参照で持つので、どの算出スタイルからも使われなくなった値は回収される（利用者が動的に作る値で表が
 * 増え続けない）。生きている値は、同じ文字列なら必ず同じオブジェクトで、{@code ==} が {@code equals} と同じになる。
 * 文字列の中身（{@link #text}）は元のまま保つので、読み出す側から見える値は変わらない。</p>
 */
final class CssValue {
    final String text;

    private CssValue(String text) {
        this.text = text;
    }

    // ==================== 正規化（弱参照） ====================

    private static final class Ref extends WeakReference<CssValue> {
        final String key;

        Ref(CssValue v, String key, ReferenceQueue<CssValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    private static final ConcurrentHashMap<String, Ref> TABLE = new ConcurrentHashMap<>(4096);
    private static final ReferenceQueue<CssValue> QUEUE = new ReferenceQueue<>();

    /** 文字列の正規の値。null は null。 */
    static CssValue of(String s) {
        if (s == null) return null;
        Ref r = TABLE.get(s);
        if (r != null) {
            CssValue v = r.get();
            if (v != null) return v;
        }
        return create(s);
    }

    private static CssValue create(String s) {
        drain();
        CssValue v = new CssValue(s);
        Ref ref = new Ref(v, s, QUEUE);
        for (; ; ) {
            Ref prev = TABLE.putIfAbsent(s, ref);
            if (prev == null) return v;
            CssValue existing = prev.get();
            if (existing != null) return existing; // 競った相手が先に登録した
            if (TABLE.replace(s, prev, ref)) return v; // 回収済みの古い参照を置き換える
        }
    }

    /** 回収された値の表の項目を片付ける。 */
    private static void drain() {
        Object r;
        while ((r = QUEUE.poll()) != null) {
            Ref ref = (Ref) r;
            TABLE.remove(ref.key, ref);
        }
    }

    /** テスト用: 表に載っている項目の数（回収待ちを含む）。 */
    static int tableSizeForTest() {
        return TABLE.size();
    }

    // ==================== 文字列から決まる型付きの結果（最初の参照で 1 回だけ解析） ====================
    // どれも不変の結果なので、競って 2 回解析しても同じ値。

    private volatile LengthResolver.CompiledLength length;

    /** 長さとして解いた結果（{@code LengthResolver.compile}）。 */
    LengthResolver.CompiledLength length() {
        LengthResolver.CompiledLength l = length;
        if (l == null) {
            l = LengthResolver.compile(text);
            length = l;
        }
        return l;
    }

    /** 色の ARGB が決まっているときは bit 32 が立つ（argb は下位 32 bit）。0 は未解析。 */
    private volatile long colorBits;

    int color() {
        long c = colorBits;
        if (c == 0) {
            c = (1L << 32) | (ColorParser.parseColor(text) & 0xFFFFFFFFL);
            colorBits = c;
        }
        return (int) c;
    }

    /** {@link StyleResolver#valueDependsOnViewport} の答え（0 = 未確定、1 = 使う、2 = 使わない）。値は不変なので 1 回だけ求める。 */
    private volatile byte viewportDependence;

    boolean dependsOnViewport() {
        byte d = viewportDependence;
        if (d == 0) {
            d = StyleResolver.valueDependsOnViewport(text) ? (byte) 1 : (byte) 2;
            viewportDependence = d;
        }
        return d == 1;
    }

    private static final Object NOT_A_NUMBER = new Object();
    private volatile Object number;

    /** 単位なしの数値。解けない値は {@code defaultValue}（解けないことも覚える）。 */
    float number(float defaultValue) {
        Object n = number;
        if (n == null) {
            try {
                n = Float.parseFloat(text.trim());
            } catch (NumberFormatException e) {
                n = NOT_A_NUMBER;
            }
            number = n;
        }
        return n == NOT_A_NUMBER ? defaultValue : (Float) n;
    }

    private static final Object NOT_WIDE = new Object();
    private volatile Object wide;

    /** CSS 全域キーワード（{@code inherit} など）なら種別、でなければ null。 */
    CssWideKeyword wide() {
        Object w = wide;
        if (w == null) {
            CssWideKeyword k = CssWideKeyword.of(text);
            w = k != null ? k : NOT_WIDE;
            wide = w;
        }
        return w == NOT_WIDE ? null : (CssWideKeyword) w;
    }

    /** 0 = 未解析、1 = 偽、2 = 真（ビットを分けて競っても壊れないよう、単独のバイトに 1 つずつ持つ）。 */
    private volatile byte dimension, relative, hasVar, currentColor;

    /** 寸法のトークン（数値の直後に単位）を持ち得るか。偽なら確実に持たない（{@link ValueComputer#mayContainDimension}）。 */
    boolean mayContainDimension() {
        byte d = dimension;
        if (d == 0) {
            d = (byte) (ValueComputer.mayContainDimension(text) ? 2 : 1);
            dimension = d;
        }
        return d == 2;
    }

    /** ビューポート・コンテナ基準の単位（{@code vw} / {@code cqw} など）を使うか。 */
    boolean usesRelativeUnit() {
        byte r = relative;
        if (r == 0) {
            r = (byte) (mayContainDimension() && ComputedStyle.scanRelativeUnit(text) ? 2 : 1);
            relative = r;
        }
        return r == 2;
    }

    /** {@code currentcolor}（大小文字無視）を含むか。 */
    boolean mentionsCurrentColor() {
        byte c = currentColor;
        if (c == 0) {
            boolean found = false;
            for (int i = 0, last = text.length() - 12; i <= last && !found; i++) {
                found = text.regionMatches(true, i, "currentcolor", 0, 12);
            }
            c = (byte) (found ? 2 : 1);
            currentColor = c;
        }
        return c == 2;
    }

    /** {@code var(} を含むか。 */
    boolean containsVar() {
        byte h = hasVar;
        if (h == 0) {
            h = (byte) (text.contains("var(") ? 2 : 1);
            hasVar = h;
        }
        return h == 2;
    }

    /** 0 = 未解析。解析済みは bit 40 が立ち、bit 32〜39 が {@code LH_*}、下位 32 bit が float。 */
    private volatile long lineHeightBits;

    /** {@code line-height} の種類（{@code ComputedStyle.LH_*}）。解釈できない値は normal。 */
    byte lineHeightKind() {
        return (byte) (lineHeightBits() >>> 32);
    }

    /** {@code line-height} の数値（px はそのまま、% は割合、em と数値は倍率）。normal は 0。 */
    float lineHeightValue() {
        return Float.intBitsToFloat((int) lineHeightBits());
    }

    private long lineHeightBits() {
        long b = lineHeightBits;
        if (b == 0) {
            b = (1L << 40) | parseLineHeight(text);
            lineHeightBits = b;
        }
        return b;
    }

    private static long parseLineHeight(String raw) {
        String v = raw.trim();
        byte kind = ComputedStyle.LH_NORMAL;
        float value = 0f;
        if (!v.isEmpty() && !v.equalsIgnoreCase("normal")) {
            try {
                if (v.endsWith("px")) {
                    value = Float.parseFloat(v.substring(0, v.length() - 2).trim());
                    kind = ComputedStyle.LH_PX;
                } else if (v.endsWith("%")) {
                    value = Float.parseFloat(v.substring(0, v.length() - 1).trim()) / 100f;
                    kind = ComputedStyle.LH_PERCENT;
                } else if (v.endsWith("em")) {
                    value = Float.parseFloat(v.substring(0, v.length() - 2).trim());
                    kind = ComputedStyle.LH_EM;
                } else {
                    value = Float.parseFloat(v);
                    kind = ComputedStyle.LH_NUMBER;
                }
            } catch (NumberFormatException e) {
                kind = ComputedStyle.LH_NORMAL;
                value = 0f;
            }
        }
        return ((long) (kind & 0xFF) << 32) | (Float.floatToRawIntBits(value) & 0xFFFFFFFFL);
    }

    /** 0 = 未解析、それ以外は {@code font-weight} の数値（1〜1000）。 */
    private volatile int weight;

    /** {@code font-weight} の数値。{@code LayoutMetrics.numericWeight(String)} と同じ結果。 */
    int fontWeight() {
        int w = weight;
        if (w == 0) {
            w = ValueKeywords.numericFontWeight(text);
            weight = w;
        }
        return w;
    }

    /** {@code trim().toLowerCase()} した文字列（{@code keyword()} が返す正規化）。 */
    private volatile String lowered;

    String lowered() {
        String l = lowered;
        if (l == null) {
            l = text.trim().toLowerCase();
            lowered = l;
        }
        return l;
    }

    /** 直近に引いたキーワードの列挙型と結果。同じ文字列を別の型で読む（まれ）と入れ替わるだけで、結果は常に正しい。 */
    private record KeywordMemo(Class<?> type, Object result) {}

    private volatile KeywordMemo keyword;

    /** {@code type} の定数にした結果（知らない値は null）。 */
    @SuppressWarnings("unchecked")
    <E extends Enum<E> & CssKeywords.Keyword> E keyword(Class<E> type) {
        KeywordMemo m = keyword;
        if (m != null && m.type == type) return (E) m.result;
        E parsed = CssKeywords.parse(type, text);
        keyword = new KeywordMemo(type, parsed);
        return parsed;
    }

    @Override
    public String toString() {
        return text;
    }
}
