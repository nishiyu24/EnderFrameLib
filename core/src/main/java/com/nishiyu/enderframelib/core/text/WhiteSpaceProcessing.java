package com.nishiyu.enderframelib.core.text;

import com.nishiyu.enderframelib.core.text.model.WhiteSpace;

/**
 * {@code white-space} による空白の処理（CSS Text 3 §4.1.1 の改行と空白の畳み込み）。文字の節の中身を作るとき
 * （{@code TextContent}）に 1 度だけ通し、行を組む側はこの結果だけを見る。純粋な関数で、記憶を持たない。
 *
 * <p>M5-5 で旧 {@code TextShaper}（整形・折り返し・区切りの記憶を static の表と {@code ThreadLocal} に持っていた）から、
 * 新経路が使う唯一の部分としてここへ移した。行の分割は {@link LineBreaker} と {@code InlineFlow}、送り幅は書体が持つ。</p>
 */
public final class WhiteSpaceProcessing {

    /** タブは Minecraft のフォントで描けないので、畳むときは空白に開く。 */
    private static final String TAB_SPACES = "    ";

    private WhiteSpaceProcessing() {}

    /** {@code white-space} に従ってテキストを整える。書き換えが要らなければ同じ文字列を返す。 */
    public static String normalize(String text, WhiteSpace whiteSpace) {
        if (text == null || text.isEmpty()) return "";
        if (isAlreadyNormal(text, whiteSpace)) return text; // 正規表現（Matcher の生成）を避ける
        String result = text.indexOf('\r') >= 0 ? text.replace("\r\n", "\n").replace('\r', '\n') : text;
        if (!whiteSpace.preservesNewlines()) result = collapseRuns(result, true);
        // 空白を保つ（pre 系）なら tab は残し、行を組むときに tab 位置まで進める。畳むなら空白と同じ
        if (result.indexOf('\t') >= 0 && whiteSpace.collapses()) result = result.replace("\t", TAB_SPACES);
        if (whiteSpace.collapses()) result = collapseRuns(result, false);
        return result;
    }

    /**
     * 改行の連続（{@code newlines}）または空白の連続を空白 1 つに置き換える。正規表現 {@code [\r\n]+} / {@code [ \t]+}
     * （タブは直前に空白へ開いてある）の置換と同じ結果を、Matcher を作らずに出す。書き換えが要らなければ同じ文字列を返す。
     */
    private static String collapseRuns(String text, boolean newlines) {
        int n = text.length();
        int first = -1;
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            // 改行は 1 つでも空白に置き換わる。空白は 2 つ続くときだけ変わる
            if (newlines ? (c == '\n' || c == '\r') : (c == ' ' && i + 1 < n && text.charAt(i + 1) == ' ')) {
                first = i;
                break;
            }
        }
        if (first < 0) return text;
        StringBuilder sb = new StringBuilder(n);
        sb.append(text, 0, first);
        boolean inRun = false;
        for (int i = first; i < n; i++) {
            char c = text.charAt(i);
            if (newlines ? (c == '\n' || c == '\r') : c == ' ') {
                if (!inRun) sb.append(' ');
                inRun = true;
            } else {
                sb.append(c);
                inRun = false;
            }
        }
        return sb.toString();
    }

    /** 改行・タブが無く、（畳むなら）空白の連続も無い: 何も書き換わらない文字列。 */
    private static boolean isAlreadyNormal(String text, WhiteSpace whiteSpace) {
        boolean collapses = whiteSpace.collapses();
        char prev = 0;
        for (int i = 0, n = text.length(); i < n; i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t') return false;
            if (collapses && c == ' ' && prev == ' ') return false;
            prev = c;
        }
        return true;
    }
}
