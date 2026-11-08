package com.nishiyu.enderframelib.core.paint.software;

import java.util.List;

record Clip(List<ClipPart> parts, int x0, int y0, int x1, int y1,
                    double ix0, double iy0, double ix1, double iy1) {
    boolean empty() {
        return x1 <= x0 || y1 <= y0;
    }

    float coverage(double dx, double dy) {
        if (dx >= ix0 && dx <= ix1 && dy >= iy0 && dy <= iy1) return 1f; // 全部の内側（軸平行のときだけ非空）
        float c = 1f;
        for (int i = 0; i < parts.size(); i++) {
            c *= parts.get(i).coverage(dx, dy);
            if (c <= 0f) return 0f;
        }
        return c;
    }
}
