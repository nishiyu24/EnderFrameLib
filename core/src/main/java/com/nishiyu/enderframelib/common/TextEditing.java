package com.nishiyu.enderframelib.common;


/**
 * テキスト欄（{@code input} / {@code textarea}）の編集規則（Minecraft の {@code EditBox} に依存しない純 Java）。
 * キャレットと選択範囲・単語単位の移動・行頭／行末・上下移動・クリップボードを、文字列とキーから計算して返す。
 * DOM は触らない。結果を適用する側（{@link StandaloneView}）が {@code beforeinput} / {@code input} を送る。
 *
 * <p>位置は UTF-16 の添字で、サロゲートペアの途中には止まらない。
 */
public final class TextEditing {
    private TextEditing() {}

    /** クリップボードの受け口。窓では GLFW、テストではメモリ上の実装を渡す。 */
    public interface Clipboard {
        String get();

        void set(String text);

        static Clipboard inMemory() {
            return new Clipboard() {
                private String held = "";

                @Override
                public String get() {
                    return held;
                }

                @Override
                public void set(String text) {
                    held = text != null ? text : "";
                }
            };
        }
    }

    /** 1 つの欄のキャレット（{@code caret}）と選択の反対端（{@code anchor}）。両者が等しければ選択なし。 */
    public static final class State {
        public int caret;
        public int anchor;

        public State(int caret) {
            this.caret = caret;
            this.anchor = caret;
        }

        public boolean hasSelection() {
            return caret != anchor;
        }

        public int start() {
            return Math.min(caret, anchor);
        }

        public int end() {
            return Math.max(caret, anchor);
        }

        public String selected(String text) {
            return hasSelection() ? text.substring(clamp(start(), text), clamp(end(), text)) : "";
        }

        /** 文字列が外から変わっても範囲に収める。 */
        public void clampTo(String text) {
            caret = clamp(caret, text);
            anchor = clamp(anchor, text);
        }

        public void set(int caret, boolean extend) {
            this.caret = caret;
            if (!extend) this.anchor = caret;
        }
    }

    /**
     * 編集の結果。{@code handled} はキーを使ったか、{@code changed} は文字列が変わるか（変わるなら
     * {@code text} が新しい値、{@code inputType} / {@code data} は {@code input} イベント用）。
     * キャレットと選択は {@code caret} / {@code anchor} で、{@code changed} のときは適用が成功してから反映する。
     */
    public record Result(boolean handled, boolean changed, String text, int caret, int anchor,
                         String inputType, String data) {
        static final Result IGNORED = new Result(false, false, null, 0, 0, null, null);

        static Result moved(State s) {
            return new Result(true, false, null, s.caret, s.anchor, null, null);
        }

        static Result edit(String text, int caret, String inputType, String data) {
            return new Result(true, true, text, caret, caret, inputType, data);
        }
    }

    /** 文字（コードポイント）の入力。選択があれば置き換える。 */
    public static Result type(State s, String text, String insertion) {
        s.clampTo(text);
        return replace(s, text, insertion, "insertText");
    }

    /**
     * キー（GLFW）の押下。矢印・Home / End・Backspace / Delete・Enter（複数行のみ）・Ctrl+A / C / X / V を扱う。
     * {@code Tab} などここで扱わないキーは {@link Result#handled()} が false。
     */
    public static Result key(State s, String text, int key, int mods, boolean multiline, Clipboard clipboard) {
        s.clampTo(text);
        boolean shift = (mods & Keys.MOD_SHIFT) != 0;
        boolean word = (mods & (Keys.MOD_CONTROL | Keys.MOD_ALT)) != 0;
        boolean ctrl = (mods & (Keys.MOD_CONTROL | Keys.MOD_SUPER)) != 0;

        if (ctrl) {
            switch (key) {
                case Keys.A -> {
                    s.anchor = 0;
                    s.caret = text.length();
                    return Result.moved(s);
                }
                case Keys.C -> {
                    if (s.hasSelection() && clipboard != null) clipboard.set(s.selected(text));
                    return Result.moved(s);
                }
                case Keys.X -> {
                    if (!s.hasSelection()) return Result.moved(s);
                    if (clipboard != null) clipboard.set(s.selected(text));
                    return replace(s, text, "", "deleteByCut");
                }
                case Keys.V -> {
                    String clip = clipboard != null ? clipboard.get() : null;
                    if (clip == null || clip.isEmpty()) return Result.moved(s);
                    clip = clip.replace("\r\n", "\n").replace('\r', '\n');
                    if (!multiline) clip = clip.replace('\n', ' ');
                    return replace(s, text, clip, "insertFromPaste");
                }
                default -> { }
            }
        }

        switch (key) {
            case Keys.LEFT -> {
                if (s.hasSelection() && !shift) s.set(s.start(), false);
                else s.set(word ? wordStart(text, s.caret) : step(text, s.caret, -1), shift);
                return Result.moved(s);
            }
            case Keys.RIGHT -> {
                if (s.hasSelection() && !shift) s.set(s.end(), false);
                else s.set(word ? wordEnd(text, s.caret) : step(text, s.caret, 1), shift);
                return Result.moved(s);
            }
            case Keys.HOME -> {
                s.set(word || !multiline ? 0 : lineStart(text, s.caret), shift);
                return Result.moved(s);
            }
            case Keys.END -> {
                s.set(word || !multiline ? text.length() : lineEnd(text, s.caret), shift);
                return Result.moved(s);
            }
            case Keys.UP, Keys.DOWN -> {
                if (!multiline) {
                    s.set(key == Keys.UP ? 0 : text.length(), shift);
                    return Result.moved(s);
                }
                s.set(verticalMove(text, s.caret, key == Keys.UP ? -1 : 1), shift);
                return Result.moved(s);
            }
            case Keys.BACKSPACE, Keys.DELETE -> {
                boolean back = key == Keys.BACKSPACE;
                String type = back ? "deleteContentBackward" : "deleteContentForward";
                if (s.hasSelection()) return replace(s, text, "", type);
                int target = back ? (word ? wordStart(text, s.caret) : step(text, s.caret, -1))
                        : (word ? wordEnd(text, s.caret) : step(text, s.caret, 1));
                if (target == s.caret) return Result.moved(s);
                s.anchor = target;
                return replace(s, text, "", type);
            }
            case Keys.ENTER, Keys.KP_ENTER -> {
                return multiline ? replace(s, text, "\n", "insertLineBreak") : Result.IGNORED;
            }
            default -> {
                return Result.IGNORED;
            }
        }
    }

