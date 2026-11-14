package com.nishiyu.enderframelib.core.kernel;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.RandomAccess;

/**
 * 要素数が 0〜1 の間は内部配列 {@code Object[]} を確保しない軽量な可変 {@link java.util.List}。
 *
 * <p>{@link java.util.ArrayList} は最初の要素を追加した時点で長さ 10 の {@code Object[]}（56 B）を確保するが、
 * DOM の子リスト・{@code LayoutBox} の子／断片リストの大半は空か 1 要素であるため、単一要素 {@code sole} を
 * フィールドに直接保持し、2 要素以上になったときだけ必要最小限（最低長 2）の配列へ移す。</p>
 */
public class CompactList<E> extends AbstractList<E> implements RandomAccess {

    private int size;
    private E sole;
    private Object[] array;

    public CompactList() { }

    @Override
    public int size() {
        return size;
    }

    @Override
    @SuppressWarnings("unchecked")
    public E get(int index) {
        Objects.checkIndex(index, size);
        return array == null ? sole : (E) array[index];
    }

    @Override
    @SuppressWarnings("unchecked")
    public E set(int index, E element) {
        Objects.checkIndex(index, size);
        if (array == null) {
            E prev = sole;
            sole = element;
            return prev;
        }
        E prev = (E) array[index];
        array[index] = element;
        return prev;
    }

    @Override
    public boolean add(E element) {
        modCount++;
        if (array == null) {
            if (size == 0) {
                sole = element;
                size = 1;
                return true;
            }
            Object[] arr = new Object[2];
            arr[0] = sole;
            arr[1] = element;
            sole = null;
            array = arr;
            size = 2;
            return true;
        }
        ensureCapacity(size + 1);
        array[size++] = element;
        return true;
    }

    @Override
    public void add(int index, E element) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        modCount++;
        if (array == null) {
            if (size == 0) {
                sole = element;
                size = 1;
                return;
            }
            Object[] arr = new Object[2];
            if (index == 0) {
                arr[0] = element;
                arr[1] = sole;
            } else {
                arr[0] = sole;
                arr[1] = element;
            }
            sole = null;
            array = arr;
            size = 2;
            return;
        }
        ensureCapacity(size + 1);
        int move = size - index;
        if (move > 0) System.arraycopy(array, index, array, index + 1, move);
        array[index] = element;
        size++;
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        int addCount = c.size();
        if (addCount == 0) return false;
        if (size == 0 && addCount == 1) {
            return add(c.iterator().next());
        }
        modCount++;
        ensureCapacity(size + addCount);
        for (E e : c) array[size++] = e;
        return true;
    }

    @Override
    public boolean addAll(int index, Collection<? extends E> c) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        int addCount = c.size();
        if (addCount == 0) return false;
        if (size == 0 && addCount == 1) {
            add(index, c.iterator().next());
            return true;
        }
        modCount++;
        Object[] added = c.toArray();
        ensureCapacity(size + added.length);
        int move = size - index;
        if (move > 0) System.arraycopy(array, index, array, index + added.length, move);
        System.arraycopy(added, 0, array, index, added.length);
        size += added.length;
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public E remove(int index) {
        Objects.checkIndex(index, size);
        modCount++;
        if (array == null) {
            E prev = sole;
            sole = null;
            size = 0;
            return prev;
        }
        E prev = (E) array[index];
        int move = size - index - 1;
        if (move > 0) System.arraycopy(array, index + 1, array, index, move);
        array[--size] = null;
        return prev;
    }

    @Override
    protected void removeRange(int fromIndex, int toIndex) {
        int removed = toIndex - fromIndex;
        if (removed <= 0) return;
        modCount++;
        if (array == null) {
            sole = null;
            size = 0;
            return;
        }
        int move = size - toIndex;
        if (move > 0) System.arraycopy(array, toIndex, array, fromIndex, move);
        int nextSize = size - removed;
        Arrays.fill(array, nextSize, size, null);
        size = nextSize;
    }

    @Override
    public void clear() {
        if (size == 0) return;
        modCount++;
        sole = null;
        if (array != null) {
            Arrays.fill(array, 0, size, null);
        }
        size = 0;
    }

    private void ensureCapacity(int minCap) {
        if (array == null) {
            int cap = Math.max(2, minCap);
            Object[] arr = new Object[cap];
            if (size == 1) {
                arr[0] = sole;
                sole = null;
            }
            array = arr;
            return;
        }
        if (minCap <= array.length) return;
        int newCap = array.length < 32 ? array.length << 1 : array.length + (array.length >> 1);
        if (newCap < minCap) newCap = minCap;
        array = Arrays.copyOf(array, newCap);
    }
}
