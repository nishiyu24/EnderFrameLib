package com.nishiyu.enderframelib.core.dom.node;

/**
 * 変更台帳（{@link ChangeLedger}）の変更の種別。台帳の読み手（{@code common}・{@code core.box}）が使うのでトップレベルに置く。
 */
public enum ChangeKind { TEXT, CHILDREN, ATTR, STYLE, ENV, SCROLL }
