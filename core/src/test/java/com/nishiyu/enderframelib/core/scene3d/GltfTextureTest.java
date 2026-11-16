package com.nishiyu.enderframelib.core.scene3d;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.paint.software.SoftwareScene3DRasterizer;
import com.nishiyu.enderframelib.core.raster.PngEncoder;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.scene3d.GltfBinaryLoader.GltfException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** X3-2: glTF の baseColorTexture（GLB に埋め込んだ PNG・{@code data:} URI の PNG）を材質の画像として読む。 */
class GltfTextureTest {

    private static byte[] floats(float... v) {
        ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) b.putFloat(f);
        return b.array();
    }

    private static byte[] u16(int... v) {
        ByteBuffer b = ByteBuffer.allocate(v.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i : v) b.putShort((short) i);
        return b.array();
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }

    private static byte[] glb(String json, byte[] bin) {
        byte[] j = json.getBytes(StandardCharsets.UTF_8);
        int jl = (j.length + 3) & ~3, bl = (bin.length + 3) & ~3;
        ByteBuffer b = ByteBuffer.allocate(12 + 8 + jl + 8 + bl).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46546C67).putInt(2).putInt(b.capacity());
        b.putInt(jl).putInt(0x4E4F534A).put(j);
        for (int i = j.length; i < jl; i++) b.put((byte) 0x20);
        b.putInt(bl).putInt(0x004E4942).put(bin);
        return b.array();
    }

    /** 左半分が赤・右半分が青の 2×1 の PNG。 */
    private static byte[] redBluePng() throws IOException {
        return PngEncoder.encode(2, 1, new int[]{0xFFFF0000, 0xFF0000FF});
    }

    /**
     * 原点中心の 2×2 の正方形（+Z 向き）に UV を付けた .glb。{@code imageInView} なら PNG を bufferView に、そうでなければ
     * {@code data:} URI に入れる。{@code samplerJson} は textures[0] に付ける sampler の定義（空なら sampler なし）。
     */
    private static byte[] quadGlb(boolean imageInView, String samplerJson, String texCoordJson) throws IOException {
        return quadGlb(imageInView, samplerJson, texCoordJson, null);
    }

    private static byte[] quadGlb(boolean imageInView, String samplerJson, String texCoordJson, String imageJson) throws IOException {
        byte[] png = redBluePng();
        byte[] pos = floats(-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0);
        byte[] uv = floats(0, 0, 1, 0, 1, 1, 0, 1);
        byte[] idx = u16(0, 1, 2, 0, 2, 3);
        byte[] bin = cat(pos, uv, idx, imageInView ? png : new byte[0]);
        String image = imageInView
                ? "{\"bufferView\":3,\"mimeType\":\"image/png\"}"
                : "{\"uri\":\"data:image/png;base64," + Base64.getEncoder().encodeToString(png) + "\"}";
        if (imageJson != null) image = imageJson;
        String views = "{\"buffer\":0,\"byteOffset\":0,\"byteLength\":48},{\"buffer\":0,\"byteOffset\":48,\"byteLength\":32},"
                + "{\"buffer\":0,\"byteOffset\":80,\"byteLength\":12}"
                + (imageInView ? ",{\"buffer\":0,\"byteOffset\":92,\"byteLength\":" + png.length + "}" : "");
        String json = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":" + bin.length + "}],"
                + "\"bufferViews\":[" + views + "],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":4,\"type\":\"VEC3\",\"min\":[-1,-1,0],\"max\":[1,1,0]},"
                + "{\"bufferView\":1,\"componentType\":5126,\"count\":4,\"type\":\"VEC2\"},"
                + "{\"bufferView\":2,\"componentType\":5123,\"count\":6,\"type\":\"SCALAR\"}],"
                + "\"images\":[" + image + "],"
                + (samplerJson.isEmpty() ? "" : "\"samplers\":[" + samplerJson + "],")
                + "\"textures\":[{\"source\":0" + (samplerJson.isEmpty() ? "" : ",\"sampler\":0") + "}],"
                + "\"materials\":[{\"pbrMetallicRoughness\":{\"baseColorTexture\":{\"index\":0" + texCoordJson + "}},"
                + "\"extensions\":{\"KHR_materials_unlit\":{}}}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0,\"TEXCOORD_0\":1},\"indices\":2,\"material\":0}]}],"
                + "\"nodes\":[{\"mesh\":0}],\"scenes\":[{\"nodes\":[0]}],\"scene\":0,"
                + "\"extensionsUsed\":[\"KHR_materials_unlit\"]}";
        return glb(json, bin);
    }

    private static MaterialDesc firstMaterial(Scene3DWorld w, GltfBinaryLoader.Result r) {
        return w.materials().get(w.arena().renderRef().get(r.rootNodes()[0] * Scene3DArena.RENDER_STRIDE + Scene3DArena.RENDER_MATERIAL_ID));
    }

    private static Scene3DWorld camera(Scene3DWorld w) {
        w.clearColorArgb(0xFF101820);
        w.camera().lookAt(0, 0, 3.2f, 0, 0, 0, 0, 1, 0);
        w.camera().setPerspective((float) Math.toRadians(45), 1f, 0.1f, 50f);
        return w;
    }

    @Test
    void anImageInTheGlbBinaryBecomesTheMaterialsBaseColorMap() throws IOException {
        Scene3DWorld w = new Scene3DWorld();
        MaterialDesc m = firstMaterial(w, GltfBinaryLoader.load(quadGlb(true, "", ""), w));
        assertNotNull(m.baseColorMap());
        assertEquals(2, m.baseColorMap().width());
        assertEquals(1, m.baseColorMap().height());
        assertEquals(0xFFFF0000, m.baseColorMap().argb(0, 0));
        assertEquals(0xFF0000FF, m.baseColorMap().argb(1, 0));
        assertTrue(m.mapRepeat(), "sampler が無いときの既定は REPEAT");
    }

    @Test
    void aDataUriImageIsReadToo() throws IOException {
        Scene3DWorld w = new Scene3DWorld();
        MaterialDesc m = firstMaterial(w, GltfBinaryLoader.load(quadGlb(false, "", ""), w));
        assertNotNull(m.baseColorMap());
        assertEquals(0xFF0000FF, m.baseColorMap().argb(1, 0));
    }

    @Test
    void theSamplersWrapModeIsKept() throws IOException {
        Scene3DWorld w = new Scene3DWorld();
        MaterialDesc clamp = firstMaterial(w, GltfBinaryLoader.load(quadGlb(true, "{\"wrapS\":33071,\"wrapT\":33071}", ""), w));
        assertFalse(clamp.mapRepeat());
        Scene3DWorld w2 = new Scene3DWorld();
        MaterialDesc mixed = firstMaterial(w2, GltfBinaryLoader.load(quadGlb(true, "{\"wrapS\":10497,\"wrapT\":33648}", ""), w2));
        assertFalse(mixed.mapRepeat(), "片方でも REPEAT でなければ張り付き");
        Scene3DWorld w3 = new Scene3DWorld();
        MaterialDesc rep = firstMaterial(w3, GltfBinaryLoader.load(quadGlb(true, "{\"wrapS\":10497,\"wrapT\":10497}", ""), w3));
        assertTrue(rep.mapRepeat());
    }

    @Test
    void aSecondUvSetIsLoadedWithoutAnImage() throws IOException {
        Scene3DWorld w = new Scene3DWorld();
        MaterialDesc m = firstMaterial(w, GltfBinaryLoader.load(quadGlb(true, "", ",\"texCoord\":1"), w));
        assertNull(m.baseColorMap(), "TEXCOORD_1 は読まない");
    }

    @Test
    void anUndecodableImageLoadsTheModelWithoutAnImage() throws IOException {
        byte[] good = quadGlb(true, "", "");
        // PNG の先頭を壊す（bufferView の最後の PNG 領域 = ファイルの末尾付近）
        byte[] bad = good.clone();
        for (int i = bad.length - 40; i < bad.length - 4; i++) bad[i] = 0x55;
        Scene3DWorld w = new Scene3DWorld();
        GltfBinaryLoader.Result r = GltfBinaryLoader.load(bad, w);
        assertEquals(1, r.rootNodes().length);
        assertNull(firstMaterial(w, r).baseColorMap());
    }

    @Test
    void anExternalImageFileIsRejectedAndLeavesTheWorldUntouched() throws IOException {
        Scene3DWorld w = new Scene3DWorld();
        int materialsBefore = w.materials().count();
        byte[] doc = quadGlb(true, "", "", "{\"uri\":\"tex.png\"}");
        assertThrows(GltfException.class, () -> GltfBinaryLoader.load(doc, w));
        assertEquals(materialsBefore, w.materials().count(), "失敗したら world は変わらない");
    }

    @Test
    void theImageIsDrawnOnTheModelBySoftwareRendering() throws IOException {
        Scene3DWorld w = camera(new Scene3DWorld());
        GltfBinaryLoader.load(quadGlb(true, "", ""), w);
        Pixmap p = SoftwareScene3DRasterizer.render(w, 64, 64);
        int left = p.get(22, 32), right = p.get(42, 32);
        assertTrue(((left >> 16) & 0xFF) > 200 && (left & 0xFF) < 60, "左は赤: " + Integer.toHexString(left));
        assertTrue((right & 0xFF) > 200 && ((right >> 16) & 0xFF) < 60, "右は青: " + Integer.toHexString(right));
    }

    @Test
    void twoMaterialsSharingOneImageShareOneDecodedTexture() throws IOException {
        byte[] png = redBluePng();
        byte[] pos = floats(-1, -1, 0, 1, -1, 0, 1, 1, 0);
        byte[] uv = floats(0, 0, 1, 0, 1, 1);
        byte[] bin = cat(pos, uv, u16(0, 1, 2), new byte[2], png);
        String json = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":" + bin.length + "}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36},{\"buffer\":0,\"byteOffset\":36,\"byteLength\":24},"
                + "{\"buffer\":0,\"byteOffset\":60,\"byteLength\":6},{\"buffer\":0,\"byteOffset\":68,\"byteLength\":" + png.length + "}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\",\"min\":[-1,-1,0],\"max\":[1,1,0]},"
                + "{\"bufferView\":1,\"componentType\":5126,\"count\":3,\"type\":\"VEC2\"},"
                + "{\"bufferView\":2,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"}],"
                + "\"images\":[{\"bufferView\":3,\"mimeType\":\"image/png\"}],\"textures\":[{\"source\":0}],"
                + "\"materials\":[{\"pbrMetallicRoughness\":{\"baseColorTexture\":{\"index\":0}}},"
                + "{\"pbrMetallicRoughness\":{\"baseColorTexture\":{\"index\":0},\"metallicFactor\":0}}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0,\"TEXCOORD_0\":1},\"indices\":2,\"material\":0},"
                + "{\"attributes\":{\"POSITION\":0,\"TEXCOORD_0\":1},\"indices\":2,\"material\":1}]}],"
                + "\"nodes\":[{\"mesh\":0}],\"scenes\":[{\"nodes\":[0]}],\"scene\":0}";
        Scene3DWorld w = new Scene3DWorld();
        GltfBinaryLoader.load(glb(json, bin), w);
        MaterialDesc a = w.materials().get(1), b = w.materials().get(2);
        assertNotNull(a.baseColorMap());
        assertSame(a.baseColorMap(), b.baseColorMap(), "同じ画像は 1 回だけ復号して共有する");
    }
}
