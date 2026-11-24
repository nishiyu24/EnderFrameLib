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
 * {@link Token} の値の組（ダーク・ライトなど）。要素に付けると、その要素と子孫でトークンがこの値になる
 * （中身はカスタムプロパティを宣言するクラス {@code theme-<name>}）。状態で切り替えるときは {@link #select}。
 *
 * <pre>{@code
 * static final Theme DARK = Theme.of("dark", SURFACE.is(Color.hex(0x1e293b)), TEXT.is(Color.hex(0xf8fafc)));
 * Theme.select(darkMode.map(d -> d ? DARK : LIGHT)).apply(body);
 * }</pre>
 */
@ApiStatus.AvailableSince("1.0")
public final class Theme implements Prop {

    private static final Pattern NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9_-]*");

    private final String className;
    private final List<StyleRule> rules;

    private Theme(String className, List<StyleRule> rules) {
        this.className = className;
        this.rules = rules;
    }

    /** @param values {@link Token#is} で作った、トークンの値の宣言 */
    public static Theme of(String name, Declaration... values) {
        if (name == null || !NAME.matcher(name).matches()) throw new IllegalArgumentException("テーマ名が不正: " + name);
        String cls = "theme-" + name;
        List<StyleRule.Declaration> decls = new ArrayList<>(values.length);
        for (Declaration d : values) {
            if (!d.property().startsWith("--")) {
                throw new IllegalArgumentException("テーマ '" + name + "' に渡せるのはトークンの値（Token.is）だけ: " + d);
            }
            decls.add(d.toRule(cls));
        }
        return new Theme(cls, List.of(new StyleRule("." + cls, decls)));
    }

    /** 要素に付くクラス名（{@code theme-<name>}）。 */
    public String className() {
        return className;
    }

    public void adopt(Document document) {
        Objects.requireNonNull(document, "document").adoptStyleRules("theme:" + className, rules);
    }

    @Override
    public void apply(@NotNull Element element) {
        adopt(element.getOwnerDocument());
        element.getClassList().add(className);
    }

    /** 状態が指すテーマを要素に付ける（変わったら前のテーマのクラスを外して付け替える）。 */
    public static Prop select(State<Theme> theme) {
        Objects.requireNonNull(theme, "theme");
        return el -> {
            Theme[] current = {null};
            Runnable sync = () -> {
                Theme next = Objects.requireNonNull(theme.get(), "theme");
                if (next == current[0]) return;
                next.adopt(el.getOwnerDocument());
                if (current[0] != null) el.getClassList().remove(current[0].className);
                el.getClassList().add(next.className);
                current[0] = next;
            };
            sync.run();
            theme.onChange(v -> sync.run());
        };
    }

    @Override
    public String toString() {
        return "Theme(" + className + ")";
    }
}
