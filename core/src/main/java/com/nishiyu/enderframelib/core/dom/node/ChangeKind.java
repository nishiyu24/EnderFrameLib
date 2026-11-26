package com.nishiyu.enderframelib.core.dom.node;

/**
 * 変更台帳（{@link ChangeLedger}）の変更の種別。台帳の読み手（{@code common}・{@code core.box}）が使うのでトップレベルに置く。
 * {@link #STYLE_SUBTREE} は要素とその子孫の全てを解き直す再スタイル（無効化セットの {@code DESCENDANTS}: 祖先の {@code :hover} を読む
 * {@code .list:hover .item} など。子孫の継承値が変わらなくても子孫自身に掛かる規則が変わる）。
 * {@link #ANCHOR} はアンカーの再計算点で、流れの外の要素の覚えたスクロール量が変わった（計画書 §3.2 の G8。スタイルは解き直さず、
 * 箱だけを作り直す）。
 */
public enum ChangeKind { TEXT, CHILDREN, ATTR, STYLE, ENV, SCROLL, STYLE_SUBTREE, ANCHOR }
