package com.nishiyu.enderframelib.client.renderer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 動画の固有サイズの登録がレイアウトのやり直しを起動するのは、必ずクライアントスレッドの上でなければならない。
 * デコード用スレッドから直接 setSize を呼ぶと、描画中のボックスツリーを書き換えて
 * ConcurrentModificationException でクラッシュした。
 */
public class VideoSizePublishThreadTest {

    private Consumer<Runnable> savedExecutor;
    private ExecutorService decodeThread;
    private ExecutorService clientThread;

    @BeforeEach
    void setUp() {
        savedExecutor = WebMediaManager.clientThreadExecutor;
        decodeThread = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-decode"));
        clientThread = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-client"));
        WebMediaManager.clientThreadExecutor = clientThread::execute;
    }

    @AfterEach
    void tearDown() {
        WebMediaManager.clientThreadExecutor = savedExecutor;
        decodeThread.shutdownNow();
        clientThread.shutdownNow();
    }

    @Test
    @DisplayName("デコード用スレッドから公開しても、リフロー通知はクライアントスレッドで走る")
    void reflowRunsOnClientThread() throws Exception {
        String url = "https://example.test/vsize-thread-" + System.nanoTime() + ".gif";
        AtomicReference<String> reflowThread = new AtomicReference<>();
        Runnable listener = () -> reflowThread.compareAndSet(null, Thread.currentThread().getName());
        WebMediaManager.ClientImageRegistry.addReflowListener(listener);
        try {
            decodeThread.submit(() -> WebMediaManager.publishVideoSize(url, 64, 48)).get(5, TimeUnit.SECONDS);
            // クライアントスレッドの処理が終わるのを待つ
            clientThread.submit(() -> { }).get(5, TimeUnit.SECONDS);

            assertEquals("test-client", reflowThread.get(), "リフローはクライアントスレッドで通知される");
            assertArrayEquals(new int[]{64, 48}, WebMediaManager.ClientImageRegistry.getSize(url));
        } finally {
            WebMediaManager.ClientImageRegistry.removeReflowListener(listener);
        }
    }

    @Test
    @DisplayName("呼び出したスレッドでは何も登録せず、クライアントスレッドへ渡すだけ")
    void callerThreadDoesNotTouchRegistry() throws Exception {
        String url = "https://example.test/vsize-deferred-" + System.nanoTime() + ".gif";
        AtomicReference<Runnable> deferred = new AtomicReference<>();
        WebMediaManager.clientThreadExecutor = deferred::set;

        decodeThread.submit(() -> WebMediaManager.publishVideoSize(url, 10, 20)).get(5, TimeUnit.SECONDS);

        assertTrue(deferred.get() != null, "処理がクライアントスレッドへ渡されている");
        assertNull(WebMediaManager.ClientImageRegistry.getSize(url), "渡した処理が走るまでは登録されない");

        deferred.get().run();
        assertArrayEquals(new int[]{10, 20}, WebMediaManager.ClientImageRegistry.getSize(url));
    }
}
