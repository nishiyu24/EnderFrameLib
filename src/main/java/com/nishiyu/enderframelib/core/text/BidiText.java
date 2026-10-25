package com.nishiyu.enderframelib.core.text;

import java.text.Bidi;

/**
 * 双方向テキスト（Unicode Standard Annex #9）の並べ替え。論理順（入力順）の文字列を、左から右へ描く順（視覚順）にする。
 *
 * 右から左へ書く文字（ヘブライ語・アラビア語など）の連なりは逆順に並び、数字や左から右の語はそのままの向きで
 * その間に入る。対になる括弧は向きを反転する（UAX #9 の L4）。
 *
 * LIMITATION: 並べ替えるだけで、アラビア文字の字形の選択（語頭・語中・語末の連結形）は行わない
 * （書体の GSUB による整形が要る）。ヘブライ語のように連結しない文字は正しく並ぶ。
 * 1 つの文字列（1 つの断片）の中の並べ替えで、複数のインライン要素にまたがる行の並べ替えは持たない。
 */
public final class BidiText {

    private BidiText() {}

    /** 右から左の文字（R / AL / AN の強い向き）を含むか。含まなければ並べ替えは要らない。 */
    public static boolean hasRtl(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x0590) continue; // ラテン文字などは速く除く
            if (Bidi.requiresBidi(text.toCharArray(), 0, text.length())) return true;
            return false;
        }
        return false;
    }

    /**
     * 論理順の文字列を視覚順にする。
     *
     * @param baseRtl 段落の向き（{@code direction: rtl}）。{@code null} は最初の強い文字で決める（HTML の {@code dir=auto} 相当）
     */
    public static String toVisual(String text, Boolean baseRtl) {
        if (!hasRtl(text)) return text;
        int flags = baseRtl == null ? Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT
                : baseRtl ? Bidi.DIRECTION_RIGHT_TO_LEFT : Bidi.DIRECTION_LEFT_TO_RIGHT;
        Bidi bidi = new Bidi(text, flags);
        if (bidi.isLeftToRight()) return text;

        int runs = bidi.getRunCount();
        byte[] levels = new byte[runs];
        Integer[] order = new Integer[runs];
        for (int r = 0; r < runs; r++) {
            levels[r] = (byte) bidi.getRunLevel(r);
            order[r] = r;
        }
        Bidi.reorderVisually(levels, 0, order, 0, runs);

        StringBuilder out = new StringBuilder(text.length());
        for (int visual = 0; visual < runs; visual++) {
            int r = order[visual];
            int start = bidi.getRunStart(r);
            int limit = bidi.getRunLimit(r);
            if ((bidi.getRunLevel(r) & 1) == 0) {
                out.append(text, start, limit);
            } else {
                // 右から左の連なりはコードポイント単位で逆順にし、対の括弧は向きを反転する
                for (int i = limit; i > start; ) {
                    int cp = text.codePointBefore(i);
                    out.appendCodePoint(mirror(cp));
                    i -= Character.charCount(cp);
                }
            }
        }
        return out.toString();
    }

    private static int mirror(int cp) {
        return switch (cp) {
            case '(' -> ')';
            case ')' -> '(';
            case '[' -> ']';
            case ']' -> '[';
            case '{' -> '}';
            case '}' -> '{';
            case '<' -> '>';
            case '>' -> '<';
            case '«' -> '»';
            case '»' -> '«';
            default -> cp;
        };
    }
}
