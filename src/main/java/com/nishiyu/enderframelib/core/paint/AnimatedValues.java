package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.AnimationTiming;
import com.nishiyu.enderframelib.core.paint.model.EasingFunction;
import com.nishiyu.enderframelib.core.paint.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;

public class AnimatedValues {
    public float[] clipData;
    public float[] radiusData;
    public float rangeVal, rangeMin, rangeMax, circleThickness;
    public int[] circleColors;
    private float opacity, tx, ty, sx, sy, rot;
    private int color, bgColor;

    public void set(float op, float tx, float ty, float sx, float sy, float rot, int col, int bgCol) {
        this.opacity = op;
        this.tx = tx;
        this.ty = ty;
        this.sx = sx;
        this.sy = sy;
        this.rot = rot;
        this.color = col;
        this.bgColor = bgCol;
    }

    public float opacity() { return opacity; }
    public float tx() { return tx; }
    public float ty() { return ty; }
    public float sx() { return sx; }
    public float sy() { return sy; }
    public float rot() { return rot; }
    public int color() { return color; }
    public int bgColor() { return bgColor; }
}
