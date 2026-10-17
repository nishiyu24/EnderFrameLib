package com.nishiyu.enderframelib.core.css;

/**
 * カスケードのオリジン。
 * 以前は {@code specificity += 10000} というマジック定数で表現されていたものを型にしたもの。
 * 宣言順が同じなら、オリジンの強い側が勝つ。
 */
public enum CascadeOrigin {
    /** UA スタイルシート（ライブラリ組み込みの既定値）。最弱。 */
    USER_AGENT(0),
    /** 作者スタイルシート（HTML の {@code <style>} やテーマ）。 */
    AUTHOR(1),
    /** 要素の {@code style="..."} 属性。最強。 */
    INLINE(2);

    private final int rank;

    CascadeOrigin(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
}
