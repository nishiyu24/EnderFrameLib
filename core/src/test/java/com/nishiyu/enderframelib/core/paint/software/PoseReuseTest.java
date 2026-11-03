package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 姿勢が動いていない項目の姿勢を、前のフレームから使い回しても（{@link PaintSceneDriver#reuseIdlePoses}）、毎フレーム作り直した
 * ときと同じ絵になること。同じ項目リストを、時刻を進めながら何度も描く（窓の描画と同じ）。色だけの {@code @keyframes}・変形・
 * 不透明度・入れ子（親が動き子の色が動く・親が止まり子が動く）・有限のアニメーションの終わりを含む。
 */
public class PoseReuseTest {

    private static final String HTML = "<html><body style='margin:0;background:#223'>"
            + "<style>"
            + "@keyframes hue { from { background-color: #f00 } 50% { background-color: #0f0 } to { background-color: #00f } }"
            + "@keyframes txt { from { color: #fff } to { color: #f80 } }"
            + "@keyframes spin { to { transform: rotate(180deg) } }"
            + "@keyframes fade { from { opacity: .2 } to { opacity: 1 } }"
            + "@keyframes both { from { transform: translateX(0); background-color: #f0f } to { transform: translateX(30px); background-color: #0ff } }"
            + ".b { position:absolute; width:40px; height:30px }"
            + ".c { animation: hue 2s linear infinite }"
            + ".t { animation: txt 1s ease-in-out infinite alternate; font-size:16px }"
            + ".r { animation: spin 3s linear infinite; background:#c33 }"
            + ".f { animation: fade 1.5s linear infinite alternate; background:#3c3 }"
            + ".x { animation: both 2s linear infinite alternate }"
            + ".once { animation: hue 1s linear 1 forwards }"
            + ".p { animation: spin 4s linear infinite; background:#555; width:120px; height:80px; position:absolute }"
            + "</style>"
            + "<div class='b c' style='left:5px;top:5px'></div>"
            + "<div class='b t' style='left:55px;top:5px;background:#444'>Hi</div>"
            + "<div class='b r' style='left:105px;top:5px'></div>"
            + "<div class='b f' style='left:155px;top:5px'></div>"
            + "<div class='b x' style='left:5px;top:50px'></div>"
            + "<div class='b once' style='left:105px;top:50px'></div>"
            + "<div class='b' style='left:155px;top:50px;background:#888'></div>"
            // 親が回り、子の色が動く／親が止まり、子が回る
            + "<div class='p' style='left:20px;top:95px'><div class='b c' style='left:10px;top:10px'></div></div>"
            + "<div class='b' style='left:160px;top:100px;width:60px;height:60px;background:#357'><div class='b r' style='left:10px;top:10px;width:20px;height:20px'></div>"
            + "<div class='b t' style='left:30px;top:30px;width:25px;height:25px;background:#222'>Q</div></div>"
            + "</body></html>";

    @AfterEach
    public void restore() {
        PaintSceneDriver.reuseIdlePoses = true;
    }

    private static List<int[]> frames(List<DisplayItem> items, boolean reuse) {
        PaintSceneDriver.reuseIdlePoses = reuse;
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(240, 180, 1f)) {
            List<int[]> out = new ArrayList<>();
            long t = 50_000;
            for (int i = 0; i < 70; i++) { // 同じリストを、不揃いな時間の刻みで描き続ける
                backend.render(items, t);
                out.add(backend.pixmap().pixels().clone());
                t += 17 + (i % 5) * 23;
            }
            return out;
        }
    }

    @Test
    public void reusingPosesDrawsTheSamePicture() {
        // 窓と同じ不変のリスト（バックエンドは、不変のリストが続く間だけ姿勢などを持ち回す）
        List<DisplayItem> items = List.copyOf(SoftwareRendering.displayList(HTML, 240, 180));
        List<int[]> off = frames(items, false);
        List<int[]> on = frames(items, true);
        for (int i = 0; i < off.size(); i++) assertArrayEquals(off.get(i), on.get(i), "フレーム " + i);
    }

    @Test
    public void theAnimationsReallyChangeThePicture() {
        // 窓と同じ不変のリスト（バックエンドは、不変のリストが続く間だけ姿勢などを持ち回す）
        List<DisplayItem> items = List.copyOf(SoftwareRendering.displayList(HTML, 240, 180));
        List<int[]> f = frames(items, false);
        long distinct = f.stream().map(java.util.Arrays::hashCode).distinct().count();
        assertTrue(distinct > 40, "絵がほとんど変わらない: " + distinct);
    }
}
