package com.nishiyu.enderframelib.core.text;

import com.nishiyu.enderframelib.core.text.model.WordBreak;

/**
 * 折り返しの候補（Unicode Standard Annex #14 の Line Breaking Algorithm）。
 *
 * 文字を行分割クラスに分け、LB7〜LB31 の規則で「この 2 文字の間で折り返してよいか」を決める。
 * 以前は空白・ハイフン・和文だけを候補にしていたので、{@code /} や {@code —}、{@code (} の手前など
 * Chrome が折り返す位置で折り返せなかった。
 *
 * 実装した規則: LB7 / 8 / 8a / 9（結合文字）/ 11〜19 / 21 / 23〜25 / 28〜30a / 31。
 * LIMITATION: タイ語・ラオ語・クメール語などの辞書による分かち書き（LB1 の SA）、ハングルの音節の連結（LB26 / 27）、
 * 東アジア幅による LB30 の括弧の区別は持たない。それらは英字と同じ扱い（語の内側では折り返さない）。
 *
 * {@code word-break: keep-all} では表意文字・仮名・ハングルを英字として扱い、語の内側で折り返さない。
 */
public final class LineBreaker {

    /** 行分割クラス（UAX #14 Table 1 のうち、使い分けるもの）。 */
    enum Cls {
        AL, ID, SP, ZW, WJ, GL, BA, HY, B2, SY, IS, NU, PR, PO, OP, CL, CP, QU, EX, NS, CM, RI
    }

    /** 行頭に置けない和文の文字（NS 扱い）。 */
    private static final String NO_BREAK_BEFORE_WIDE = "・ーぁぃぅぇぉっゃゅょゎゕゖァィゥェォッャュョヮヵヶ々ゝゞヽヾ゛゜：；";
    /** 行末に置けない和文の文字（OP 扱い）。 */
    private static final String NO_BREAK_AFTER_WIDE = "「『（［｛〈《【〔〖";
    /** 閉じ括弧と句読点（CL 扱い）。 */
    private static final String CLOSING_WIDE = "」』）］｝〉》】〕〗、。，．";

    private LineBreaker() {}

