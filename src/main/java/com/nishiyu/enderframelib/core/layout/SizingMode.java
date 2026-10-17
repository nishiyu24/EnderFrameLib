package com.nishiyu.enderframelib.core.layout;

public enum SizingMode {
    DEFINITE,       // 幅がピクセルやパーセントで確定している
    SHRINK_TO_FIT   // 子要素のサイズに合わせて縮む (従来の w == -1)
}