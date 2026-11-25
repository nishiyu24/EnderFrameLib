package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * M1: {@code PropertyRegistry} の全プロパティが、型付き 6 群のどれか 1 つだけに所属すること
 * （足し忘れ・二重所属は、{@code scripts/GenStyleGroups.java} の再実行忘れとしてここで落ちる）。
 */
class StyleGroupCoverageTest {

    private static final Class<?>[] GROUPS = {InheritedStyle.class, BoxGeomStyle.class, VisualStyle.class,
            FlexGridStyle.class, TableStyle.class, RareStyle.class, InheritedPaintStyle.class};

    private static Set<Integer> idsOf(Class<?> group) throws ReflectiveOperationException {
        Set<Integer> ids = new HashSet<>();
        for (Field f : group.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getName().startsWith("ID_")) {
                f.setAccessible(true);
                ids.add(f.getInt(null));
            }
        }
        return ids;
    }

    @Test
    void everyRegisteredPropertyBelongsToExactlyOneGroup() throws ReflectiveOperationException {
        Set<Integer> expected = new HashSet<>();
        for (PropertyRegistry.Property p : PropertyRegistry.all()) {
            if (p.isShorthand() && p.expander() != null) continue; // 展開されて算出スタイルには残らない
            int id = ComputedStyle.idOf(p.name());
            assertNotEquals(-1, id, p.name());
            expected.add(id);
        }
        Set<Integer> seen = new HashSet<>();
        for (Class<?> g : GROUPS) {
            for (int id : idsOf(g)) {
                assertTrue(seen.add(id), "二重所属: " + ComputedStyle.CssPropertyId.name(id) + " (" + g.getSimpleName() + ")");
            }
        }
        Set<Integer> missing = new HashSet<>(expected);
        missing.removeAll(seen);
        assertTrue(missing.isEmpty(), "どの群にも属さない: " + missing.stream().map(ComputedStyle.CssPropertyId::name).toList());
        seen.removeAll(expected);
        assertTrue(seen.isEmpty(), "レジストリに無い: " + seen);
    }

    @Test
    void inheritedGroupHoldsExactlyTheInheritedProperties() throws ReflectiveOperationException {
        for (Class<?> g : new Class<?>[]{InheritedStyle.class, InheritedPaintStyle.class}) {
            for (int id : idsOf(g)) {
                assertTrue(PropertyRegistry.isInherited(ComputedStyle.CssPropertyId.name(id)), ComputedStyle.CssPropertyId.name(id));
            }
        }
        for (Class<?> g : GROUPS) {
            if (g == InheritedStyle.class || g == InheritedPaintStyle.class) continue;
            for (int id : idsOf(g)) {
                assertFalse(PropertyRegistry.isInherited(ComputedStyle.CssPropertyId.name(id)),
                        ComputedStyle.CssPropertyId.name(id) + " は継承するのに " + g.getSimpleName() + " にある");
            }
        }
    }

    @Test
    void generatedGroupsStayUnderTheClassSizeRule() throws java.io.IOException {
        for (Class<?> g : GROUPS) {
            java.nio.file.Path src = java.nio.file.Path.of("core/src/main/java/com/nishiyu/enderframelib/core/css", g.getSimpleName() + ".java");
            assertTrue(java.nio.file.Files.readAllLines(src).size() <= 600, g.getSimpleName());
        }
    }
}
