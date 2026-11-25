package com.nishiyu.enderframelib.core.layout.fragment;

/**
 * 親が子の整形に渡す制約（Engine v3 M2a）。呼び出しのたびに作り得る値オブジェクトなので、キャッシュは
 * <b>{@code ==} の高速路つきの値等価</b>（{@link #equals}）で照合する（計画書 §3.3 A-4 の案 A。M2a の実測で採用）。
 *
 * <p>子の結果に効かない欄は入れない（キーの粗粒化）。例えば % の高さの基準は、子自身が % の高さを持つときだけ載せる
 * （載せないと、親が 2 パスで測られるたびに全ての子が別の制約になり、入れ子で再測定が倍々に増える）。</p>
 */
public final class ConstraintSpace {
    /** 独立した整形コンテキストを作る（flex の項目・ルート）。子との余白の相殺をしない。 */
    public static final int FORMATTING_ROOT = 1;
    /** 自分の {@code height} を掛けずに内容の高さで測る（column flex の内容基準の基準の大きさ・自動最小サイズ）。 */
    public static final int CONTENT_BLOCK_SIZE = 2;

    /** 包含ブロックの幅（余白・パディング・幅の % の基準。auto の幅はここから余白を引いた値）。 */
    public final float availableWidth;
    /** 親が決めた枠の幅（border-box）。NaN なら箱自身が決める。 */
    public final float fixedWidth;
    /** 親が決めた枠の高さ（border-box）。NaN なら箱自身が決める。 */
    public final float fixedHeight;
    /** % の高さの基準（確定していなければ NaN）。 */
    public final float percentHeightBase;
    public final int flags;
    /**
     * 同じ BFC の外の float の除外領域（x は包含ブロックの内容ボックス、y は自分の border-box の上端が基準。自分の上端より下に掛かる
     * 矩形だけ）。独立した整形コンテキストを作らないブロックの子にだけ載り、それ以外は {@link Floats.Space#EMPTY}。値等価で鍵に入る。
     */
    final Floats.Space exclusions;

    public ConstraintSpace(float availableWidth, float fixedWidth, float fixedHeight, float percentHeightBase, int flags) {
        this(availableWidth, fixedWidth, fixedHeight, percentHeightBase, flags, Floats.Space.EMPTY);
    }

    ConstraintSpace(float availableWidth, float fixedWidth, float fixedHeight, float percentHeightBase, int flags, Floats.Space exclusions) {
        this.availableWidth = availableWidth;
        this.fixedWidth = fixedWidth;
        this.fixedHeight = fixedHeight;
        this.percentHeightBase = percentHeightBase;
        this.flags = flags;
        this.exclusions = exclusions;
    }

    /** 除外領域だけを差し替えた制約（同じなら自分）。 */
    ConstraintSpace withExclusions(Floats.Space e) {
        return e.equals(exclusions) ? this : new ConstraintSpace(availableWidth, fixedWidth, fixedHeight, percentHeightBase, flags, e);
    }

    public boolean formattingRoot() { return (flags & FORMATTING_ROOT) != 0; }

    public boolean contentBlockSize() { return (flags & CONTENT_BLOCK_SIZE) != 0; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConstraintSpace s)) return false;
        return flags == s.flags
                && Float.floatToIntBits(availableWidth) == Float.floatToIntBits(s.availableWidth)
                && Float.floatToIntBits(fixedWidth) == Float.floatToIntBits(s.fixedWidth)
                && Float.floatToIntBits(fixedHeight) == Float.floatToIntBits(s.fixedHeight)
                && Float.floatToIntBits(percentHeightBase) == Float.floatToIntBits(s.percentHeightBase)
                && (exclusions == s.exclusions || exclusions.equals(s.exclusions));
    }

    @Override
    public int hashCode() {
        int h = Float.floatToIntBits(availableWidth);
        h = h * 31 + Float.floatToIntBits(fixedWidth);
        h = h * 31 + Float.floatToIntBits(fixedHeight);
        h = h * 31 + Float.floatToIntBits(percentHeightBase);
        return (h * 31 + flags) * 31 + exclusions.hashCode();
    }

    @Override
    public String toString() {
        return "Space[aw=" + availableWidth + " fw=" + fixedWidth + " fh=" + fixedHeight + " pct=" + percentHeightBase
                + (formattingRoot() ? " root" : "") + "]";
    }
}
