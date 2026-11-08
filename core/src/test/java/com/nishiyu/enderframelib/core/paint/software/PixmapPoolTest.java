package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.raster.Pixmap;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PixmapPoolTest {

    @Test
    void takesNewWhenEmptyAndReusesReturnedOnesLastInFirstOut() {
        PixmapPool pool = new PixmapPool(8, 4);
        Pixmap a = pool.take(), b = pool.take();
        assertNotSame(a, b);
        assertEquals(8, a.width());
        assertEquals(4, a.height());
        assertEquals(2, pool.createdCount());
        assertEquals(0, pool.idleCount());
        pool.give(a);
        pool.give(b);
        assertEquals(2, pool.idleCount());
        assertSame(b, pool.take(), "直近に返したものから再利用する（キャッシュに温かい方）");
        assertSame(a, pool.take());
        assertEquals(2, pool.createdCount(), "再利用では作らない");
    }

    @Test
    void rejectsPixmapsOfAnotherSize() {
        PixmapPool pool = new PixmapPool(8, 4);
        assertThrows(IllegalArgumentException.class, () -> pool.give(new Pixmap(8, 5)));
        assertEquals(0, pool.idleCount());
    }

    @Test
    void nestedLayersRecycleTheSamePixmapsAcrossFrames() {
        PixmapPool pool = new PixmapPool(16, 16);
        for (int frame = 0; frame < 50; frame++) {
            Pixmap outer = pool.take(), inner = pool.take();
            pool.give(inner);
            pool.give(outer);
        }
        assertEquals(2, pool.createdCount(), "50 フレーム入れ子の層を開閉しても作るのは 2 枚だけ");
    }

    @Test
    void backendHoldsNoHandWrittenPoolLogic() throws Exception {
        String src = Files.readString(Path.of("core/src/main/java/com/nishiyu/enderframelib/core/paint/software/SoftwareRenderBackend.java"));
        assertFalse(src.contains("layerPool.pollFirst"), "層の貸し借りは PixmapPool に集約済み（§9.5.19）");
        assertFalse(src.contains("layerPool.addFirst"));
    }
}
