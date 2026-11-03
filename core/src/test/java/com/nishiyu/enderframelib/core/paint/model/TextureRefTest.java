package com.nishiyu.enderframelib.core.paint.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureRefTest {

    @Test
    void roundTripsThroughSrc() {
        TextureRef plain = new TextureRef(42, 64, 32);
        assertEquals("texture:42@64x32", plain.src());
        assertEquals(plain, TextureRef.parse(plain.src()));

        TextureRef full = new TextureRef(7, 256, 256, 0.25f, 0.5f, 0.5f, 0.75f, true);
        assertEquals(full, TextureRef.parse(full.src()));
        assertEquals(64f, full.regionWidth(), 1e-4);
        assertEquals(64f, full.regionHeight(), 1e-4);
    }

    @Test
    void rejectsMalformedOrInvalidReferences() {
        assertNull(TextureRef.parse("data:image/png;base64,AAAA"));
        assertNull(TextureRef.parse(null));
        assertNull(TextureRef.parse("texture:abc@4x4"));
        assertNull(TextureRef.parse("texture:5"));
        assertNull(TextureRef.parse("texture:5@4x"));
        assertNull(TextureRef.parse("texture:0@4x4"), "ハンドル 0 は無効");
        assertNull(TextureRef.parse("texture:5@4x4?uv=0,0,1"), "範囲は 4 値");
        assertNull(TextureRef.parse("texture:5@4x4?uv=0.5,0,0.5,1"), "空の範囲");
        assertNull(TextureRef.parse("texture:5@4x4?uv=0,0,2,1"), "範囲が 1 を超える");
        assertTrue(TextureRef.isTextureSrc("texture:5@4x4"));
        assertFalse(TextureRef.isTextureSrc("raster:x"));
    }
}
