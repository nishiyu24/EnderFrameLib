package com.nishiyu.enderframelib.core.css;

import java.util.regex.Pattern;

/**
 * CSSの詳細度（Specificity）を表現し、比較可能にするクラス。
 */
public record Specificity(int id, int clazz, int type, int origin) implements Comparable<Specificity> {

    private static final Pattern WHITESPACE_SPLIT = Pattern.compile("\\s+");
    private static final Pattern SPEC_CLEAN_PATTERN = Pattern.compile("[#.\\[:].*");

    public static Specificity calculate(String selector, int originScore) {
        int idCount = 0;
        int classCount = 0;
        int typeCount = 0;

        String[] parts = WHITESPACE_SPLIT.split(selector.trim());
        for (String part : parts) {
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c == '#') {
                    idCount++;
                } else if (c == '.' || c == '[') {
                    classCount++;
                } else if (c == ':') {
                    // 擬似要素(::)は type、擬似クラス(:)は class とカウントする
                    if (i + 1 < part.length() && part.charAt(i + 1) == ':') {
                        typeCount++;
                        i++; // '::' を読み飛ばす
                    } else {
                        // :not(), :is() などの内部の再帰的計算は今回は省略し、単純な擬似クラスとして1カウント
                        classCount++;
                    }
                }
            }
            String cleanPart = SPEC_CLEAN_PATTERN.matcher(part).replaceAll("");
            if (!cleanPart.isEmpty() && !cleanPart.equals("*")) {
                typeCount++;
            }
        }
        return new Specificity(idCount, classCount, typeCount, originScore);
    }

    // 後方互換用: 旧仕様の int スコア（origin = specificity加算値）を返す
    public int toLegacyScore() {
        return (origin * 10000) + (id * 100) + (clazz * 10) + type;
    }

    @Override
    public int compareTo(Specificity other) {
        if (this.origin != other.origin) return Integer.compare(this.origin, other.origin);
        if (this.id != other.id) return Integer.compare(this.id, other.id);
        if (this.clazz != other.clazz) return Integer.compare(this.clazz, other.clazz);
        return Integer.compare(this.type, other.type);
    }
}