    /** {@code index} の手前で折り返してよいか（{@code 0 < index < text.length()}、コードポイントの境目）。 */
    public static boolean canBreakBefore(String text, int index, WordBreak wordBreak) {
        int cur = text.codePointAt(index);
        int prev = text.codePointBefore(index);
        if (cur == '\n') return false;

        Cls curCls = classify(cur, wordBreak);
        Cls prevCls = classify(prev, wordBreak);
        // 語の内側（英字どうし）が圧倒的に多い。LB28 より前の規則で true を返すものは無いので先に答える
        if (prevCls == Cls.AL && curCls == Cls.AL) return false;

        // LB7: 空白と ZW の手前では折り返さない。LB9 / LB8a: 結合文字と ZWJ の手前も
        if (curCls == Cls.SP || curCls == Cls.ZW) return false;
        if (curCls == Cls.CM || prev == 0x200D) return false;

        // 空白・結合文字を飛ばした「実質の直前の文字」（LB8 / 14 / 15 / 16 / 17 の SP* 部分）
        int spaceCount = 0;
        Cls base;
        int scan = index;
        while (true) {
            if (scan <= 0) return false;
            int c = text.codePointBefore(scan);
            Cls k = classify(c, wordBreak);
            int start = scan - Character.charCount(c);
            if (k == Cls.SP) {
                spaceCount++;
                scan = start;
                continue;
            }
            if (k == Cls.CM && start > 0) {
                // 結合文字は直前の文字のクラスを引き継ぐ（LB9）
                scan = start;
                continue;
            }
            base = k;
            break;
        }

        // LB9: 結合文字は直前の文字と同じクラスとして扱う
        if (prevCls == Cls.CM && spaceCount == 0) prevCls = base;

        // LB8: ZW SP* ÷
        if (base == Cls.ZW) return true;
        // LB11: WJ の前後
        if (curCls == Cls.WJ || base == Cls.WJ) return false;
        // LB12: GL の後。LB12a: GL の前（空白・BA・HY の後を除く）
        if (spaceCount == 0 && prevCls == Cls.GL) return false;
        if (curCls == Cls.GL) return spaceCount > 0 || prevCls == Cls.BA || prevCls == Cls.HY;
        // LB13: CL / CP / EX / IS / SY の手前
        if (curCls == Cls.CL || curCls == Cls.CP || curCls == Cls.EX || curCls == Cls.IS || curCls == Cls.SY) return false;
        // LB14: OP SP* ×
        if (base == Cls.OP) return false;
        // LB15: QU SP* × OP
        if (base == Cls.QU && curCls == Cls.OP) return false;
        // LB16: (CL | CP) SP* × NS
        if ((base == Cls.CL || base == Cls.CP) && curCls == Cls.NS) return false;
        // LB17: B2 SP* × B2
        if (base == Cls.B2 && curCls == Cls.B2) return false;
        // LB18: SP ÷
        if (spaceCount > 0) return true;
        // LB19: QU の前後
        if (curCls == Cls.QU || prevCls == Cls.QU) return false;
        // LB21: BA / HY / NS の手前
        if (curCls == Cls.BA || curCls == Cls.HY || curCls == Cls.NS) return false;

        // LB23: AL × NU、NU × AL
        if (prevCls == Cls.AL && curCls == Cls.NU) return false;
        if (prevCls == Cls.NU && curCls == Cls.AL) return false;
        // LB23a: PR × ID、ID × PO
        if (prevCls == Cls.PR && curCls == Cls.ID) return false;
        if (prevCls == Cls.ID && curCls == Cls.PO) return false;
        // LB24: (PR | PO) × AL、AL × (PR | PO)
        if ((prevCls == Cls.PR || prevCls == Cls.PO) && curCls == Cls.AL) return false;
        if (prevCls == Cls.AL && (curCls == Cls.PR || curCls == Cls.PO)) return false;
        // LB25: 数と通貨・パーセントなど
        if ((prevCls == Cls.CL || prevCls == Cls.CP || prevCls == Cls.NU) && (curCls == Cls.PO || curCls == Cls.PR)) return false;
        if ((prevCls == Cls.PO || prevCls == Cls.PR) && (curCls == Cls.OP || curCls == Cls.NU)) return false;
        if ((prevCls == Cls.HY || prevCls == Cls.IS || prevCls == Cls.NU || prevCls == Cls.SY) && curCls == Cls.NU) return false;
        // LB28: AL × AL。LB29: IS × AL
        if (prevCls == Cls.AL && curCls == Cls.AL) return false;
        if (prevCls == Cls.IS && curCls == Cls.AL) return false;
        // LB30: (AL | NU) × OP、CP × (AL | NU)
        if ((prevCls == Cls.AL || prevCls == Cls.NU) && curCls == Cls.OP && cur < 0x2E80) return false;
        if (prevCls == Cls.CP && (curCls == Cls.AL || curCls == Cls.NU) && prev < 0x2E80) return false;
        // LB30a: 地域指示記号は 2 つで 1 組
        if (prevCls == Cls.RI && curCls == Cls.RI) return riCountBefore(text, index) % 2 == 0;

        // LB31: それ以外は折り返してよい（break-all はここに来る語の内側も含めて折り返せる）
        return true;
    }

    private static int riCountBefore(String text, int index) {
        int n = 0;
        int i = index;
        while (i > 0) {
            int c = text.codePointBefore(i);
            if (!isRegionalIndicator(c)) break;
            n++;
            i -= Character.charCount(c);
        }
        return n;
    }

    private static boolean isRegionalIndicator(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }

    static Cls classify(int cp, WordBreak wordBreak) {
        Cls cls = classifyBase(cp, wordBreak == WordBreak.KEEP_ALL);
        // break-all: 英字・数字も表意文字と同じく、どの 2 字の間でも折り返してよい
        if (wordBreak == WordBreak.BREAK_ALL && (cls == Cls.AL || cls == Cls.NU)) return Cls.ID;
        return cls;
    }

