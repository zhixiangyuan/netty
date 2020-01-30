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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 *
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated
 * > chunk - a chunk is a collection of pages
 * > in this code chunkSize = 2^{maxOrder} * pageSize
 *
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 *
 * For simplicity all sizes are normalized according to PoolArena#normalizeCapacity method
 * This ensures that when we request for memory segments of size >= pageSize the normalizedCapacity
 * equals the next nearest power of 2
 *
 * To search for the first offset in chunk that has at least requested size available we construct a
 * complete balanced binary tree and store it in an array (just like heaps) - memoryMap
 *
 * The tree looks like this (the size of each node being mentioned in the parenthesis)
 *
 * depth=0        1 node (chunkSize)
 * depth=1        2 nodes (chunkSize/2)
 * ..
 * ..
 * depth=d        2^d nodes (chunkSize/2^d)
 * ..
 * depth=maxOrder 2^maxOrder nodes (chunkSize/2^{maxOrder} = pageSize)
 *
 * depth=maxOrder is the last level and the leafs consist of pages
 *
 * With this tree available searching in chunkArray translates like this:
 * To allocate a memory segment of size chunkSize/2^k we search for the first node (from left) at height k
 * which is unused
 *
 * Algorithm:
 * ----------
 * Encode the tree in memoryMap with the notation
 *   memoryMap[id] = x => in the subtree rooted at id, the first node that is free to be allocated
 *   is at depth x (counted from depth=0) i.e., at depths [depth_of_id, x), there is no node that is free
 *
 *  As we allocate & free nodes, we update values stored in memoryMap so that the property is maintained
 *
 * Initialization -
 *   In the beginning we construct the memoryMap array by storing the depth of a node at each node
 *     i.e., memoryMap[id] = depth_of_id
 *
 * Observations:
 * -------------
 * 1) memoryMap[id] = depth_of_id  => it is free / unallocated
 * 2) memoryMap[id] > depth_of_id  => at least one of its child nodes is allocated, so we cannot allocate it, but
 *                                    some of its children can still be allocated based on their availability
 * 3) memoryMap[id] = maxOrder + 1 => the node is fully allocated & thus none of its children can be allocated, it
 *                                    is thus marked as unusable
 *
 * Algorithm: [allocateNode(d) => we want to find the first node (from left) at height h that can be allocated]
 * ----------
 * 1) start at root (i.e., depth = 0 or id = 1)
 * 2) if memoryMap[1] > d => cannot be allocated from this chunk
 * 3) if left node value <= h; we can allocate from left subtree so move to left and repeat until found
 * 4) else try in right subtree
 *
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) Compute d = log_2(chunkSize/size)
 * 2) Return allocateNode(d)
 *
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) use allocateNode(maxOrder) to find an empty (i.e., unused) leaf (i.e., page)
 * 2) use this handle to construct the PoolSubpage object or if it already exists just call init(normCapacity)
 *    note that this PoolSubpage object is added to subpagesPool in the PoolArena when we init() it
 *
 * Note:
 * -----
 * In the implementation for improving cache coherence,
 * we store 2 pieces of information depth_of_id and x as two byte values in memoryMap and depthMap respectively
 *
 * memoryMap[id]= depth_of_id  is defined above
 * depthMap[id]= x  indicates that the first node which is free to be allocated is at depth x (from root)
 */
final class PoolChunk<T> implements PoolChunkMetric {

    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;

