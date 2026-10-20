package com.nishiyu.enderframelib.core.css;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CSS Syntax Module Level 3 のトークナイザと、その上のコンポーネント値・規則・宣言のパーサ。
 *
 * 何が壊れていたか: 以前は {@code CssTokenizer} が文字を走査して「プレリュード＋波括弧ブロック」に切るだけで、
 * 宣言の値は文字列のまま正規表現（{@code NUMBER_WITH_UNIT} など）で何度も読み直していた。
 * ネストした規則（CSS ネスト）は波括弧の対応だけでは読めず、{@code url()} や文字列の例外を
 * 各所が自前で数えていた。
 *
 * ここでは仕様 §4（トークナイズ）と §5（パース）に従い、
 * <ol>
 *   <li>文字列を {@link Token} の列にする（{@link #tokenize}）</li>
 *   <li>トークン列を {@link ComponentValue}（保存されたトークン・関数・単純ブロック）の木にする（{@link #parseComponentValues}）</li>
 *   <li>木を規則の列（{@link #parseRuleList}）と、宣言と入れ子の規則が混じる列（{@link #parseBlockContents}）にする</li>
 * </ol>
 * 値の中身を読む側（長さ・色・数式・セレクタ以外のプロパティ）は文字列を読み直さず、この木を読む。
 */
public final class CssSyntax {

    private CssSyntax() {}

    // ==========================================
    // トークン
    // ==========================================

    public enum Type {
        IDENT, FUNCTION, AT_KEYWORD, HASH, STRING, BAD_STRING, URL, BAD_URL, DELIM,
        NUMBER, PERCENTAGE, DIMENSION, WHITESPACE, CDO, CDC, COLON, SEMICOLON, COMMA,
        LEFT_BRACKET, RIGHT_BRACKET, LEFT_PAREN, RIGHT_PAREN, LEFT_BRACE, RIGHT_BRACE
    }

    /**
     * @param value  識別子・関数名・{@code @} 名・{@code #} 名は（エスケープを解いた）名前、文字列と URL は中身、
     *               {@code DELIM} はその 1 文字。それ以外は空
     * @param number 数値トークンの値（{@code PERCENTAGE} は {@code 50%} の 50）
     * @param unit   {@code DIMENSION} の単位（小文字化しない）。それ以外は空
     * @param flag   {@code NUMBER} / {@code DIMENSION} / {@code PERCENTAGE} では整数か、{@code HASH} では ID として有効な名前か
     * @param raw    ソース上の文字列。コメントは含まない
     */
    public record Token(Type type, String value, double number, String unit, boolean flag,
                        String raw, int start, int end) {

        public boolean is(Type t) {
            return type == t;
        }

        public boolean isDelim(char c) {
            return type == Type.DELIM && value.length() == 1 && value.charAt(0) == c;
        }

        public boolean isIdent(String name) {
            return type == Type.IDENT && value.equalsIgnoreCase(name);
        }
    }

    /** 文字列をトークン列にする（仕様 §4.3 の consume a token を EOF まで繰り返す）。 */
    public static List<Token> tokenize(String source) {
        return new Tokenizer(preprocess(source == null ? "" : source)).run();
    }

    /**
     * 入力の前処理（仕様 §3.3）のうち、文字数を変えないもの: NULL と対の無いサロゲートを U+FFFD にする。
     * 改行の正規化（CR / CRLF / FF → LF）は、トークンが元の文字列の位置を持つので行わず、
     * トークナイザが 5 つの改行文字をそのまま同じ扱いで読む。
     */
    private static String preprocess(String source) {
        StringBuilder sb = null;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            boolean broken = c == 0
                    || (Character.isHighSurrogate(c) && !(i + 1 < source.length() && Character.isLowSurrogate(source.charAt(i + 1))))
                    || (Character.isLowSurrogate(c) && !(i > 0 && Character.isHighSurrogate(source.charAt(i - 1))));
            if (!broken) {
                if (sb != null) sb.append(c);
                continue;
            }
            if (sb == null) sb = new StringBuilder(source.length()).append(source, 0, i);
            sb.append('�');
        }
        return sb == null ? source : sb.toString();
    }

    /** CSS の空白: U+0009・U+000A・U+000C・U+000D・U+0020 の 5 文字だけ（Unicode の空白や U+000B は含まない）。 */
    public static boolean isCssWhitespace(char c) {
        return isWhitespace(c);
    }

    /** 前後の CSS 空白を取る。{@link String#trim()} は U+000B などの制御文字も落とすので使わない。 */
    public static String trimCssWhitespace(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isWhitespace(text.charAt(start))) start++;
        while (end > start && isWhitespace(text.charAt(end - 1))) end--;
        return text.substring(start, end);
    }

    /** {@code at} から識別子が始まるか（仕様 §4.3.9 の 3 コードポイントの判定）。 */
    public static boolean startsIdentifier(String text, int at) {
        return new Tokenizer(text).startsIdentifier(at);
    }

    private static final class Tokenizer {
        private final String s;
        private final int len;
        private int pos;
        private final List<Token> out = new ArrayList<>();

        Tokenizer(String s) {
            this.s = s;
            this.len = s.length();
        }

        List<Token> run() {
            while (true) {
                skipComments();
                if (pos >= len) return out;
                consumeToken();
            }
        }

        private void skipComments() {
            while (pos + 1 < len && s.charAt(pos) == '/' && s.charAt(pos + 1) == '*') {
                int end = s.indexOf("*/", pos + 2);
                pos = end < 0 ? len : end + 2;
            }
        }

        private int peek(int offset) {
            int i = pos + offset;
            return i < len ? s.charAt(i) : -1;
        }

        private void emit(Type type, String value, double number, String unit, boolean flag, int start) {
            out.add(new Token(type, value, number, unit, flag, rawText(type, start), start, pos));
        }

        /**
         * トークンのソース上の文字列。EOF で終わった名前の末尾のバックスラッシュ（エスケープの対象が無い）は
         * 名前の値と同じく U+FFFD に置き換え（§4.3.7）、閉じていない {@code url()} は {@code )} を補う。
         * 閉じていない文字列は、対象の無い末尾のバックスラッシュを捨てるだけ。
         */
        private String rawText(Type type, int start) {
            String raw = s.substring(start, pos);
            if (pos < len) return raw;
            if (raw.endsWith("\\") && endsWithUnpairedBackslash(raw)) {
                String withoutBackslash = raw.substring(0, raw.length() - 1);
                raw = type == Type.STRING ? withoutBackslash : withoutBackslash + '\uFFFD';
            }
            if (type == Type.URL && !raw.endsWith(")")) return raw + ")";
            return type == Type.STRING && !isClosedString(raw) ? raw + raw.charAt(0) : raw;
        }

        /** 文字列トークンのソースが、開きと同じ引用符で（エスケープされずに）閉じているか。 */
        private static boolean isClosedString(String raw) {
            if (raw.length() < 2 || raw.charAt(raw.length() - 1) != raw.charAt(0)) return false;
            return !endsWithUnpairedBackslash(raw.substring(0, raw.length() - 1));
        }

        private static boolean endsWithUnpairedBackslash(String raw) {
            int trailing = 0;
            while (trailing < raw.length() && raw.charAt(raw.length() - 1 - trailing) == '\\') trailing++;
            return trailing % 2 == 1;
        }

        private void emit(Type type, int start) {
            emit(type, "", 0, "", false, start);
        }

        private void consumeToken() {
            int start = pos;
            char c = s.charAt(pos);

            if (isWhitespace(c)) {
                while (pos < len && isWhitespace(s.charAt(pos))) pos++;
                emit(Type.WHITESPACE, start);
                return;
            }
            switch (c) {
                case '"', '\'' -> {
                    consumeString(c, start);
                    return;
                }
                case '#' -> {
                    pos++;
                    if (pos < len && (isNameChar(s.charAt(pos)) || startsEscape(pos))) {
                        boolean isId = startsIdentifier(pos);
                        String name = consumeName();
                        emit(Type.HASH, name, 0, "", isId, start);
                    } else {
                        emit(Type.DELIM, "#", 0, "", false, start);
                    }
                    return;
                }
                case '(' -> single(Type.LEFT_PAREN);
                case ')' -> single(Type.RIGHT_PAREN);
                case '[' -> single(Type.LEFT_BRACKET);
                case ']' -> single(Type.RIGHT_BRACKET);
                case '{' -> single(Type.LEFT_BRACE);
                case '}' -> single(Type.RIGHT_BRACE);
                case ',' -> single(Type.COMMA);
                case ':' -> single(Type.COLON);
                case ';' -> single(Type.SEMICOLON);
                case '+', '.' -> {
                    if (startsNumber(pos)) consumeNumeric(start);
                    else delim(c, start);
                }
                case '-' -> {
                    if (startsNumber(pos)) {
                        consumeNumeric(start);
                    } else if (peek(1) == '-' && peek(2) == '>') {
                        pos += 3;
                        emit(Type.CDC, start);
                    } else if (startsIdentifier(pos)) {
                        consumeIdentLike(start);
                    } else {
                        delim(c, start);
                    }
                }
                case '<' -> {
                    if (s.startsWith("<!--", pos)) {
                        pos += 4;
                        emit(Type.CDO, start);
                    } else {
                        delim(c, start);
                    }
                }
                case '@' -> {
                    pos++;
                    if (startsIdentifier(pos)) {
                        String name = consumeName();
                        emit(Type.AT_KEYWORD, name, 0, "", false, start);
                    } else {
                        emit(Type.DELIM, "@", 0, "", false, start);
                    }
                }
                case '\\' -> {
                    if (startsEscape(pos)) consumeIdentLike(start);
                    else delim(c, start);
                }
                default -> {
                    if (c >= '0' && c <= '9') consumeNumeric(start);
                    else if (isNameStart(c)) consumeIdentLike(start);
                    else delim(c, start);
                }
            }
        }

        private void single(Type type) {
            int start = pos++;
            emit(type, start);
        }

        private void delim(char c, int start) {
            pos++;
            emit(Type.DELIM, String.valueOf(c), 0, "", false, start);
        }

        // ---- 文字列

        private void consumeString(char quote, int start) {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= len) {
                    emit(Type.STRING, sb.toString(), 0, "", false, start);
                    return;
                }
                char c = s.charAt(pos);
                if (c == quote) {
                    pos++;
                    emit(Type.STRING, sb.toString(), 0, "", false, start);
                    return;
                }
                if (isNewline(c)) {
                    // 閉じていない文字列（改行は含めない）
                    emit(Type.BAD_STRING, sb.toString(), 0, "", false, start);
                    return;
                }
                if (c == '\\') {
                    if (pos + 1 >= len) {
                        pos++;
                    } else if (isNewline(s.charAt(pos + 1))) {
                        pos += 2; // 行継続
                        if (s.charAt(pos - 1) == '\r' && pos < len && s.charAt(pos) == '\n') pos++;
                    } else {
                        pos++;
                        sb.appendCodePoint(consumeEscape());
                    }
                    continue;
                }
                sb.append(c);
                pos++;
            }
        }

        // ---- 数

        private void consumeNumeric(int start) {
            boolean[] integer = {true};
            double number = consumeNumber(integer);
            if (startsIdentifier(pos)) {
                String unit = consumeName();
                emit(Type.DIMENSION, "", number, unit, integer[0], start);
            } else if (pos < len && s.charAt(pos) == '%') {
                pos++;
                emit(Type.PERCENTAGE, "", number, "", integer[0], start);
            } else {
                emit(Type.NUMBER, "", number, "", integer[0], start);
            }
        }

        private double consumeNumber(boolean[] integer) {
            int numStart = pos;
            if (pos < len && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
            while (pos < len && isDigit(s.charAt(pos))) pos++;
            if (pos + 1 < len && s.charAt(pos) == '.' && isDigit(s.charAt(pos + 1))) {
                integer[0] = false;
                pos++;
                while (pos < len && isDigit(s.charAt(pos))) pos++;
            }
            if (pos < len && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                int sign = peek(1) == '+' || peek(1) == '-' ? 1 : 0;
                int digit = peek(1 + sign);
                if (digit >= '0' && digit <= '9') {
                    integer[0] = false;
                    pos += 1 + sign;
                    while (pos < len && isDigit(s.charAt(pos))) pos++;
                }
            }
            try {
                return Double.parseDouble(s.substring(numStart, pos));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        // ---- 識別子・関数・url()

        private void consumeIdentLike(int start) {
            String name = consumeName();
            if (name.equalsIgnoreCase("url") && pos < len && s.charAt(pos) == '(') {
                pos++;
                int lookahead = pos;
                while (lookahead < len && isWhitespace(s.charAt(lookahead))) lookahead++;
                // url( の後が引用符なら通常の関数。そうでなければ引用符なしの url トークン
                if (lookahead < len && (s.charAt(lookahead) == '"' || s.charAt(lookahead) == '\'')) {
                    emit(Type.FUNCTION, name, 0, "", false, start);
                } else {
                    pos = lookahead;
                    consumeUrl(start);
                }
                return;
            }
            if (pos < len && s.charAt(pos) == '(') {
                pos++;
                emit(Type.FUNCTION, name, 0, "", false, start);
                return;
            }
            emit(Type.IDENT, name, 0, "", false, start);
        }

        private void consumeUrl(int start) {
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= len) {
                    emit(Type.URL, sb.toString(), 0, "", false, start);
                    return;
                }
                char c = s.charAt(pos);
                if (c == ')') {
                    pos++;
                    emit(Type.URL, sb.toString(), 0, "", false, start);
                    return;
                }
                if (isWhitespace(c)) {
                    while (pos < len && isWhitespace(s.charAt(pos))) pos++;
                    if (pos >= len || s.charAt(pos) == ')') {
                        if (pos < len) pos++;
                        emit(Type.URL, sb.toString(), 0, "", false, start);
                    } else {
                        consumeBadUrlRemnants();
                        emit(Type.BAD_URL, "", 0, "", false, start);
                    }
                    return;
                }
                if (c == '"' || c == '\'' || c == '(' || isNonPrintable(c)) {
                    consumeBadUrlRemnants();
                    emit(Type.BAD_URL, "", 0, "", false, start);
                    return;
                }
                if (c == '\\') {
                    if (startsEscape(pos)) {
                        pos++;
                        sb.appendCodePoint(consumeEscape());
                        continue;
                    }
                    consumeBadUrlRemnants();
                    emit(Type.BAD_URL, "", 0, "", false, start);
                    return;
                }
                sb.append(c);
                pos++;
            }
        }

        private void consumeBadUrlRemnants() {
            while (pos < len) {
                char c = s.charAt(pos);
                if (c == ')') {
                    pos++;
                    return;
                }
                if (startsEscape(pos)) {
                    pos++;
                    consumeEscape();
                } else {
                    pos++;
                }
            }
        }

        // ---- 名前とエスケープ

        private String consumeName() {
            StringBuilder sb = new StringBuilder();
            while (pos < len) {
                char c = s.charAt(pos);
                if (isNameChar(c)) {
                    sb.append(c);
                    pos++;
                } else if (startsEscape(pos)) {
                    pos++;
                    sb.appendCodePoint(consumeEscape());
                } else {
                    break;
                }
            }
            return sb.toString();
        }

        /** バックスラッシュの次から読んでコードポイントを返す。 */
        private int consumeEscape() {
            if (pos >= len) return 0xFFFD;
            char c = s.charAt(pos);
            if (Character.digit(c, 16) >= 0) {
                int value = 0;
                int digits = 0;
                while (pos < len && digits < 6 && Character.digit(s.charAt(pos), 16) >= 0) {
                    value = value * 16 + Character.digit(s.charAt(pos), 16);
                    pos++;
                    digits++;
                }
                if (pos < len && isWhitespace(s.charAt(pos))) {
                    if (s.charAt(pos) == '\r' && pos + 1 < len && s.charAt(pos + 1) == '\n') pos++;
                    pos++;
                }
                if (value == 0 || value > Character.MAX_CODE_POINT
                        || (value >= 0xD800 && value <= 0xDFFF)) {
                    return 0xFFFD;
                }
                return value;
            }
            int cp = s.codePointAt(pos);
            pos += Character.charCount(cp);
            return cp;
        }

        // ---- 先読み（仕様 §4.3.8〜§4.3.10）

        private boolean startsEscape(int at) {
            // バックスラッシュの次が EOF のエスケープも有効（U+FFFD になる）
            return at < len && s.charAt(at) == '\\' && (at + 1 >= len || !isNewline(s.charAt(at + 1)));
        }

        private boolean startsIdentifier(int at) {
            if (at >= len) return false;
            char c = s.charAt(at);
            if (c == '-') {
                if (at + 1 >= len) return false;
                char d = s.charAt(at + 1);
                return isNameStart(d) || d == '-' || startsEscape(at + 1);
            }
            return isNameStart(c) || startsEscape(at);
        }

        private boolean startsNumber(int at) {
            if (at >= len) return false;
            char c = s.charAt(at);
            if (c == '+' || c == '-') {
                if (at + 1 >= len) return false;
                char d = s.charAt(at + 1);
                if (isDigit(d)) return true;
                return d == '.' && at + 2 < len && isDigit(s.charAt(at + 2));
            }
            if (c == '.') return at + 1 < len && isDigit(s.charAt(at + 1));
            return isDigit(c);
        }
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }

    private static boolean isNewline(char c) {
        return c == '\n' || c == '\r' || c == '\f';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isNameStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c >= 0x80;
    }

    private static boolean isNameChar(char c) {
        return isNameStart(c) || isDigit(c) || c == '-';
    }

    private static boolean isNonPrintable(char c) {
        return (c >= 0 && c <= 8) || c == 0x0B || (c >= 0x0E && c <= 0x1F) || c == 0x7F;
    }

    // ==========================================
    // コンポーネント値
    // ==========================================

    /** 保存されたトークン・関数・単純ブロックのいずれか（仕様 §5.4.7〜§5.4.9）。 */
    public sealed interface ComponentValue permits Preserved, Function, Block {
        int start();

        int end();
    }

    public record Preserved(Token token) implements ComponentValue {
        @Override
        public int start() {
            return token.start();
        }

        @Override
        public int end() {
            return token.end();
        }
    }

    /** {@code name( … )}。{@code args} は括弧の内側のコンポーネント値（カンマも保存されたトークンとして残る）。 */
    public record Function(String name, List<ComponentValue> args, int start, int end) implements ComponentValue {
        public boolean is(String expected) {
            return name.equalsIgnoreCase(expected);
        }
    }

    /** {@code ( … )} / {@code [ … ]} / {@code { … }}。{@code open} は開きのトークン種別。 */
    public record Block(Type open, List<ComponentValue> body, int start, int end) implements ComponentValue {}

    public static List<ComponentValue> parseComponentValues(String source) {
        return parseComponentValues(tokenize(source));
    }

    /** トークン列をコンポーネント値の木にする。閉じていない関数・ブロックは EOF で閉じる。 */
    public static List<ComponentValue> parseComponentValues(List<Token> tokens) {
        return consumeSequence(tokens, new int[]{0}, null, new int[1]);
    }

    /**
     * {@code closer} が null なら EOF まで。そうでなければ対応する閉じのトークンまで（閉じは読み捨てる）。
     *
     * @param closedAt 閉じのトークンで終わったらその終端、EOF で終わったら -1 が入る
     */
    private static List<ComponentValue> consumeSequence(List<Token> tokens, int[] cursor, Type closer, int[] closedAt) {
        List<ComponentValue> out = new ArrayList<>();
        closedAt[0] = -1;
        while (cursor[0] < tokens.size()) {
            Token t = tokens.get(cursor[0]);
            if (closer != null && t.type() == closer) {
                cursor[0]++;
                closedAt[0] = t.end();
                return out;
            }
            out.add(consumeValue(tokens, cursor));
        }
        return out;
    }

    private static ComponentValue consumeValue(List<Token> tokens, int[] cursor) {
        Token t = tokens.get(cursor[0]++);
        int[] closedAt = {-1};
        switch (t.type()) {
            case LEFT_PAREN, LEFT_BRACKET, LEFT_BRACE -> {
                Type closer = t.type() == Type.LEFT_PAREN ? Type.RIGHT_PAREN
                        : t.type() == Type.LEFT_BRACKET ? Type.RIGHT_BRACKET : Type.RIGHT_BRACE;
                List<ComponentValue> body = consumeSequence(tokens, cursor, closer, closedAt);
                return new Block(t.type(), body, t.start(), endOf(t, body, closedAt[0]));
            }
            case FUNCTION -> {
                List<ComponentValue> args = consumeSequence(tokens, cursor, Type.RIGHT_PAREN, closedAt);
                return new Function(t.value(), args, t.start(), endOf(t, args, closedAt[0]));
            }
            default -> {
                return new Preserved(t);
            }
        }
    }

    /** 閉じで終わったなら閉じの終端、EOF で閉じたなら中身の終端（中身が空なら開きの終端）。 */
    private static int endOf(Token open, List<ComponentValue> body, int closedAt) {
        if (closedAt >= 0) return closedAt;
        return body.isEmpty() ? open.end() : body.getLast().end();
    }

    // ==========================================
    // コンポーネント値の操作
    // ==========================================

    public static boolean isWhitespace(ComponentValue v) {
        return v instanceof Preserved p && p.token().type() == Type.WHITESPACE;
    }

    public static boolean isToken(ComponentValue v, Type type) {
        return v instanceof Preserved p && p.token().type() == type;
    }

    public static Token tokenOf(ComponentValue v) {
        return v instanceof Preserved p ? p.token() : null;
    }

    /** 前後の空白を除いた部分列。 */
    public static List<ComponentValue> trim(List<ComponentValue> values) {
        int from = 0;
        int to = values.size();
        while (from < to && isWhitespace(values.get(from))) from++;
        while (to > from && isWhitespace(values.get(to - 1))) to--;
        return values.subList(from, to);
    }

    /** 最上位の {@code separator} 区切りで分ける（各要素は前後の空白を除く）。空の要素も残す。 */
    public static List<List<ComponentValue>> split(List<ComponentValue> values, Type separator) {
        List<List<ComponentValue>> out = new ArrayList<>();
        int from = 0;
        for (int i = 0; i < values.size(); i++) {
            if (isToken(values.get(i), separator)) {
                out.add(trim(values.subList(from, i)));
                from = i + 1;
            }
        }
        out.add(trim(values.subList(from, values.size())));
        return out;
    }

    /** 最上位の空白区切りで分ける。空白は結果に含めない。 */
    public static List<List<ComponentValue>> splitOnWhitespace(List<ComponentValue> values) {
        List<List<ComponentValue>> out = new ArrayList<>();
        List<ComponentValue> current = new ArrayList<>();
        for (ComponentValue v : values) {
            if (isWhitespace(v)) {
                if (!current.isEmpty()) out.add(current);
                current = new ArrayList<>();
            } else {
                current.add(v);
            }
        }
        if (!current.isEmpty()) out.add(current);
        return out;
    }

    /** コンポーネント値を CSS のテキストに戻す。コメントで隔てられていたトークンの間には空白を 1 つ入れる。 */
    public static String serialize(List<ComponentValue> values) {
        return serialize(values, Token::raw);
    }

    /**
     * トークンごとに出力する文字列を差し替えながらテキストに戻す（単位の換算など、値の一部だけを書き換えるとき）。
     * 関数・ブロックの中も辿る。
     */
    public static String serialize(List<ComponentValue> values, java.util.function.Function<Token, String> tokenText) {
        StringBuilder sb = new StringBuilder();
        appendSerialized(sb, values, tokenText);
        return sb.toString();
    }

    public static String serialize(ComponentValue value) {
        return serialize(List.of(value), Token::raw);
    }

    private static void appendSerialized(StringBuilder sb, List<ComponentValue> values,
                                         java.util.function.Function<Token, String> tokenText) {
        ComponentValue previous = null;
        for (ComponentValue v : values) {
            if (previous != null && v.start() > previous.end()) sb.append(' ');
            switch (v) {
                case Preserved p -> sb.append(tokenText.apply(p.token()));
                case Function f -> {
                    sb.append(f.name()).append('(');
                    appendSerialized(sb, f.args(), tokenText);
                    sb.append(')');
                }
                case Block b -> {
                    sb.append(openChar(b.open()));
                    appendSerialized(sb, b.body(), tokenText);
                    sb.append(closeChar(b.open()));
                }
            }
            previous = v;
        }
    }

    private static char openChar(Type open) {
        return open == Type.LEFT_PAREN ? '(' : open == Type.LEFT_BRACKET ? '[' : '{';
    }

    private static char closeChar(Type open) {
        return open == Type.LEFT_PAREN ? ')' : open == Type.LEFT_BRACKET ? ']' : '}';
    }

    // ==========================================
    // 規則と宣言
    // ==========================================

    /**
     * 規則 1 つ。{@code atName} が null なら修飾規則（{@code a { … }}）、そうでなければ {@code @名前}。
     * {@code block} は波括弧の中身で、{@code @import …;} のように波括弧を持たないアットルールでは null。
     */
    public record Rule(String atName, List<ComponentValue> prelude, List<ComponentValue> block) {
        public boolean isAtRule() {
            return atName != null;
        }

        public boolean hasBlock() {
            return block != null;
        }

        public String preludeText() {
            return serialize(trim(prelude));
        }
    }

    /** 宣言 1 つ。{@code value} は {@code !important} を除いた値（コンポーネント値）。 */
    public record Declaration(String name, List<ComponentValue> value, boolean important) {
        public String valueText() {
            return serialize(trim(value));
        }
    }

    /** ブロックの中身の 1 項目。{@link Declaration} か入れ子の {@link Rule} のどちらか。 */
    public record Item(Declaration declaration, Rule rule) {}

    /** 最上位の規則リスト（仕様 §5.4.1）。{@code <!--} と {@code -->} は読み捨てる。 */
    public static List<Rule> parseRuleList(String source) {
        return parseRuleList(parseComponentValues(source), true);
    }

    public static List<Rule> parseRuleList(List<ComponentValue> values, boolean topLevel) {
        List<Rule> out = new ArrayList<>();
        int i = 0;
        while (i < values.size()) {
            ComponentValue v = values.get(i);
            if (isWhitespace(v)) {
                i++;
                continue;
            }
            if (topLevel && v instanceof Preserved p && (p.token().type() == Type.CDO || p.token().type() == Type.CDC)) {
                i++;
                continue;
            }
            int[] next = {i};
            Rule rule = v instanceof Preserved p && p.token().type() == Type.AT_KEYWORD
                    ? consumeAtRule(values, next)
                    : consumeQualifiedRule(values, next);
            i = next[0];
            if (rule != null) out.add(rule);
        }
        return out;
    }

    private static Rule consumeAtRule(List<ComponentValue> values, int[] cursor) {
        String name = ((Preserved) values.get(cursor[0]++)).token().value();
        List<ComponentValue> prelude = new ArrayList<>();
        while (cursor[0] < values.size()) {
            ComponentValue v = values.get(cursor[0]++);
            if (isToken(v, Type.SEMICOLON)) return new Rule(name, prelude, null);
            if (v instanceof Block b && b.open() == Type.LEFT_BRACE) return new Rule(name, prelude, b.body());
            prelude.add(v);
        }
        return new Rule(name, prelude, null);
    }

    /** 波括弧のブロックまでをプレリュードとする。ブロックが無いまま EOF なら捨てる（仕様の parse error）。 */
    private static Rule consumeQualifiedRule(List<ComponentValue> values, int[] cursor) {
        List<ComponentValue> prelude = new ArrayList<>();
        while (cursor[0] < values.size()) {
            ComponentValue v = values.get(cursor[0]++);
            if (v instanceof Block b && b.open() == Type.LEFT_BRACE) {
                // `--x:hover { … }` はカスタムプロパティの宣言とも規則とも読めて曖昧なので、規則としては捨てる（§5.4.3）
                return startsCustomProperty(prelude, firstNonWhitespace(prelude)) ? null : new Rule(null, prelude, b.body());
            }
            prelude.add(v);
        }
        return null;
    }

    private static int firstNonWhitespace(List<ComponentValue> values) {
        int i = 0;
        while (i < values.size() && isWhitespace(values.get(i))) i++;
        return i;
    }

    /**
     * ブロックの中身を宣言と入れ子の規則に分ける（CSS Syntax §5.4.4 と CSS Nesting §3）。
     *
     * {@code ;} までに波括弧のブロックが現れたらその項目は入れ子の規則、現れなければ宣言。
     * {@code --x: { … }} のようにカスタムプロパティの値に波括弧が入る宣言は、名前と {@code :} で先に判定する。
     */
    public static List<Item> parseBlockContents(List<ComponentValue> body) {
        List<Item> out = new ArrayList<>();
        int i = 0;
        while (i < body.size()) {
            ComponentValue v = body.get(i);
            if (isWhitespace(v) || isToken(v, Type.SEMICOLON)) {
                i++;
                continue;
            }
            if (v instanceof Preserved p && p.token().type() == Type.AT_KEYWORD) {
                int[] cursor = {i};
                out.add(new Item(null, consumeAtRule(body, cursor)));
                i = cursor[0];
                continue;
            }
            int end = i;
            boolean customProperty = startsCustomProperty(body, i);
            Block ruleBlock = null;
            while (end < body.size() && !isToken(body.get(end), Type.SEMICOLON)) {
                if (!customProperty && body.get(end) instanceof Block b && b.open() == Type.LEFT_BRACE) {
                    ruleBlock = b;
                    break;
                }
                end++;
            }
            if (ruleBlock != null) {
                out.add(new Item(null, new Rule(null, new ArrayList<>(body.subList(i, end)), ruleBlock.body())));
                i = end + 1;
            } else {
                Declaration declaration = parseDeclaration(body.subList(i, end));
                if (declaration != null) out.add(new Item(declaration, null));
                i = end + 1;
            }
        }
        return out;
    }

    private static boolean startsCustomProperty(List<ComponentValue> body, int i) {
        if (i >= body.size() || !(body.get(i) instanceof Preserved p) || p.token().type() != Type.IDENT || !p.token().value().startsWith("--")) {
            return false;
        }
        int j = i + 1;
        while (j < body.size() && isWhitespace(body.get(j))) j++;
        return j < body.size() && isToken(body.get(j), Type.COLON);
    }

    /** {@code name : value [!important]}。名前と {@code :} が無い、値が空のときは null（宣言ごと捨てる）。 */
    public static Declaration parseDeclaration(List<ComponentValue> tokens) {
        List<ComponentValue> trimmed = trim(tokens);
        if (trimmed.isEmpty() || !(trimmed.getFirst() instanceof Preserved first)
                || first.token().type() != Type.IDENT) {
            return null;
        }
        int i = 1;
        while (i < trimmed.size() && isWhitespace(trimmed.get(i))) i++;
        if (i >= trimmed.size() || !isToken(trimmed.get(i), Type.COLON)) return null;

        List<ComponentValue> value = new ArrayList<>(trimmed.subList(i + 1, trimmed.size()));
        boolean important = stripImportant(value);
        String name = first.token().value();
        boolean custom = name.startsWith("--");
        if (!custom) name = name.toLowerCase(Locale.ROOT);
        List<ComponentValue> trimmedValue = trim(value);
        // 空の値は、カスタムプロパティでは有効（空のトークン列）だが、このエンジンでは未定義と同じに扱うので捨てる
        if (trimmedValue.isEmpty()) return null;
        return new Declaration(name, new ArrayList<>(trimmedValue), important);
    }

    /** 末尾の {@code ! important}（間の空白可）を value から取り除く。取り除いたら true。 */
    private static boolean stripImportant(List<ComponentValue> value) {
        int end = value.size();
        while (end > 0 && isWhitespace(value.get(end - 1))) end--;
        if (end == 0 || !(value.get(end - 1) instanceof Preserved last) || !last.token().isIdent("important")) {
            return false;
        }
        int bang = end - 2;
        while (bang >= 0 && isWhitespace(value.get(bang))) bang--;
        if (bang < 0 || !(value.get(bang) instanceof Preserved b) || !b.token().isDelim('!')) return false;
        while (value.size() > bang) value.removeLast();
        return true;
    }

    /** {@code style} 属性のようにブロックの中身だけのテキストを宣言の列にする（入れ子の規則は無視）。 */
    public static List<Declaration> parseDeclarationList(String source) {
        List<Declaration> out = new ArrayList<>();
        for (Item item : parseBlockContents(parseComponentValues(source))) {
            if (item.declaration() != null) out.add(item.declaration());
        }
        return out;
    }
}