    private static Cls classifyBase(int cp, boolean keepAll) {
        if (cp < 0x80) return ASCII[cp];
        switch (cp) {
            case 0x0085, 0x2028, 0x2029 -> { return Cls.SP; } // 改行相当は事前に分けてあるので空白扱い
            case 0x00A0, 0x202F, 0x2007, 0x2011, 0x0F0C -> { return Cls.GL; }
            case 0x200B -> { return Cls.ZW; }
            case 0x2060, 0xFEFF -> { return Cls.WJ; }
            case 0x00AD, 0x058A, 0x2010, 0x2012, 0x2013, 0x1680, 0x3000, 0x205F -> { return Cls.BA; }
            case 0x2014 -> { return Cls.B2; }
            case 0x00AB, 0x00BB, 0x2018, 0x2019, 0x201A, 0x201B, 0x201C, 0x201D, 0x201E, 0x201F, 0x2039, 0x203A -> { return Cls.QU; }
            case 0x00A1, 0x00BF -> { return Cls.OP; }
            case 0x00A2, 0x00B0, 0x2030, 0x2031, 0x2032, 0x2033, 0x2103, 0x2109 -> { return Cls.PO; }
            case 0x00A3, 0x00A4, 0x00A5, 0x00B1, 0x20A0, 0x20AC, 0x2212, 0x2213 -> { return Cls.PR; }
            case 0x2024, 0x2025, 0x2026, 0x203C, 0x203D, 0x2047, 0x2048, 0x2049 -> { return Cls.NS; }
            case 0x200D -> { return Cls.CM; }
            default -> { }
        }
        if (cp >= 0x2000 && cp <= 0x200A) return Cls.BA;
        if (isRegionalIndicator(cp)) return Cls.RI;
        int type = Character.getType(cp);
        if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || type == Character.COMBINING_SPACING_MARK) {
            return Cls.CM;
        }
        if (cp <= 0xFFFF) {
            char c = (char) cp;
            if (NO_BREAK_AFTER_WIDE.indexOf(c) >= 0) return Cls.OP;
            if (CLOSING_WIDE.indexOf(c) >= 0) return Cls.CL;
            if (NO_BREAK_BEFORE_WIDE.indexOf(c) >= 0) return Cls.NS;
            if (c == 0xFF01 || c == 0xFF1F) return Cls.EX;
        }
        if (isIdeographic(cp)) return keepAll ? Cls.AL : Cls.ID;
        if (cp >= 0x1F000 && Character.isEmoji(cp)) return Cls.ID;
        return Cls.AL;
    }

    private static final Cls[] ASCII = new Cls[128];

    static {
        for (int cp = 0; cp < 128; cp++) ASCII[cp] = ascii(cp);
    }

    private static Cls ascii(int cp) {
        switch (cp) {
            case ' ', '\t', '\n', '\r' -> { return Cls.SP; }
            case '-' -> { return Cls.HY; }
            case '/' -> { return Cls.SY; }
            case ',', '.', ':', ';' -> { return Cls.IS; }
            case '!', '?' -> { return Cls.EX; }
            case '(', '[', '{' -> { return Cls.OP; }
            case ')', ']' -> { return Cls.CP; }
            case '}' -> { return Cls.CL; }
            case '"', '\'' -> { return Cls.QU; }
            case '$', '+', '\\' -> { return Cls.PR; }
            case '%' -> { return Cls.PO; }
            case '|' -> { return Cls.BA; }
            default -> {
                if (cp >= '0' && cp <= '9') return Cls.NU;
                if (cp < 0x20 || cp == 0x7F) return Cls.CM;
                return Cls.AL;
            }
        }
    }

    /** 表意文字・仮名・ハングル・全角記号。語の内側でも分割できる文字。 */
    static boolean isIdeographic(int cp) {
        return (cp >= 0x3000 && cp <= 0x30FF)     // CJK 記号・ひらがな・カタカナ
                || (cp >= 0x3100 && cp <= 0x312F) // 注音
                || (cp >= 0x3130 && cp <= 0x318F) // ハングル互換字母
                || (cp >= 0x31F0 && cp <= 0x31FF) // カタカナ拡張
                || (cp >= 0x3200 && cp <= 0x4DBF) // 囲み文字・CJK 部首・拡張 A
                || (cp >= 0x4E00 && cp <= 0x9FFF) // CJK 統合漢字
                || (cp >= 0xA960 && cp <= 0xA97F)
                || (cp >= 0xAC00 && cp <= 0xD7AF) // ハングル音節
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE30 && cp <= 0xFE4F)
                || (cp >= 0xFF00 && cp <= 0xFF60) // 全角英数・記号
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x20000 && cp <= 0x3FFFD);
    }
}
