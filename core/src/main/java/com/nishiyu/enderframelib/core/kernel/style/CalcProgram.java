package com.nishiyu.enderframelib.core.kernel.style;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code calc()} / {@code min()} / {@code max()} / {@code clamp()} を、パース時に 1 回だけ
 * 逆ポーランド記法の命令配列（{@code int[]} ＋定数 {@code float[]}）にしたもの。
 * 評価は呼び出し側が渡すスタック配列の上で {@code switch} を回すだけで、割り当てが無い。
 */
public final class CalcProgram {
    private static final int PUSH = 0, ADD = 1, SUB = 2, MUL = 3, DIV = 4, MIN = 5, MAX = 6;
    // 命令 = op | unit << 8 | constIndex << 16（PUSH のときだけ unit / constIndex を使う）

    private final int[] code;
    private final float[] consts;
    private final int stackSize;

    private CalcProgram(int[] code, float[] consts, int stackSize) {
        this.code = code;
        this.consts = consts;
        this.stackSize = stackSize;
    }

    /** 評価に必要なスタックの長さ。 */
    public int stackSize() { return stackSize; }

    /** 命令数（テスト・診断用）。 */
    public int length() { return code.length; }

    /** 空間依存が無い（px と無単位だけ）か。真ならロード時に畳める。 */
    public boolean isConstant() {
        for (int c : code) {
            if ((c & 0xFF) == PUSH) {
                int u = (c >> 8) & 0xFF;
                if (u != PackedCssValue.U_PX && u != PackedCssValue.U_NONE) return false;
            }
        }
        return true;
    }

    /** {@code stack} は {@link #stackSize()} 以上の長さ。 */
    public float eval(float[] stack, float percentBase, float fontSize, float rootFontSize, float vw, float vh) {
        int sp = 0;
        for (int c : code) {
            switch (c & 0xFF) {
                case PUSH -> {
                    float k = consts[c >>> 16];
                    stack[sp++] = switch ((c >> 8) & 0xFF) {
                        case PackedCssValue.U_PERCENT -> k * percentBase / 100f;
                        case PackedCssValue.U_EM -> k * fontSize;
                        case PackedCssValue.U_REM -> k * rootFontSize;
                        case PackedCssValue.U_VW -> k * vw / 100f;
                        case PackedCssValue.U_VH -> k * vh / 100f;
                        default -> k;
                    };
                }
                case ADD -> { sp--; stack[sp - 1] += stack[sp]; }
                case SUB -> { sp--; stack[sp - 1] -= stack[sp]; }
                case MUL -> { sp--; stack[sp - 1] *= stack[sp]; }
                case DIV -> { sp--; stack[sp - 1] /= stack[sp]; }
                case MIN -> { sp--; stack[sp - 1] = Math.min(stack[sp - 1], stack[sp]); }
                case MAX -> { sp--; stack[sp - 1] = Math.max(stack[sp - 1], stack[sp]); }
                default -> throw new IllegalStateException("bad op " + (c & 0xFF));
            }
        }
        return stack[0];
    }

    /** 式をコンパイルする。構文が壊れていれば null。 */
    public static CalcProgram compile(String expr) {
        try {
            Parser p = new Parser(expr.trim().toLowerCase(java.util.Locale.ROOT));
            p.function();
            p.ws();
            if (p.pos != p.s.length() || p.depth != 1) return null;
            int[] code = new int[p.code.size()];
            for (int i = 0; i < code.length; i++) code[i] = p.code.get(i);
            float[] consts = new float[p.consts.size()];
            for (int i = 0; i < consts.length; i++) consts[i] = p.consts.get(i);
            return new CalcProgram(code, consts, Math.max(1, p.maxDepth));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** K_CALC の添字が指すプログラム表。 */
    public static final class Table {
        private final List<CalcProgram> programs = new ArrayList<>();

        public int add(CalcProgram p) {
            programs.add(p);
            return programs.size() - 1;
        }

        public CalcProgram get(int index) { return programs.get(index); }
        public int size() { return programs.size(); }
    }

    private static final class Parser {
        final String s;
        int pos;
        final List<Integer> code = new ArrayList<>();
        final List<Float> consts = new ArrayList<>();
        int depth, maxDepth;

        Parser(String s) { this.s = s; }

        void emit(int op) {
            code.add(op);
            if ((op & 0xFF) == PUSH) {
                depth++;
                maxDepth = Math.max(maxDepth, depth);
            } else {
                depth--;
            }
        }

        void ws() { while (pos < s.length() && s.charAt(pos) == ' ') pos++; }

        void fail() { throw new IllegalArgumentException(s); }

        void function() {
            String name = ident();
            ws();
            if (pos >= s.length() || s.charAt(pos) != '(') fail();
            pos++;
            args(name);
        }

        void args(String name) {
            switch (name) {
                case "calc" -> { sum(); expect(')'); }
                case "min", "max" -> {
                    int op = name.equals("min") ? MIN : MAX;
                    sum();
                    ws();
                    while (pos < s.length() && s.charAt(pos) == ',') {
                        pos++;
                        sum();
                        emit(op);
                        ws();
                    }
                    expect(')');
                }
                case "clamp" -> {
                    // clamp(a, b, c) = max(a, min(b, c))
                    sum(); comma(); sum(); comma(); sum(); expect(')');
                    emit(MIN);
                    emit(MAX);
                }
                default -> fail();
            }
        }

        void comma() {
            ws();
            if (pos >= s.length() || s.charAt(pos) != ',') fail();
            pos++;
        }

        void expect(char c) {
            ws();
            if (pos >= s.length() || s.charAt(pos) != c) fail();
            pos++;
        }

        String ident() {
            int st = pos;
            while (pos < s.length() && Character.isLetter(s.charAt(pos))) pos++;
            return s.substring(st, pos);
        }

        void sum() {
            product();
            for (; ; ) {
                ws();
                if (pos >= s.length()) return;
                char c = s.charAt(pos);
                if (c != '+' && c != '-') return;
                pos++;
                product();
                emit(c == '+' ? ADD : SUB);
            }
        }

        void product() {
            atom();
            for (; ; ) {
                ws();
                if (pos >= s.length()) return;
                char c = s.charAt(pos);
                if (c != '*' && c != '/') return;
                pos++;
                atom();
                emit(c == '*' ? MUL : DIV);
            }
        }

        void atom() {
            ws();
            if (pos >= s.length()) fail();
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                sum();
                expect(')');
                return;
            }
            if (Character.isLetter(c)) {
                String name = ident();
                if (pos < s.length() && s.charAt(pos) == '(') {
                    pos++;
                    args(name);
                    return;
                }
                fail();
            }
            int st = pos;
            if (c == '-' || c == '+') pos++;
            int digitsAt = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
            if (pos == digitsAt) fail();
            float v = Float.parseFloat(s.substring(st, pos));
            int us = pos;
            if (pos < s.length() && s.charAt(pos) == '%') pos++;
            else while (pos < s.length() && Character.isLetter(s.charAt(pos))) pos++;
            int unit = switch (s.substring(us, pos)) {
                case "" -> PackedCssValue.U_NONE;
                case "px" -> PackedCssValue.U_PX;
                case "%" -> PackedCssValue.U_PERCENT;
                case "em" -> PackedCssValue.U_EM;
                case "rem" -> PackedCssValue.U_REM;
                case "vw" -> PackedCssValue.U_VW;
                case "vh" -> PackedCssValue.U_VH;
                default -> -1;
            };
            if (unit < 0) fail();
            consts.add(v);
            emit(PUSH | (unit << 8) | ((consts.size() - 1) << 16));
        }
    }
}