    /** 所属 Arena 对象 */
    final PoolArena<T> arena;
    /** 内存空间 */
    final T memory;
    /** 是否非池化 */
    final boolean unpooled;
    /** todo 不知道有什么用 */
    final int offset;
    /** 分配信息满二叉树，index 为节点编号 */
    private final byte[] memoryMap;
    /** 高度信息满二叉树，index 为节点编号 */
    private final byte[] depthMap;
    /** PoolSubpage 数组 */
    private final PoolSubpage<T>[] subpages;
    /**
     * Used to determine if the requested capacity is equal to or greater than pageSize.
     *
     * 判断分配请求内存是否为 Tiny/Small ，即分配 Subpage 内存块
     */
    private final int subpageOverflowMask;
    /** Page 大小，默认 8KB = 8192B */
    private final int pageSize;
    /**
     * 从 1 开始左移到 {@link #pageSize} 的位数。默认 13 ，1 << 13 = 8192
     *
     * 具体用途，见 {@link #allocateRun(int)} 方法，计算指定容量所在满二叉树的层级
     */
    private final int pageShifts;
    /** 满二叉树的高度。默认为 11，层高从 0 开始 */
    private final int maxOrder;
    /** Chunk 内存块占用大小。默认为 16M = 16 * 1024 */
    private final int chunkSize;
    /** log2 {@link #chunkSize} 的结果。默认为 log2( 16M ) = 24 */
    private final int log2ChunkSize;
    /**
     * 可分配 {@link #subpages} 的数量，即数组大小。默认为 1 << maxOrder = 1 << 11 = 2048
     * 这里写的很合理，它其实是最大的可分配的 subpage 数，因为我们知道一个 chunk 是 16M = 2^24，
     * 而一个 page 是 8K = 2^13，所以 2^24 / 2^13 = 2^11 = 2048
     *
     * 表示的其实就是最大有 2048 个 page 可以分配为 subpage
     */
    private final int maxSubpageAllocs;
    /**
     * Used to mark memory as unusable
     *
     * 标记节点不可用。默认为 maxOrder + 1 = 12
     */
    private final byte unusable;

    // Use as cache for ByteBuffer created from the memory. These are just duplicates and so are only a container
    // around the memory itself. These are often needed for operations within the Pooled*ByteBuf and so
    // may produce extra GC, which can be greatly reduced by caching the duplicates.
    //
    // This may be null if the PoolChunk is unpooled as pooling the ByteBuffer instances does not make any sense here.
    private final Deque<ByteBuffer> cachedNioBuffers;

    /** 剩余可用字节数 */
    private int freeBytes;

    /** 所属 PoolChunkList 对象 */
    PoolChunkList<T> parent;
    /** 通过 prev 和 next 将所有的 chunk 组成双向链表结构 */
    /** 上一个 Chunk 对象 */
    PoolChunk<T> prev;
    /** 下一个 Chunk 对象 */
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolChunk(PoolArena<T> arena, T memory, int pageSize, int maxOrder, int pageShifts, int chunkSize, int offset) {
        // 池化
        unpooled = false;
        // 保存 arena
        this.arena = arena;
        // 保存申请的内存块
        this.memory = memory;
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.maxOrder = maxOrder;
        // chunkSize 大小 16M
        this.chunkSize = chunkSize;
        this.offset = offset;
        // 默认为层高加一
        unusable = (byte) (maxOrder + 1);
        log2ChunkSize = log2(chunkSize);
        subpageOverflowMask = ~(pageSize - 1);
        // 刚创建的时候能够使用的空间大小就等于 chunkSize
        freeBytes = chunkSize;

        assert maxOrder < 30 : "maxOrder should be < 30, but is: " + maxOrder;
        maxSubpageAllocs = 1 << maxOrder;


        // 初始化 memoryMap 和 depthMap
        // Generate the memory map.
        // 注意，下面的 d <= maxOrder 是 <= 不是 <
        // 所以完全二叉树的树高需要加一
        memoryMap = new byte[maxSubpageAllocs << 1];
        depthMap = new byte[memoryMap.length];
        int memoryMapIndex = 1;
        for (int d = 0; d <= maxOrder; ++ d) { // move down the tree one level at a time
            int depth = 1 << d;
            for (int p = 0; p < depth; ++ p) {
                // in each level traverse left to right and set value to the depth of subtree
                memoryMap[memoryMapIndex] = (byte) d;
                depthMap[memoryMapIndex] = (byte) d;
                memoryMapIndex ++;
            }
        }

        // 初始化 subpages，这里为了复用，所以初始化的最大的数组
        // 虽然某些页可能暂时不会作为 subpage，但是将来可能会作为 subpage
        subpages = newSubpageArray(maxSubpageAllocs);
        cachedNioBuffers = new ArrayDeque<ByteBuffer>(8);
    }

