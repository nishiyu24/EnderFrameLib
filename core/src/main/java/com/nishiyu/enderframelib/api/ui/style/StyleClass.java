package com.nishiyu.enderframelib.api.ui.style;

import com.nishiyu.enderframelib.api.dom.Document;
import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.api.dom.StyleRule;
import com.nishiyu.enderframelib.api.ui.Prop;
import com.nishiyu.enderframelib.api.ui.State;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Java で定義する再利用可能なスタイル（中身は CSS のクラスと、その規則）。要素に {@link Prop} として渡すと、
 * クラスを付け、規則を初めて使う文書へ採用させる（{@link Document#adoptStyleRules}）。
 *
 * <pre>{@code
 * static final StyleClass BTN = StyleClass.of("btn", padding(px(9), px(16)), border(NONE), cursor(Cursor.POINTER));
 * static final StyleClass CHIP = StyleClass.of("chip", BTN, borderRadius(px(999)),
 *         when(ACTIVE, background(ACCENT)), hover(opacity(0.9)));
 *
 * button(CHIP, CHIP.is(ACTIVE, isActive), "Open")
 * }</pre>
 *
 * <p>規則はクラスのセレクタ分の詳細度で、採用順（土台 → 派生）に並ぶので、派生側の宣言が土台に勝つ。
 * 同じ名前で別の規則を採用すると {@link IllegalStateException}。</p>
 */
@ApiStatus.AvailableSince("1.0")
public final class StyleClass implements Prop {

    private static final Pattern NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9_-]*");

    private final String name;
    private final List<StyleClass> bases;
    private final List<StyleRule> rules;

    private StyleClass(String name, List<StyleClass> bases, List<StyleRule> rules) {
        this.name = name;
        this.bases = bases;
        this.rules = rules;
    }

    /**
     * @param name  クラス名（文書の中で一意に。英字で始まり英数字・{@code -}・{@code _}）
     * @param parts {@link Declaration}（このクラスの宣言）・{@link StyleClass}（土台。要素に両方のクラスが付く）・
     *              {@link Variant}（{@link Css#when}・{@link Css#hover} などの条件つきの宣言）
     */
    public static StyleClass of(String name, Object... parts) {
        if (name == null || !NAME.matcher(name).matches()) throw new IllegalArgumentException("スタイル名が不正: " + name);
        List<StyleClass> bases = new ArrayList<>();
        List<StyleRule.Declaration> own = new ArrayList<>();
        List<StyleRule> variants = new ArrayList<>();
        for (Object part : parts) {
            switch (part) {
                case Declaration d -> own.add(d.toRule(name));
                case StyleClass base -> bases.add(base);
                case Variant v -> variants.add(new StyleRule(v.selector(name), toRules(name, v.declarations())));
                case null -> throw new NullPointerException("'" + name + "' の部品に null");
                default -> throw new IllegalArgumentException("'" + name + "' に渡せない部品: " + part.getClass().getName());
            }
        }
        List<StyleRule> rules = new ArrayList<>();
        if (!own.isEmpty()) rules.add(new StyleRule("." + name, own));
        rules.addAll(variants);
        return new StyleClass(name, List.copyOf(bases), List.copyOf(rules));
    }

    private static List<StyleRule.Declaration> toRules(String owner, List<Declaration> decls) {
        List<StyleRule.Declaration> out = new ArrayList<>(decls.size());
        for (Declaration d : decls) out.add(d.toRule(owner));
        return out;
    }

    static String modifierClass(String className, Modifier modifier) {
        return className + "--" + modifier.name();
    }

    /** クラス名。 */
    public String className() {
        return name;
    }

    /** このクラスの規則（土台の規則は含まない）。 */
    public List<StyleRule> rules() {
        return rules;
    }

    /** 状態 {@code modifier} を、{@code on} が true の間だけ要素に付ける（{@link Css#when} の宣言が効く）。 */
    public Prop is(Modifier modifier, State<Boolean> on) {
        Objects.requireNonNull(on, "on");
        String token = modifierClass(name, Objects.requireNonNull(modifier, "modifier"));
        return el -> {
            el.getClassList().toggle(token, Boolean.TRUE.equals(on.get()));
            on.onChange(v -> el.getClassList().toggle(token, Boolean.TRUE.equals(v)));
        };
    }

    /** 状態 {@code modifier} を固定で付ける・付けない。 */
    public Prop is(Modifier modifier, boolean on) {
        String token = modifierClass(name, Objects.requireNonNull(modifier, "modifier"));
        return el -> el.getClassList().toggle(token, on);
    }

    /** 規則を文書へ採用させ（初回だけ）、要素にクラスを付ける。土台のクラスも同じように付ける。 */
    @Override
    public void apply(@NotNull Element element) {
        adopt(element.getOwnerDocument());
        addClasses(element);
    }

    private void addClasses(Element element) {
        for (StyleClass base : bases) base.addClasses(element);
        element.getClassList().add(name);
    }

    /** このクラスと土台の規則を、土台から順に文書へ採用させる。 */
    public void adopt(Document document) {
        Objects.requireNonNull(document, "document");
        for (StyleClass base : bases) base.adopt(document);
        document.adoptStyleRules("class:" + name, rules);
    }

    @Override
    public String toString() {
        return "StyleClass(" + name + ")";
    }
}
