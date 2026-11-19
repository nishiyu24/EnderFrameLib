package com.nishiyu.enderframelib.core.paint;

import java.util.Arrays;

/** 小さな int の可変配列（fastutil を引かずに済ませる）。 */
final class IntArrayListLite {
    int[] a = new int[8];
    int size;
    void add(int v) {
        if (size == a.length) a = java.util.Arrays.copyOf(a, size * 2);
        a[size++] = v;
    }
}
