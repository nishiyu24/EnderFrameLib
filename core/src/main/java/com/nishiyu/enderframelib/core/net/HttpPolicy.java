package com.nishiyu.enderframelib.core.net;

/**
 * {@link SafeHttpClient} が取得の可否を決めるための設定。値は呼び出しのたびに読むので、
 * 実行中に設定が変わってもよい（Minecraft 側は設定ファイルの static 値を読む実装を渡す）。
 */
public interface HttpPolicy {
    /** false なら外部取得をすべて断る。 */
    boolean allowExternal();

    /** true ならループバック・LAN・リンクローカルなどへ解決される宛先を断る。 */
    boolean blockPrivate();

    /** 通常の取得で許すホストか（小文字化・IDN 正規化済みのホスト名）。 */
    boolean isHostAllowed(String host);

    /** 外部を許し、私設網を遮断し、ホストは問わない。単体利用の既定。 */
    static HttpPolicy publicOnly() {
        return new HttpPolicy() {
            @Override public boolean allowExternal() { return true; }
            @Override public boolean blockPrivate() { return true; }
            @Override public boolean isHostAllowed(String host) { return true; }
        };
    }

    /** 外部を許し、私設網も許す（テスト・信頼できる環境用）。 */
    static HttpPolicy permissive() {
        return new HttpPolicy() {
            @Override public boolean allowExternal() { return true; }
            @Override public boolean blockPrivate() { return false; }
            @Override public boolean isHostAllowed(String host) { return true; }
        };
    }
}
