package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.ValueComputer.CssWideKeyword;
import java.util.Map;

/**
 * 規則の宣言を、カスケードで使う形に <b>解析時に 1 回だけ</b> 前処理した表（宣言の事前コンパイル）。
 *
 * 以前は宣言を適用するたびに、値の全域キーワード判定・{@code var(} の有無・ショートハンドの展開の成否・色の構文検査・
 * 名前でのレジストリ引き・サブプロパティの列挙をやり直していた。これらは宣言の（名前, 値）だけで決まり、
 * 要素や状態には依らないので、ここで宣言ごとに配列へ詰めておき、適用は添字で書き込むだけにする。
 */
final class CompiledDeclarations {
    static final CompiledDeclarations EMPTY = new CompiledDeclarations(0);

    /** 値が {@code revert} のまま適用される（巻き戻し先が無い）ときに書く {@code unset}。 */
    static final CssValue UNSET = CssValue.of("unset");

    final int n;
    final String[] names;
    final String[] values;
    /** {@link #values} の正規の値オブジェクト。作業表へはこれを（文字列を引き直さずに）書く。 */
    final CssValue[] cssValues;
    /** 登録済みプロパティの添字。登録に無い名前は {@code ComputedStyle.CssPropertyId.NONE}。 */
    final int[] ids;
    /** 値が CSS 全域キーワードならその種別、でなければ null。 */
    final CssWideKeyword[] wide;
    /** 名前が {@code all} か。 */
    final boolean[] isAll;
    /** 通常の値として無効（解釈できないショートハンド・色）で、適用せず読み飛ばす宣言か。 */
    final boolean[] invalid;
    /** ショートハンドが先行するロングハンドを消すために列挙するサブプロパティ（無ければ長さ 0）。 */
    final String[][] subNames;
    final int[][] subIds;
    /** 値の先頭が {@code revert} の宣言を含むか（{@link StyleResolver} が巻き戻し先の追跡を要するかの保守的な判定）。 */
    final boolean mentionsRevert;

    private CompiledDeclarations(int n) {
        this.n = n;
        names = new String[n];
        values = new String[n];
        cssValues = new CssValue[n];
        ids = new int[n];
        wide = new CssWideKeyword[n];
        isAll = new boolean[n];
        invalid = new boolean[n];
        subNames = new String[n][];
        subIds = new int[n][];
        mentionsRevert = false;
    }

    private CompiledDeclarations(Map<String, String> decls) {
        int n = decls.size();
        this.n = n;
        names = new String[n];
        values = new String[n];
        cssValues = new CssValue[n];
        ids = new int[n];
        wide = new CssWideKeyword[n];
        isAll = new boolean[n];
        invalid = new boolean[n];
        subNames = new String[n][];
        subIds = new int[n][];
        boolean revert = false;
        int i = 0;
        for (Map.Entry<String, String> e : decls.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            names[i] = key;
            values[i] = value;
            cssValues[i] = CssValue.of(value);
            ids[i] = ComputedStyle.idOf(key);
            wide[i] = CssWideKeyword.of(value);
            isAll[i] = "all".equals(key);
            if (value != null && value.trim().regionMatches(true, 0, "revert", 0, 6)) revert = true;
            if (!isAll[i]) {
                PropertyRegistry.Property p = PropertyRegistry.get(key);
                if (wide[i] == null && value != null && !value.contains("var(")) {
                    invalid[i] = (p != null && p.expander() != null && p.expander().apply(value.trim()).isEmpty())
                            || !StyleResolver.isValidColorValue(p, key, value);
                }
                var subs = PropertyRegistry.subproperties(key);
                String[] sn = subs.toArray(new String[0]);
                int[] si = new int[sn.length];
                for (int j = 0; j < sn.length; j++) si[j] = ComputedStyle.idOf(sn[j]);
                subNames[i] = sn;
                subIds[i] = si;
            }
            i++;
        }
        mentionsRevert = revert;
    }

    static CompiledDeclarations of(Map<String, String> decls) {
        return decls == null || decls.isEmpty() ? EMPTY : new CompiledDeclarations(decls);
    }
}
