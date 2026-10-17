package com.nishiyu.enderframelib.core.css;

/**
 * CSS の詳細度（Specificity）とカスケードの優先順位。
 *
 * 以前は「セレクタ文字列の文字数カウント」と {@code += 10000} のマジック定数だったため、
 * 桁溢れ（クラスが 10 個あると ID 1 個を超える）と {@code ::before} が class より強くなる
 * 逆転が起きていた。ここでは (origin, important, id, class, type) のタプルで比較する。
 *
 * 比較の強さは CSS のカスケード順に従い important → origin → id → class → type。
 */
public record Specificity(CascadeOrigin origin, boolean important, int id, int clazz, int type)
        implements Comparable<Specificity> {

    /** セレクタ文字列から詳細度を計算する（オリジンは作者スタイル）。 */
    public static Specificity calculate(String selector) {
        return calculate(selector, CascadeOrigin.AUTHOR);
    }

    /** セレクタ文字列から詳細度を計算する。 */
    public static Specificity calculate(String selector, CascadeOrigin origin) {
        return SelectorParser.parse(selector, origin, false).specificity();
    }

    public Specificity withOrigin(CascadeOrigin newOrigin) {
        return new Specificity(newOrigin, important, id, clazz, type);
    }

    public Specificity withImportant(boolean newImportant) {
        return new Specificity(origin, newImportant, id, clazz, type);
    }

    @Override
    public int compareTo(Specificity other) {
        if (this.important != other.important) return this.important ? 1 : -1;
        if (this.origin != other.origin) return Integer.compare(this.origin.rank(), other.origin.rank());
        if (this.id != other.id) return Integer.compare(this.id, other.id);
        if (this.clazz != other.clazz) return Integer.compare(this.clazz, other.clazz);
        return Integer.compare(this.type, other.type);
    }
}
