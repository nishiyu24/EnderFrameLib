package com.nishiyu.enderframelib.core.paint;

/**
 * スクロールコンテナの中身（保持できる層）の扱い。{@link PaintSceneDriver.Painter#planRetained} の答え。
 * {@code SKIP}: 保持済みの層を重ねるので項目を走査しない。{@code RECORD}: 範囲を層へ記録する。{@code NONE}: ふつうに描く。
 */
public enum RetainPlan { NONE, SKIP, RECORD }
