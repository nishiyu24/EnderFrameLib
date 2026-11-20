package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.PaintSceneDriver.Group;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver.Resolved;

/**
 * 姿勢の使い回し（{@code Shortcut.REUSE_IDLE_POSES}）の検証（ENGINE_REARCHITECTURE_PLAN C0 ②。{@code Shortcut.VERIFY_MEMO}）。
 * 使い回した前回の姿勢を、作り直した姿勢と比べる。
 */
final class PoseVerifier {
    private PoseVerifier() {}

    /** 変形・不透明度が恒等（葉の項目なら姿勢の作り直しの結果が親の姿勢と同じ値になる）。 */
    static boolean identityGeometry(AnimatedValues a) {
        return a.opacity() == 1f && a.tx() == 0f && a.ty() == 0f && a.sx() == 1f && a.sy() == 1f && a.rot() == 0f && a.skew() == 0f;
    }

    /** 食い違えば {@link AssertionError}。{@code anchor}（リスト上の位置）は項目の移動で変わるので比べない。 */
    static void check(String itemId, Resolved reused, Resolved fresh) {
        boolean same = fresh.pose().equals(reused.pose()) && fresh.opacity() == reused.opacity()
                && fresh.residual() == reused.residual() && fresh.groups().size() == reused.groups().size();
        for (int g = 0; same && g < fresh.groups().size(); g++) {
            Group a = fresh.groups().get(g), b = reused.groups().get(g);
            same = a.id().equals(b.id()) && a.opacity() == b.opacity() && a.pose().equals(b.pose());
        }
        if (!same) {
            throw new AssertionError("姿勢の使い回しが古い: 項目 " + itemId + " 使い回し=" + reused.pose() + " 作り直し=" + fresh.pose()
                    + " 不透明度 " + reused.opacity() + "/" + fresh.opacity() + " 残差 " + reused.residual() + "/" + fresh.residual()
                    + " 群 " + reused.groups().size() + "/" + fresh.groups().size());
        }
    }
}
