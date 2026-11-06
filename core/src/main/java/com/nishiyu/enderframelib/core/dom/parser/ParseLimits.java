package com.nishiyu.enderframelib.core.dom.parser;

/**
 * {@link HtmlParser} の資源上限。
 *
 * @param maxTokens 処理するトークン数（開始タグ・終了タグ・文字列・コメントの合計）の上限
 * @param maxDepth  要素の入れ子の深さの上限
 * @param strict    true なら上限を超えた時点で {@link HtmlLimitExceededException} を投げる。
 *                  false なら超過分を切り捨て、警告ログを 1 回出す（従来の挙動）
 */
public record ParseLimits(int maxTokens, int maxDepth, boolean strict) {

    /** 既定値。信頼できない入力に対する上限としては緩め、実用の大きな文書（数万行）は通す。 */
    public static final ParseLimits DEFAULT = new ParseLimits(200_000, 256, false);

    public ParseLimits {
        if (maxTokens < 1) throw new IllegalArgumentException("maxTokens must be >= 1");
        if (maxDepth < 1) throw new IllegalArgumentException("maxDepth must be >= 1");
    }

    public ParseLimits withStrict(boolean strict) {
        return new ParseLimits(maxTokens, maxDepth, strict);
    }

    /** 上限を超えたことを知らせる（{@link #strict()} のときだけ投げる）。 */
    public static final class HtmlLimitExceededException extends RuntimeException {
        public HtmlLimitExceededException(String message) {
            super(message);
        }
    }
}