    private static Result replace(State s, String text, String insertion, String inputType) {
        int from = s.start(), to = s.end();
        String next = text.substring(0, from) + insertion + text.substring(to);
        return Result.edit(next, from + insertion.length(), inputType, insertion.isEmpty() ? null : insertion);
    }

    // ------------------------------------------------------------------
    // 位置の計算
    // ------------------------------------------------------------------

    private static int clamp(int i, String text) {
        return Math.max(0, Math.min(text.length(), i));
    }

    /** コードポイント 1 つぶん前後へ。 */
    static int step(String text, int at, int dir) {
        if (dir < 0) return at <= 0 ? 0 : text.offsetByCodePoints(at, -1);
        return at >= text.length() ? text.length() : text.offsetByCodePoints(at, 1);
    }

    private static boolean isWordChar(int cp) {
        return Character.isLetterOrDigit(cp) || cp == '_';
    }

    /** 1 つ前の単語の先頭（単語でない文字を飛ばしてから単語の先頭まで）。 */
    static int wordStart(String text, int at) {
        int i = at;
        while (i > 0 && !isWordChar(text.codePointBefore(i))) i = step(text, i, -1);
        while (i > 0 && isWordChar(text.codePointBefore(i))) i = step(text, i, -1);
        return i;
    }

    /** 次の単語の末尾（単語でない文字を飛ばしてから単語の末尾まで）。 */
    static int wordEnd(String text, int at) {
        int i = at, n = text.length();
        while (i < n && !isWordChar(text.codePointAt(i))) i = step(text, i, 1);
        while (i < n && isWordChar(text.codePointAt(i))) i = step(text, i, 1);
        return i;
    }

    /**
     * {@code at} を含む単語の範囲 {@code [start, end]}（ダブルクリックの選択）。単語の上でなければ、同じ種類（空白・記号）の連なり。
     */
    public static int[] wordRange(String text, int at) {
        int n = text.length();
        at = Math.max(0, Math.min(n, at));
        if (n == 0) return new int[]{0, 0};
        // 境界上のキャレットは右側の文字を見る（末尾だけは左側）
        int probe = at < n ? at : step(text, at, -1);
        int cp = text.codePointAt(probe);
        int kind = kindOf(cp);
        int s = probe, e = step(text, probe, 1);
        while (s > 0 && kindOf(text.codePointBefore(s)) == kind) s = step(text, s, -1);
        while (e < n && kindOf(text.codePointAt(e)) == kind) e = step(text, e, 1);
        return new int[]{s, e};
    }

    /** 0: 単語の文字、1: 空白、2: 改行、3: 記号。 */
    private static int kindOf(int cp) {
        if (cp == '\n') return 2;
        if (isWordChar(cp)) return 0;
        return Character.isWhitespace(cp) ? 1 : 3;
    }

    /** {@code at} がある行の範囲 {@code [start, end]}（トリプルクリックの選択。改行は含まない）。 */
    public static int[] lineRange(String text, int at) {
        at = Math.max(0, Math.min(text.length(), at));
        return new int[]{lineStart(text, at), lineEnd(text, at)};
    }

    static int lineStart(String text, int caret) {
        return startOfLine(text, caret);
    }

    static int lineEnd(String text, int caret) {
        int e = text.indexOf('\n', caret);
        return e < 0 ? text.length() : e;
    }

    /** 行をまたぐ上下移動。列は保ち、行が短ければ行末へ。先頭行の上は先頭、末尾行の下は末尾。 */
    static int verticalMove(String text, int caret, int dir) {
        int start = startOfLine(text, caret);
        int col = caret - start;
        if (dir < 0) {
            if (start == 0) return 0;
            int prevEnd = start - 1;
            int prevStart = startOfLine(text, prevEnd);
            return prevStart + Math.min(col, prevEnd - prevStart);
        }
        int end = lineEnd(text, caret);
        if (end >= text.length()) return text.length();
        int nextStart = end + 1;
        int nextEnd = lineEnd(text, nextStart);
        return nextStart + Math.min(col, nextEnd - nextStart);
    }

    private static int startOfLine(String text, int caret) {
        return caret == 0 ? 0 : text.lastIndexOf('\n', caret - 1) + 1;
    }

    /** 文字列中の {@code at} が属する行の番号（0 起点）と行頭からの列。 */
    public static int[] rowAndColumn(String text, int at) {
        int row = 0, start = 0;
        int limit = Math.min(at, text.length());
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') {
                row++;
                start = i + 1;
            }
        }
        return new int[]{row, limit - start};
    }
}
