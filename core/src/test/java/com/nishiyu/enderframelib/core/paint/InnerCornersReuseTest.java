package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.Corners;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** B9: 枠線の内側の角（円）は、値が同じなら毎フレーム作り直さず同じ実体を返す。 */
class InnerCornersReuseTest {

    @Test
    void sameValuesReuseInstance() {
        BoxDecorationPainter p = new BoxDecorationPainter(800, 600, new AtomicInteger());
        Corners a = p.innerCircular(4, 4, 4, 4);
        assertSame(a, p.innerCircular(4, 4, 4, 4));
        assertEquals(new Corners(4, 4, 4, 4), a);
    }

    @Test
    void differentValuesAndZero() {
        BoxDecorationPainter p = new BoxDecorationPainter(800, 600, new AtomicInteger());
        Corners a = p.innerCircular(4, 4, 4, 4);
        Corners b = p.innerCircular(4, 4, 4, 5);
        assertNotSame(a, b);
        assertEquals(5, b.bottomLeft());
        assertSame(Corners.ZERO, p.innerCircular(0, 0, 0, 0));
        assertEquals(b, p.innerCircular(4, 4, 4, 5));
    }
}
