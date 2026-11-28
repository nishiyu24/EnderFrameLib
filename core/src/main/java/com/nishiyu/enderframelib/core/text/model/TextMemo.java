package com.nishiyu.enderframelib.core.text.model;

/**
 * 書体の実例が持つ、文字の測定の 1 層の記憶（計画書 §2.3 の表の L1 の行。M5-5）。鍵は {@code (書体 ==, 文字列の値等価,
 * 整形の指定の値等価)} で、書体の {@code ==} は記憶を持つ実例そのもの（書体ごとに 1 つ持つ）。値は不変の測定の結果。
 *
 * <p>形は 2 列の組の直接配置の表（{@code buckets × 2} 欄。大きさの上限はこの欄の数）。入れるときは組の 1 列目へ置き、元の
 * 1 列目を 2 列目へ送る（2 列目にあったものは捨てる）。2 列目で当たったものは 1 列目へ上げる（組の中の近似の LRU）。
 * 引くときは確保しない（鍵の照会のための作業用の値を作らない）。</p>
 *
 * <p>スレッドの扱い: 欄は不変の {@link Entry}（欄は全て {@code final}）だけを指すので、複数のスレッドが同時に読み書きしても、
 * 読み手が見るのは「空・古い組・新しい組」のどれかの完全な値で、鍵と値を取り違えない（final の欄の初期化の保証。値が配列なら
 * 配列の中身も含む）。競った書き込みは片方を失うだけ（次に引いたときに測り直す）。以前の {@code ThreadLocal} の表
 * （スレッドごとに 1 つ）・{@code static} の照会用の鍵と違い、プロセス共有の可変状態を持たない。</p>
 *
 * @param <S> 整形の指定（{@code equals}・{@code hashCode} が値で比べる不変の値。{@link FontStyle} など）
 * @param <V> 測定の結果（不変。配列なら呼び出し側は書き換えない）
 */
public final class TextMemo<S, V> {

    private static final class Entry<S, V> {
        final String text;
        final S spec;
        final int hash;
        final V value;

        Entry(String text, S spec, int hash, V value) {
            this.text = text;
            this.spec = spec;
            this.hash = hash;
            this.value = value;
        }

        boolean matches(String t, S s, int h) {
            return hash == h && (text == t || text.equals(t)) && (spec == s || spec.equals(s));
        }
    }

    private final Entry<S, V>[] slots;
    private final int bucketMask;

    /** {@code buckets}（2 の冪）組の表。入る数の上限は {@code buckets * 2}。 */
    @SuppressWarnings("unchecked")
    public TextMemo(int buckets) {
        if (buckets <= 0 || Integer.bitCount(buckets) != 1) throw new IllegalArgumentException("buckets は 2 の冪: " + buckets);
        this.slots = (Entry<S, V>[]) new Entry[buckets * 2];
        this.bucketMask = buckets - 1;
    }

    private static int hash(String text, Object spec) {
        int h = text.hashCode() * 31 + spec.hashCode();
        return h ^ (h >>> 16);
    }

    /** 覚えた値（無ければ null）。 */
    public V get(String text, S spec) {
        int h = hash(text, spec);
        int base = (h & bucketMask) << 1;
        Entry<S, V>[] t = slots;
        Entry<S, V> e = t[base];
        if (e != null && e.matches(text, spec, h)) return e.value;
        Entry<S, V> second = t[base + 1];
        if (second != null && second.matches(text, spec, h)) {
            // 2 列目で当たったら 1 列目へ上げる（よく引く語が、新しく入れた語に押し出されないように。競って書いても欄は不変の組だけを指す）
            t[base + 1] = e;
            t[base] = second;
            return second.value;
        }
        return null;
    }

    /** {@code value} を覚えて返す。 */
    public V put(String text, S spec, V value) {
        int h = hash(text, spec);
        int base = (h & bucketMask) << 1;
        Entry<S, V>[] t = slots;
        Entry<S, V> first = t[base];
        if (first != null) t[base + 1] = first;
        t[base] = new Entry<>(text, spec, h, value);
        return value;
    }

    /** 入る数の上限。 */
    public int capacity() {
        return slots.length;
    }

    /** いま覚えている数（検証用。走査するので熱い経路で呼ばない）。 */
    public int size() {
        int n = 0;
        for (Entry<S, V> e : slots) if (e != null) n++;
        return n;
    }
}
