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
     * 例如：Page 默认大小为 8KB ，Subpage 默认最小为 16B ，所以一个 Page 最多
     * 可包含 2^3 * 2^10 / 2^4 = 2^9 个 Subpage 。
     * 因此，bitmap 数组大小为 2^9 / 2^6 = 2^3 = 8
     * 这里之所以除以 2^6 是因为 long 为 8B，那么总共是 2^3 * 2^3 位，即 2^6
     * 所以它里面是每一位表示一个 Subpage 是否被分配
     * 另外，bitmap 的数组大小，使用 {@link #bitmapLength} 来标记。或者说，bitmap 数组，默认按照 Subpage 的大小为 16B 来初始化。
     * 为什么是这样的设定呢？因为 PoolSubpage 可重用，通过 {@link #init(PoolSubpage, int)} 进行重新初始化。
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
        // 这个构造仅仅用来创建表头
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
        // 默认初始化为 8，即最大值
        // pageSize / 16 / 64
        // 这里的 16 是 16B，即让 pageSize 以 16B 为大小分为一块一块的
        // 这里的 64 是 long 的位数，即 2^3 * 2^3
        bitmap = new long[pageSize >>> 10];

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
            // 这里除以 2^6 是 long 的长度
            bitmapLength = maxNumElems >>> 6;
            // 下面的判断表示 maxNumElems 是否能被 64 整除
            if ((maxNumElems & 63) != 0) {
                // 未整除，补 1
                // 这是由于如果出现未整除的情况，那么 long 的位置会不够，需要再加一个 long
                bitmapLength++;
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
        // 获得下一个可用的 Subpage 在 bitmap 中数组的 index
        // bitmapIdx 在这里的含义不是 bitmap 数组的 index，而是
        // 其处在那个逻辑数组的 index，所以这里直接对 bitmapIdx 除以 64 即可
        int q = bitmapIdx >>> 6; // 除以 64
        // 获得下一个可用的 Subpage 在 bitmap 中数组的位置的第几 bits
        // 然后对 bitmapIdx 做个取余，计算一下对 64 取余后表示在 long 的第几个位置
        // bitmapIdx & 63 等同于 bitmapIdx % 64
        int r = bitmapIdx & 63;
        // 断言该位置是否是未分配
        assert (bitmap[q] >>> r & 1) == 0;
        // 修改 Subpage 在 bitmap 中不可分配
        // 将需要改为未分配的位置修改一下
        bitmap[q] |= 1L << r;

        // 可用 Subpage 内存块的计数减一
        if (--numAvail == 0) {
            // 无可用 Subpage 内存块，则从双向链表中移除
            removeFromPool();
        }

        // 计算 handle
        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     * {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     *
     * 返回 true 表示该 subpage 在使用中，返回 false 表示该 subPage 不再由 chunk 使用可以释放
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        // 防御性编程，不存在这种情况
        if (elemSize == 0) {
            return true;
        }
        // 获得 Subpage 在 bitmap 中数组的位置
        int q = bitmapIdx >>> 6;
        // 获得 Subpage 在 bitmap 中数组的 long 的第几位
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        // 修改 Subpage 在 bitmap 中为不可分配
        // 这里是异或运算 1 ^ 1 = 0，0 ^ 1 = 1
        // 这里主要是运用 1 ^ 1 = 0
        bitmap[q] ^= 1L << r;

        // 由于释放了一个，那么下次就可以获取这一个，那么设置上就好了
        setNextAvail(bitmapIdx);

        // 可用 Subpage 内存块的计数加一
        // 先判断 numAvail == 0，然后再执行 numAvail += 1
        // 如果目前没有可用的子叶，那么则不进入 if
        if (numAvail++ == 0) {
            // 添加到 Arena 的双向链表中
            addToPool(head);
            return true;
        }

        // 目前可以使用的 subPage 的数目不等于最大可用的元素
        // 则说明还有 Subpage 在使用
        if (numAvail != maxNumElems) {
            return true;
        }
        // 没有 Subpage 在使用
        else {
            // 双向链表中，只有该节点，不进行移除
            // Subpage not in use (numAvail == maxNumElems)
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // 标记为已销毁
            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            // 从双向链表中移除
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
            // 值存在的话直接拿出来
            // nextAvail 大于 0 ，意味着已经“缓存”好下一个可用的位置，直接返回即可
            this.nextAvail = -1;
            return nextAvail;
        }
        // 寻找下一个 nextAvail
        return findNextAvail();
    }

    private int findNextAvail() {
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        for (int i = 0; i < bitmapLength; i++) {
            long bits = bitmap[i];
            // 这里除了 bits = 0xFFFFFFFF 的情况下 ~bits 是等于 0 的
            // 别的情况都是不等于 0 的
            // 也就一位置 ~bits != 0 表示的是该 long 没有满
            // 但是这里会不会出现 bug，也就是说如果 long 本身就存在不是 0xFFFFFFFF 表示满
            // 的状态的话，这里便可能导致 bug
            if (~bits != 0) {
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }

    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        // 计算基础值，表示在 bitmap 的数组下标
        // 相当于 * 2^6，这里 i 传进来的时候表示这是第几个 long
        // 如果是第 0 个 long，那么基础值为 0
        // 如果是第 1 个 long，那么基础值为 1 * 2^6 = 64，
        // 表示前 64 个位已经判断过了
        final int baseVal = i << 6;

        // 遍历 64 bits，这里循环 64 是因为一个 long 64 位
        for (int j = 0; j < 64; j++) {
            // 计算当前 bit 是否未分配
            if ((bits & 1) == 0) {
                // 可能 bitmap 最后一个元素，并没有 64 位
                // 通过 baseVal | j < maxNumElems 来保证不超过上限
                // 其实就是 baseVal + j < maxNumElems 这就很好懂了
                int val = baseVal | j;
                // 因为数组下标从 0 开始，所以这里是 < 而不是 <=
                if (val < maxNumElems) {
                    // 未超过，返回 val
                    return val;
                } else {
                    // 超过，结束循环，最终返回 -1
                    break;
                }
            }
            // 移动到下一位，然后循环继续判断
            bits >>>= 1;
        }
        // 未找到
        return -1;
    }

    private long toHandle(int bitmapIdx) {
        // 这里首先理解 (long) bitmapIdx << 32 | memoryMapIdx
        // 由于他两都是 int 都是 32 位
        // 所以效果就是将 bitmapIdx 放到高 32 位，memoryMapIdx 放到低 32 位，然后合并起来
        // 这里面由于 Subpage 最小以 16B 做切分，所以 bitmapIdx 最大是 512
        // 也就是 0x00000000_00000000_00000001_00000000
        // 这里的 0x01000000_00000000_00000000_00000000
        // 以最高位 01 作为标志位，表示其位 handle，防止和 chunk 的 handle 搞混
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
