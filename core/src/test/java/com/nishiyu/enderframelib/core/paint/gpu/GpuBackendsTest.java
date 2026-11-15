package com.nishiyu.enderframelib.core.paint.gpu;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.paint.RenderBackend;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** G4: GPU 実装の選択ロジック（ServiceLoader の内側）。偽の実装で、優先順位・絞り込み・フォールバック・失敗の伝え方を確かめる。 */
class GpuBackendsTest {

    private static final GpuBackendProvider.Request REQ = new GpuBackendProvider.Request(64, 48, 1f, null, null, null, 0);

    /** 実際のバックエンドは作らず、作った印の {@link RenderBackend}（プロキシ）を返す偽物。 */
    private static final class Fake implements GpuBackendProvider {
        final String id;
        final GpuApiKind api;
        final int priority;
        final boolean available;
        final RuntimeException failure;
        final List<String> log;

        Fake(String id, GpuApiKind api, int priority, boolean available, RuntimeException failure, List<String> log) {
            this.id = id;
            this.api = api;
            this.priority = priority;
            this.available = available;
            this.failure = failure;
            this.log = log;
        }

        @Override public String id() { return id; }
        @Override public GpuApiKind api() { return api; }
        @Override public int priority() { return priority; }
        @Override public boolean available() { return available; }

        @Override
        public RenderBackend create(Request r) {
            log.add(id);
            if (failure != null) throw failure;
            return (RenderBackend) java.lang.reflect.Proxy.newProxyInstance(RenderBackend.class.getClassLoader(),
                    new Class<?>[]{RenderBackend.class}, (p, m, a) -> null);
        }

        @Override public long colorTexture(RenderBackend backend) { return 7; }
    }

    @Test
    void providersAreSortedByPriorityThenId() {
        List<String> log = new ArrayList<>();
        var sorted = GpuBackends.providers(List.of(
                new Fake("vulkan", GpuApiKind.VULKAN, 20, true, null, log),
                new Fake("b", GpuApiKind.GL, 10, true, null, log),
                new Fake("a", GpuApiKind.GL, 10, true, null, log)));
        assertEquals(List.of("a", "b", "vulkan"), sorted.stream().map(GpuBackendProvider::id).toList());
    }

    @Test
    void autoPicksTheFirstUsableByPriority() {
        List<String> log = new ArrayList<>();
        var c = GpuBackends.create(null, REQ, List.of(
                new Fake("gl", GpuApiKind.GL, 10, true, null, log), new Fake("vk", GpuApiKind.VULKAN, 20, true, null, log)));
        assertEquals("gl", c.provider().id());
        assertEquals(List.of("gl"), log, "使える実装が見つかったら、あとの実装は作らない");
    }

    @Test
    void autoSkipsUnavailableAndFallsBackWhenCreationFails() {
        List<String> log = new ArrayList<>();
        var c = GpuBackends.create(null, REQ, List.of(
                new Fake("gl", GpuApiKind.GL, 10, false, null, log),
                new Fake("vk1", GpuApiKind.VULKAN, 20, true, new IllegalStateException("no device"), log),
                new Fake("vk2", GpuApiKind.VULKAN, 30, true, null, log)));
        assertEquals("vk2", c.provider().id());
        assertEquals(List.of("vk1", "vk2"), log, "使えない実装は create も呼ばない・失敗した実装の次へ進む");
    }

    @Test
    void explicitApiOnlyTriesThatApi() {
        List<String> log = new ArrayList<>();
        var c = GpuBackends.create(GpuApiKind.VULKAN, REQ, List.of(
                new Fake("gl", GpuApiKind.GL, 10, true, null, log), new Fake("vk", GpuApiKind.VULKAN, 20, true, null, log)));
        assertEquals("vk", c.provider().id());
        assertEquals(List.of("vk"), log);
    }

    @Test
    void explicitApiDoesNotFallBackToAnotherApi() {
        List<String> log = new ArrayList<>();
        var e = assertThrows(IllegalStateException.class, () -> GpuBackends.create(GpuApiKind.VULKAN, REQ, List.of(
                new Fake("gl", GpuApiKind.GL, 10, true, null, log),
                new Fake("vk", GpuApiKind.VULKAN, 20, true, new IllegalStateException("no device"), log))));
        assertEquals(List.of("vk"), log, "Vulkan を頼まれたら GL へ逃げない");
        assertTrue(e.getMessage().contains("vk"));
        assertEquals("no device", e.getCause().getMessage(), "原因を握りつぶさない");
    }

    @Test
    void failuresAreChainedAsSuppressed() {
        List<String> log = new ArrayList<>();
        var e = assertThrows(IllegalStateException.class, () -> GpuBackends.create(null, REQ, List.of(
                new Fake("a", GpuApiKind.GL, 10, true, new IllegalStateException("first"), log),
                new Fake("b", GpuApiKind.VULKAN, 20, true, new IllegalStateException("second"), log))));
        assertEquals("first", e.getCause().getMessage());
        assertEquals(1, e.getSuppressed().length);
        assertEquals("second", e.getSuppressed()[0].getMessage());
    }

    @Test
    void missingModuleNamesTheModuleToAdd() {
        var none = assertThrows(IllegalStateException.class, () -> GpuBackends.create(GpuApiKind.GL, REQ, List.of()));
        assertTrue(none.getMessage().contains("core-gl"), none.getMessage());
        var vk = assertThrows(IllegalStateException.class, () -> GpuBackends.create(GpuApiKind.VULKAN, REQ, List.of()));
        assertTrue(vk.getMessage().contains("core-vulkan"), vk.getMessage());
        var unavailable = assertThrows(IllegalStateException.class, () -> GpuBackends.create(GpuApiKind.GL, REQ,
                List.of(new Fake("gl", GpuApiKind.GL, 10, false, null, new ArrayList<>()))));
        assertTrue(unavailable.getMessage().contains("使えません"), unavailable.getMessage());
    }
}
