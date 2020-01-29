/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;

/**
 * 同一个 PoolSubpage 内部的 Subpage 占用的内存大小是相同的
 *
 * todo 这里面存的东西如此之多，难道每一个子页都要存么，那么更新的时候所有的子页都要更新
 */
final class PoolSubpage<T> implements PoolSubpageMetric {

    /** 所属 PoolChunk 对象 */
    final PoolChunk<T> chunk;
    /** 在 {@link PoolChunk#memoryMap} 的节点编号 */
    private final int memoryMapIdx;
    /** 在 Chunk 中，偏移字节量 */
    private final int runOffset;
    /** Page 大小 {@link PoolChunk#pageSize} */
    private final int pageSize;
    /**
     * Subpage 分配信息数组
     *
     * 表示子叶里面的内存分配情况，1 表示已经被分配，0 表示未被分配
     *
     * 每个 long 的 bits 位代表一个 Subpage 是否分配。
     * 因为 PoolSubpage 可能会超过 64 个( long 的 bits 位数 )，所以使用数组。
     *   例如：Page 默认大小为 8KB ，Subpage 默认最小为 16 B ，所以一个 Page 最多可包含 8 * 1024 / 16 = 512 个 Subpage 。
     *        因此，bitmap 数组大小为 512 / 64 = 8 。
     * 另外，bitmap 的数组大小，使用 {@link #bitmapLength} 来标记。或者说，bitmap 数组，默认按照 Subpage 的大小为 16B 来初始化。
     *    为什么是这样的设定呢？因为 PoolSubpage 可重用，通过 {@link #init(PoolSubpage, int)} 进行重新初始化。
     *
     * todo 这里面有个问题，子页是以双向链表的形式进行连接，那么它搞一个 long[] 到底是怎么判断是否被分配的
     * 对于上述的这个问题，其实它本身是基于 page 做的分配，那么分配完之后，逻辑上面以 bitmap 做记录
     */
    private final long[] bitmap;

    /** 子页以双向链表的方式进行连接 */
    PoolSubpage<T> prev;
    PoolSubpage<T> next;

    /** 是否未销毁 */
    boolean doNotDestroy;
    /** 表示子页的大小，即每个 Subpage 的占用内存大小，单位是字节，简写为 B，比如说 1024B 就是 1KB */
    int elemSize;
    /** 总共 Subpage 的数量 */
    private int maxNumElems;
    /** {@link #bitmap} 长度，这是因为其逻辑上面的长度和实际的长度可能不同 */
    private int bitmapLength;
    /** 下一个可分配 Subpage 的数组位置，todo 这里标记下一个可分配的数组位置，那难道每一个 Subpage 里面都要维护一个 */
    private int nextAvail;
    /** 剩余可用 Subpage 的数量 */
    private int numAvail;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;
        this.runOffset = runOffset;
        this.pageSize = pageSize;
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64
        init(head, elemSize);
    }

    void init(PoolSubpage<T> head, int elemSize) {
        // 未销毁
        doNotDestroy = true;
        // 初始化 elemSize
        this.elemSize = elemSize;
        if (elemSize != 0) {
            // pageSize / elemSize 的结果是将 page 划分为多少分
            // pageSize 便是 page 大小
            // elemSize 便是每个元素大小
            maxNumElems = numAvail = pageSize / elemSize;
            // 初始化 nextAvail
            nextAvail = 0;
            // 计算 bitmapLength 的大小
            bitmapLength = maxNumElems >>> 6;
            if ((maxNumElems & 63) != 0) {
                // 未整除，补 1
                bitmapLength ++;
            }

            // 初始化 bitmap
            for (int i = 0; i < bitmapLength; i++) {
                bitmap[i] = 0;
            }
        }
        // 添加到 Arena 的双向链表中
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     */
    long allocate() {
        // 防御性编程，不存在这种情况。
        if (elemSize == 0) {
            return toHandle(0);
        }

        // 可用数量为 0 ，或者已销毁，返回 -1 ，即不可分配
        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        // 获得下一个可用的 Subpage 在 bitmap 中的总体位置
        final int bitmapIdx = getNextAvail();
        // 获得下一个可用的 Subpage 在 bitmap 中数组的位置
        int q = bitmapIdx >>> 6;
        // 获得下一个可用的 Subpage 在 bitmap 中数组的位置的第几 bits
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) == 0;
        // 修改 Subpage 在 bitmap 中不可分配。
        bitmap[q] |= 1L << r;

        // 可用 Subpage 内存块的计数减一
        if (-- numAvail == 0) {
            // 无可用 Subpage 内存块，则从双向链表中移除
            removeFromPool();
        }

        // 计算 handle
        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        if (elemSize == 0) {
            return true;
        }
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        bitmap[q] ^= 1L << r;

        setNextAvail(bitmapIdx);

        if (numAvail ++ == 0) {
            addToPool(head);
            return true;
        }

        if (numAvail != maxNumElems) {
            return true;
        } else {
            // Subpage not in use (numAvail == maxNumElems)
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            removeFromPool();
            return false;
        }
    }

    /** 加入到链表 */
    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    /** 从链表中移除当前节点 */
    private void removeFromPool() {
        assert prev != null && next != null;
        // 前后节点，互相指向
        prev.next = next;
        next.prev = prev;
        // 当前节点，置空
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    private int getNextAvail() {
        int nextAvail = this.nextAvail;
        if (nextAvail >= 0) {
            this.nextAvail = -1;
            return nextAvail;
        }
        return findNextAvail();
    }

    private int findNextAvail() {
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];
            if (~bits != 0) {
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }

    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        final int baseVal = i << 6;

        for (int j = 0; j < 64; j ++) {
            if ((bits & 1) == 0) {
                int val = baseVal | j;
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            bits >>>= 1;
        }
        return -1;
    }

    private long toHandle(int bitmapIdx) {
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        synchronized (chunk.arena) {
            if (!this.doNotDestroy) {
                doNotDestroy = false;
                // Not used for creating the String.
                maxNumElems = numAvail = elemSize = -1;
            } else {
                doNotDestroy = true;
                maxNumElems = this.maxNumElems;
                numAvail = this.numAvail;
                elemSize = this.elemSize;
            }
        }

        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return "(" + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