    /** Creates a special chunk that is not pooled. */
    PoolChunk(PoolArena<T> arena, T memory, int size, int offset) {
        // 非池化
        unpooled = true;
        this.arena = arena;
        this.memory = memory;
        this.offset = offset;
        memoryMap = null;
        depthMap = null;
        subpages = null;
        subpageOverflowMask = 0;
        pageSize = 0;
        pageShifts = 0;
        maxOrder = 0;
        unusable = (byte) (maxOrder + 1);
        chunkSize = size;
        log2ChunkSize = log2(chunkSize);
        maxSubpageAllocs = 0;
        cachedNioBuffers = null;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpageArray(int size) {
        return new PoolSubpage[size];
    }

    @Override
    public int usage() {
        // 根据空余空间来计算使用率
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }
        return usage(freeBytes);
    }

    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        final long handle;
        // 大于等于 Page 大小，分配 Page 内存块
        if ((normCapacity & subpageOverflowMask) != 0) { // >= pageSize
            handle =  allocateRun(normCapacity);
        }
        // 小于 Page 大小，分配 Subpage 内存块
        else {
            handle = allocateSubpage(normCapacity);
        }

        if (handle < 0) {
            return false;
        }
        ByteBuffer nioBuffer = cachedNioBuffers != null ? cachedNioBuffers.pollLast() : null;
        initBuf(buf, nioBuffer, handle, reqCapacity);
        return true;
    }

    /**
     * Update method used by allocate
     * This is triggered only when a successor is allocated and all its predecessors
     * need to update their state
     * The minimal depth at which subtree rooted at id has some free space
     *
     * 更新获得的节点的祖先都不可用
     *
     * @param id id
     */
    private void updateParentsAlloc(int id) {
        // 当节点为根节点的时候结束循环
        while (id > 1) {
            // id 为当前节点，如果当前节点不为根节点的话则进入循环，即 id > 1，因为 1 是根节点
            // >>> 为无符号右移
            // id >>> 1 即对 id 除以 2
            int parentId = id >>> 1;
            // 取出当前节点的值
            byte val1 = value(id);
            // 取出当前节点的兄弟节点的值
            byte val2 = value(id ^ 1);
            // 从两个值中取出较低小的那一个
            byte val = val1 < val2 ? val1 : val2;
            // 然后将较小的值赋值给父节点
            setValue(parentId, val);
            // 将父节点置为当前节点
            id = parentId;
        }
    }

    /**
     * Update method used by free
     * This needs to handle the special case when both children are completely free
     * in which case parent be directly allocated on request of size = child-size * 2
     *
     * @param id id
     */
    private void updateParentsFree(int id) {
        int logChild = depth(id) + 1;
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1);
            logChild -= 1; // in first iteration equals log, subsequently reduce 1 from logChild as we traverse up

            if (val1 == logChild && val2 == logChild) {
                setValue(parentId, (byte) (logChild - 1));
            } else {
                byte val = val1 < val2 ? val1 : val2;
                setValue(parentId, val);
            }

            id = parentId;
        }
    }

    /**
     * Algorithm to allocate an index in memoryMap when we query for a free node
     * at depth d
     *
     * 在深度 d 上分配一个节点
     *
     * @param d depth
     * @return index in memoryMap
     */
    private int allocateNode(int d) {
        // id 表示的是当前选择节点的索引
        int id = 1;
        // 这里的 initial 其实是一个蒙板，用于实现高效的小于操作
        // 具体细节在下面的 (id & initial) == 0 分析
        int initial = - (1 << d); // has last d bits = 0 and rest all = 1
        // 获取根节点可分配的内存，即根节点的深度
        // value(id) { return memoryMap[id]; }
        byte val = value(id);
        // 如果根节点的值,如果跟节点大于 d，当前 Chunk 没有符合的节点
        // 比如说根节点为 2，即 val = 2，那么最大可分配内存为 8M，而如果 d = 1，那么说明想要分配 16M 内存
        // 那么此 chunk 必然无法满足
        if (val > d) { // unusable
            return -1;
        }
        // val < d 表示的是当前节点的容量是否大于申请的容量
        //
        // 下面是对 (id & initial) == 0 分析，其实很简单，就是一个位运算实现 < 号，提高效率
        // 这里假设 d = 11，那么 initial 便等于 0b11111111_11111111_11111000_00000000
        // int initial = - (1 << d) 这个操作便完成了制作一个后 11 位为 0，前面都是 1 的工作
        // 然后在运算 (id & initial) == 0 的运算起到的效果便是 id < 2048
        // 这里 initial = -2048，但是运算的效果是 id < 2048
        // 那么这个表达式的效果就是当选择的节点没到 id > 2048 的时候便一直循环
        // 而 2048 是 d = 11 的第一个节点
        // id & initial 来保证，高度小于 d 会继续循环
        //
        // 看完上述分析，那么这里走出循环的逻辑便是当 val >= d 也就是当前节点的容量小于等于申请节点的容量（而小于无法分配，
        // 所以只有等于的情况了），&& id >= 2048，也就是在所申请层的节点，而不是在其父节点
        //
        // 看过这里的分析便明白这里进入循环的条件是当 val 即当前节点的容量等于请求的容量
        // 或者此时还没有循环到该到的深度的时候便进入循环
        while (val < d || (id & initial) == 0) { // id & initial == 1 << d for all ids at depth d, for < d it is 0
            // 对 id * 2，进入下一层
            id <<= 1;
            // 获取 id 所在节点的深度
            val = value(id);
            // 如果值大于 d ，说明，以左节点作为根节点形成虚拟的虚拟满二叉树，没有符合的节点
            if (val > d) {
                // 此处的 id 为偶数则加一，奇数则减一
                // 也就是说如果是左边节点不满足则换到右边节点
                // 如果是右边节点不满足，则换到左边节点
                // 不过这里的 id 一定是偶数，所以只会是从左边节点切换到右边节点的效果
                id ^= 1;
                // 然后取出节点中的深度
                val = value(id);
            }
        }
        // 校验获得的节点值合理
        byte value = value(id);
        assert value == d && (id & initial) == 1 << d : String.format("val = %d, id & initial = %d, d = %d",
                value, id & initial, d);
        // 更新获得的节点不可用，即该节点已分配
        setValue(id, unusable); // mark as unusable
        // 更新获得的节点的祖先都不可用
        updateParentsAlloc(id);
        // 返回节点编号
        return id;
    }

    /**
     * Allocate a run of pages (>=1)
     *
     * 分配一个大于等一一个 page 的空间
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateRun(int normCapacity) {
        // 算出来需要分配的内存大小在完全二叉树的第几层
        // 这里面需要注意的是 normCapacity 是大于等于 page 大小的，这个方法外层有个 if 控制住了
        // 那么 log2(normCapacity) 计算出来的数值一定是大于等于 pageShifts
        // 假设这里进来的 normCapacity = 8192 刚好是一个 page 的大小
        // 那么计算出来的 log2(normCapacity) = 13，pageShifts 也等于 13
        // 最后 maxOrder - (log2(normCapacity) - pageShifts) = 11，而第 11 层确实是按照 8K 来分配内存的

        // 这里的计算逻辑便是 maxOrder 作为默认的层高，通过 (log2(normCapacity) - pageShifts) 来
        // 判断是否需要向上层移动，以及需要移动几层
        // 那么这里移动几层便是通过二进制来判断，normCapacity 必然是 2 的幂次
        // 这里面的 pageShifts 是 13，表示的是一个 1 << 13 的二进制数即 8192，也即 0x00000000_00000000_00100000_00000000
        // 而 normCapacity 是一个 2 的幂的数，这个数的二进制只会有一个 1，log2(normCapacity) 也是判断这个 1 后面有几个 0
        // 因为默认 page 为 8192，pageShifts 也就是 13
        // 当 normCapacity 后面有 13 个零的时候，那么 normCapacity 便与 page 大小相等，不需要向上层移动
        // 当 normCapacity 后面有 14 个零的时候，那么 normCapacity 便比 page 大出一个等级，便需要向上移动一个层级
        // 当 normCapacity 后面有 15 个零的时候，那么 normCapacity 便比 page 大出两个等级，便需要向上移动两个层级
        // 表现形式就是 maxOrder - (log2(normCapacity) - pageShifts)
        int d = maxOrder - (log2(normCapacity) - pageShifts);
        // 在该层上面分配一个节点
        int id = allocateNode(d);
        // 未获得到节点，直接返回
        if (id < 0) {
            return id;
        }
        // 减少剩余可用字节数
        freeBytes -= runLength(id);
        return id;
    }

    /**
     * Create / initialize a new PoolSubpage of normCapacity
     * Any PoolSubpage created / initialized here is added to subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateSubpage(int normCapacity) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
        // 获得对应内存规格的 Subpage 双向链表的 head 节点
        PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity);
        // 选中最 page 的最底层，maxOrder 即 11
        int d = maxOrder; // subpages are only be allocated from pages i.e., leaves
        // 加锁，分配过程会修改双向链表的结构，会存在多线程的情况
        // todo 这里的竞争是什么情况
        synchronized (head) {
            // 获得最底层的一个节点。Subpage 只能使用二叉树的最底层的节点
            int id = allocateNode(d);
            // 获取失败，直接返回
            if (id < 0) {
                return id;
            }

            // 拿到 chunk 中的 subpages 数组
            final PoolSubpage<T>[] subpages = this.subpages;
            // 获取每页大小
            final int pageSize = this.pageSize;

            // 减少剩余可用字节数
            freeBytes -= pageSize;

            // 获得节点对应的 subpages 数组的编号，这个编号是 page 的编号
            int subpageIdx = subpageIdx(id);
            // 获得节点对应的 subpages 数组的 PoolSubpage 对象
            PoolSubpage<T> subpage = subpages[subpageIdx];
            // 初始化 PoolSubpage 对象
            if (subpage == null) {
                // 不存在，则进行创建 PoolSubpage 对象
                subpage = new PoolSubpage<T>(head, this, id, runOffset(id), pageSize, normCapacity);
                subpages[subpageIdx] = subpage;
            } else {
                // 存在，则重新初始化 PoolSubpage 对象
                subpage.init(head, normCapacity);
            }
            // 分配 PoolSubpage 内存块
            return subpage.allocate();
        }
    }

    /**
     * Free a subpage or a run of pages
     * When a subpage is freed from PoolSubpage, it might be added back to subpage pool of the owning PoolArena
     * If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize, we can
     * completely free the owning Page so it is available for subsequent allocations
     *
     * @param handle handle to free
     */
    void free(long handle, ByteBuffer nioBuffer) {
        // 取出 memoryMapIdx
        int memoryMapIdx = memoryMapIdx(handle);
        // 取出 bitmapIdx
        int bitmapIdx = bitmapIdx(handle);

        if (bitmapIdx != 0) { // free a subpage
            // 如果 bitmapIdx != 0 则说明需要释放的是 subpage
            // 通过索引找到该 subpage
            PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
            assert subpage != null && subpage.doNotDestroy;

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            // 获得对应内存规格的 Subpage 双向链表的 head 节点
            PoolSubpage<T> head = arena.findSubpagePoolHead(subpage.elemSize);
            // 加锁，分配过程会修改双向链表的结构，会存在多线程的情况
            synchronized (head) {
                // 释放 Subpage
                if (subpage.free(head, bitmapIdx & 0x3FFFFFFF)) {
                    return;
                }
                // 返回 false ，说明 Page 中无切分正在使用的 Subpage 内存块，所以可以继续向下执行，释放 Page
            }
        }
        // 释放 Page begin

        // 增加剩余可用字节数
        freeBytes += runLength(memoryMapIdx);
        // 设置 Page 对应的节点可用
        setValue(memoryMapIdx, depth(memoryMapIdx));
        // 更新 Page 对应的节点的祖先可用
        updateParentsFree(memoryMapIdx);

        if (nioBuffer != null && cachedNioBuffers != null &&
                cachedNioBuffers.size() < PooledByteBufAllocator.DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK) {
            cachedNioBuffers.offer(nioBuffer);
        }
    }

    void initBuf(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        int memoryMapIdx = memoryMapIdx(handle);
        int bitmapIdx = bitmapIdx(handle);
        // 判断是 page 还是 subpage，subpage 的 bitmapIdx 最高位为 01 不可能为 0
        if (bitmapIdx == 0) {
            byte val = value(memoryMapIdx);
            assert val == unusable : String.valueOf(val);
            buf.init(this, nioBuffer, handle, runOffset(memoryMapIdx) + offset,
                    reqCapacity, runLength(memoryMapIdx), arena.parent.threadCache());
        } else {
            initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx, reqCapacity);
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx(handle), reqCapacity);
    }

    private void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer,
                                    long handle, int bitmapIdx, int reqCapacity) {
        assert bitmapIdx != 0;

        int memoryMapIdx = memoryMapIdx(handle);

        // 根据 subpageIdx 取出 subpage 对象
        PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
        assert subpage.doNotDestroy;
        assert reqCapacity <= subpage.elemSize;

        buf.init(
            this, nioBuffer, handle,
                    // page 偏移量 + subpage 偏移量
                    // bitmapIdx & 0x3FFFFFFF 是因为 bitmapIdx 的高位为 0x01
                    // 通过 0x3F 即 0x00111111 将最高位两位给过滤掉，这里 0x3FFFFFFF
                    // 的作用便是作为掩码
                    // todo 这里的 offset 不管他，我也不知道这里还加个 offset 干嘛
                    //
                    // 计算 SubPage 内存块在 memory 中的开始位置
            runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset,
                reqCapacity, subpage.elemSize, arena.parent.threadCache());
    }

    /** 获取该 id 在树的哪一层，如果处于 un */
    private byte value(int id) {
        return memoryMap[id];
    }

    private void setValue(int id, byte val) {
        memoryMap[id] = val;
    }

    /** 根据 id 获取该节点所在树的深度 */
    private byte depth(int id) {
        return depthMap[id];
    }

    private static int log2(int val) {
        // compute the (0-based, with lsb = 0) position of highest set bit i.e, log2
        // 假设进来的 val = 16777216，即 16M，即 0b00000001_00000000_00000000_00000000
        // Integer.numberOfLeadingZeros(val) = 7，表示高位的 7 个 0
        // INTEGER_SIZE_MINUS_ONE = 31
        // INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val) = 24 表示除了第一个 1 后面的位数
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
        // 弄明白了它的含义之后还需要知道一点，即 log2(1 << 24) = 24，计算出来的这个数字便是 1 左移多少位能够还原的数字
    }

    /** 计算节点 id 所占用的字节长度*/
    private int runLength(int id) {
        // represents the size in #bytes supported by node 'id' in the tree
        // 这里计算节点长度我们来推演一下
        // 假设现在我们计算 id = 1 的节点，那么计算出来的值应该是 16M 即 2^4 * 2^20
        // log2ChunkSize = 24，depth(1) = 0，
        // 那么 1 << (log2ChunkSize - depth(id)) 便为 1 << 24 即  2^4 * 2^20
        // 如果是第二层节点即  2^3 * 2^20
        return 1 << (log2ChunkSize - depth(id));
        // 这里由于 log2(1 << 24) = 24，第 0 层不动，第 1 层除 2 即可，以此类推
    }

    /** 计算字节长度 */
    private int runOffset(int id) {
        // represents the 0-based offset in #bytes from start of the byte-array chunk
        // 这里是计算偏移几个点
        // id ^ (1 << depth(id)) 的结果等价于 id - (1 << depth(id))
        // 这里假设 id = 2048，那么 depth(id) = 11 便是 11 层，1 << 11 = 2048 便是 11 层第一个节点
        // 那么 id - 2048 便是 0，即无需移动
        // 假设 id = 2049 那么便是 id - 2048 = 1，需要移动一格
        int shift = id ^ (1 << depth(id));
        // shift * runLength(id) 即计算偏移
        // 假设第一个节点，那么便是 0 * runLength(id) = 0，即无需移动
        // 假设第二个节点，那么便是 1 * runLength(id) = runLength(id)，偏移量便是 runLength(id)
        return shift * runLength(id);
    }

    /** 计算出子页的 id */
    private int subpageIdx(int memoryMapIdx) {
        // 这里的 memoryMapIdx ^ maxSubpageAllocs 实现的效果其实就是 memoryMapIdx - maxSubpageAllocs
        // 这里的 memoryMapIdx 是所处 page 完全二叉树数组的 id
        // maxSubpageAllocs 是最多可分配的 page 数
        // todo 这两个相减可以得到 page 数组的 id 还是有点费解
        // 可以这样理解，maxSubpageAllocs 虽然是最多可分配的 page 数，但其实这个数字是等于
        // 完全二叉树数组的第一个 page 元素的 id 的，所以这样相减可以得到其 page 数组的下标
        // 比如说 2048 ^ 2048 = 0、2049 ^ 2048 = 1
        return memoryMapIdx ^ maxSubpageAllocs; // remove highest set bit, to get offset
    }

    private static int memoryMapIdx(long handle) {
        return (int) handle;
    }

    private static int bitmapIdx(long handle) {
        return (int) (handle >>> Integer.SIZE);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        synchronized (arena) {
            return freeBytes;
        }
    }

    @Override
    public String toString() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }
}
