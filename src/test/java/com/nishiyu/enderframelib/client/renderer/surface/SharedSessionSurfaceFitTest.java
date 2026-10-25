package com.nishiyu.enderframelib.client.renderer.surface;

import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import net.minecraft.world.item.ItemDisplayContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同じセッションを縦横比の違う面（ブロック・ホログラム・手持ちアイテム）で共有しても、レイアウトの大きさは
 * セッションが決め、どの面もそれを縦横比を保って収めるだけであること（TEST_SPEC_SURFACE C-1）。
 * 面を描く側がセッションを作り直したり、後から描いた面の大きさで先の面のレイアウトを潰したりしない。
 */
public class SharedSessionSurfaceFitTest {

    private static final String SESSION = "shared_fit_session";

    @AfterEach
    void tearDown() {
        ClientWebManager.clearSession(SESSION);
    }

    @Test
    @DisplayName("縦横比の異なる面にフィットしても、セッションの解像度は変わらず内容の縦横比が保たれる")
    void fitDoesNotTouchSessionAndKeepsAspect() {
        ClientWebManager.clearSession(SESSION);
        SessionContext session = ClientWebManager.getOrCreateSession(SESSION);
        session.loadLocalHtml("<div id='a'>x</div>", 320, 240);

        float[][] faces = {{4f, 1f}, {1f, 3f}, {2f, 1.5f}, {3f, 3f}};
        for (float[] face : faces) {
            SurfaceFit fit = SurfaceGeometry.fit(session, face[0], face[1]);
            SurfaceGeometry content = fit.content();

            assertEquals(320, session.rootW(), "面 " + face[0] + "x" + face[1] + " でセッションの幅が変わらない");
            assertEquals(240, session.rootH(), "面 " + face[0] + "x" + face[1] + " でセッションの高さが変わらない");
            assertEquals(320, content.virtualW());
            assertEquals(240, content.virtualH());
            assertEquals(4f / 3f, content.widthBlocks() / content.heightBlocks(), 1e-4f);
            assertTrue(content.widthBlocks() <= face[0] + 1e-4f && content.heightBlocks() <= face[1] + 1e-4f,
                    "内容が面からはみ出さない: " + face[0] + "x" + face[1]);
            assertEquals(face[0], content.widthBlocks() + 2 * fit.offsetX(), 1e-4f, "左右に均等な余白");
            assertEquals(face[1], content.heightBlocks() + 2 * fit.offsetY(), 1e-4f, "上下に均等な余白");
        }
    }

    @Test
    @DisplayName("手持ちアイテムの一人称は、仕様の最大幅を超えず視界の外へ広がらない")
    void firstPersonWidthIsClamped() {
        ItemSurfaceSpec spec = new ItemSurfaceSpec(320, 240, 0.8f, 0.6f, ItemSurfaceSpec.Anchor.CENTER, 0f);
        assertEquals(0.6f, spec.physicalWidth(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND), 1e-6f);
        assertEquals(0.6f, spec.physicalWidth(ItemDisplayContext.FIRST_PERSON_LEFT_HAND), 1e-6f);
        assertEquals(0.8f, spec.physicalWidth(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND), 1e-6f);
        float[] origin = spec.origin(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND);
        float w = spec.physicalWidth(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND);
        float h = spec.physicalHeight(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND);
        assertTrue(origin[0] >= 0f && origin[0] + w <= 1f, "アイテム空間の 0..1 に収まる(横)");
        assertTrue(origin[1] - h >= 0f && origin[1] <= 1f, "アイテム空間の 0..1 に収まる(縦)");
    }
}
