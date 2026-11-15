package com.nishiyu.enderframelib.api.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RecordStateTest {

    record Profile(String name, int age, boolean admin) {
        Profile {
            if (age < 0) throw new IllegalArgumentException("age");
        }
    }

    @Test
    public void fieldWritesRebuildTheRecord() {
        RecordState<Profile> p = RecordState.of(new Profile("a", 3, false));
        State<String> name = p.field("name", String.class);
        State<Integer> age = p.field("age", Integer.class);
        name.set("b");
        age.set(9);
        assertEquals(new Profile("b", 9, false), p.get());
    }

    @Test
    public void wholeRecordUpdatesEveryField() {
        RecordState<Profile> p = RecordState.of(new Profile("a", 3, false));
        State<String> name = p.field("name", String.class);
        State<Boolean> admin = p.field("admin", Boolean.class);
        List<String> seen = new ArrayList<>();
        name.onChange(seen::add);
        p.set(new Profile("z", 3, true));
        assertEquals("z", name.get());
        assertTrue(admin.get());
        assertEquals(List.of("z"), seen);
    }

    @Test
    public void recordInvariantsStillRunAndStateIsKept() {
        RecordState<Profile> p = RecordState.of(new Profile("a", 3, false));
        State<Integer> age = p.field("age", Integer.class);
        assertThrows(IllegalArgumentException.class, () -> age.set(-1));
        assertEquals(3, p.get().age());
    }

    @Test
    public void wrongNameOrTypeIsRejectedUpFront() {
        RecordState<Profile> p = RecordState.of(new Profile("a", 3, false));
        assertThrows(IllegalArgumentException.class, () -> p.field("nope", String.class));
        assertThrows(IllegalArgumentException.class, () -> p.field("age", String.class));
    }

    @Test
    public void combineAndLensPropagateBothWays() {
        State<Integer> a = State.of(1), b = State.of(2);
        State<Integer> sum = State.combine(a, b, Integer::sum);
        a.set(10);
        b.set(5);
        assertEquals(15, sum.get());
        State<String> s = State.of("x:1");
        State<String> head = s.lens(v -> v.split(":")[0], (cur, h) -> h + ":" + cur.split(":")[1]);
        head.set("y");
        assertEquals("y:1", s.get());
        s.set("q:2");
        assertEquals("q", head.get());
    }
}
