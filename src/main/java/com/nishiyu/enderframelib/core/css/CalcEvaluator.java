package com.nishiyu.enderframelib.core.css;

public class CalcEvaluator {
    private final String str;
    private final int maxPixels;
    private final int rootW;
    private final int rootH;
    private final float elementFontSize;
    private final float rootFontSize;
    private int pos = -1;
    private int ch;

    public CalcEvaluator(String str, int maxPixels, int rootW, int rootH, float elementFontSize, float rootFontSize) {
        this.str = str;
        this.maxPixels = maxPixels;
        this.rootW = rootW;
        this.rootH = rootH;
        this.elementFontSize = elementFontSize;
        this.rootFontSize = rootFontSize;
    }

    public double evaluate() {
        nextChar();
        double x = parseExpression();
        if (pos < str.length()) return 0;
        return x;
    }

    private void nextChar() {
        ch = (++pos < str.length()) ? str.charAt(pos) : -1;
    }

    private boolean eat(int charToEat) {
        while (ch == ' ') nextChar();
        if (ch == charToEat) {
            nextChar();
            return true;
        }
        return false;
    }

    private double parseExpression() {
        double x = parseTerm();
        for (; ; ) {
            if (eat('+')) x += parseTerm();
            else if (eat('-')) x -= parseTerm();
            else return x;
        }
    }

    private double parseTerm() {
        double x = parseFactor();
        for (; ; ) {
            if (eat('*')) x *= parseFactor();
            else if (eat('/')) x /= parseFactor();
            else return x;
        }
    }

    private double parseFactor() {
        if (eat('+')) return parseFactor();
        if (eat('-')) return -parseFactor();
        double x;
        int startPos = this.pos;
        if (eat('(')) {
            x = parseExpression();
            eat(')');
        } else if ((ch >= '0' && ch <= '9') || ch == '.') {
            while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
            String numStr = str.substring(startPos, this.pos);
            StringBuilder unitSb = new StringBuilder();
            while ((ch >= 'a' && ch <= 'z') || ch == '%') {
                unitSb.append((char) ch);
                nextChar();
            }
            String unit = unitSb.toString();
            try {
                float val = Float.parseFloat(numStr);
                x = switch (unit) {
                    case "%" -> maxPixels * val / 100f;
                    case "vw" -> rootW * val / 100f;
                    case "vh" -> rootH * val / 100f;
                    case "em" -> elementFontSize * val;
                    case "rem" -> rootFontSize * val;
                    default -> val; // px or unitless
                };
            } catch (Exception e) {
                x = 0;
            }
        } else {
            return 0;
        }
        return x;
    }
}