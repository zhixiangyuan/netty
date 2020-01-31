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

import io.netty.util.internal.LongCounter;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;
import static java.lang.Math.max;
/**
 * 分配内存的竞技场，注意这是一个抽象类，实现类在它的内部
 * 注意：arena 是存在多线程竞争的情况的
 */
abstract class PoolArena<T> implements PoolArenaMetric {
    static final boolean HAS_UNSAFE = PlatformDependent.hasUnsafe();

    enum SizeClass {
        /** 0～512B，规则有 16B、32B、48B...480B、496B */
        Tiny,
        /** 512B、1K、2K、4K */
        Small,
        /** 8K、16K、32K */
        Normal
    }
    /** 相当于 512 除以 16，即 512 / 16 = 32 */
    static final int numTinySubpagePools = 512 >>> 4;

    final PooledByteBufAllocator parent;

    private final int maxOrder;
    final int pageSize;
    final int pageShifts;
    final int chunkSize;
    final int subpageOverflowMask;
    final int numSmallSubpagePools;
    final int directMemoryCacheAlignment;
    final int directMemoryCacheAlignmentMask;
    /**
     * tiny 类型的 PoolSubpage 数组
     *
     * 数组的每个元素，都是双向链表
     *
     * tinySubpagePools[0] 表示 16B ，tinySubpagePools[1] 表示 32B
     * 每个规格以 16B 进行增加
     *
     * 这里面初始化的时候只放入了 head，在初始化 subpage 后便加入到 head 后面
     */
    private final PoolSubpage<T>[] tinySubpagePools;
    /**
     * small 类型的 SubpagePools 数组
     *
     * 数组的每个元素，都是双向链表
     *
     * small 中的规格为 512B、1K、2K、4K
     *
     * 这里面初始化的时候只放入了 head，在初始化 subpage 后便加入到 head 后面
     */
    private final PoolSubpage<T>[] smallSubpagePools;

    private final PoolChunkList<T> q050;
    private final PoolChunkList<T> q025;
    private final PoolChunkList<T> q000;
    private final PoolChunkList<T> qInit;
    private final PoolChunkList<T> q075;
    private final PoolChunkList<T> q100;

    private final List<PoolChunkListMetric> chunkListMetrics;

    // Metrics for allocations and deallocations
    private long allocationsNormal;
    // We need to use the LongCounter here as this is not guarded via synchronized block.
    private final LongCounter allocationsTiny = PlatformDependent.newLongCounter();
    private final LongCounter allocationsSmall = PlatformDependent.newLongCounter();
    private final LongCounter allocationsHuge = PlatformDependent.newLongCounter();
    private final LongCounter activeBytesHuge = PlatformDependent.newLongCounter();

    private long deallocationsTiny;
    private long deallocationsSmall;
    private long deallocationsNormal;

    // We need to use the LongCounter here as this is not guarded via synchronized block.
    private final LongCounter deallocationsHuge = PlatformDependent.newLongCounter();

    // Number of thread caches backed by this arena.
    /**
     * 当前使用这个 arena 的线程数，每当生成一个 PoolThreadCache 的时候便执行加一，
     * 当 PoolThreadCache 执行 free 的时候便减一
     */
    final AtomicInteger numThreadCaches = new AtomicInteger();

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    protected PoolArena(PooledByteBufAllocator parent, int pageSize,
          int maxOrder, int pageShifts, int chunkSize, int cacheAlignment) {
        this.parent = parent;
        this.pageSize = pageSize;
        this.maxOrder = maxOrder;
        this.pageShifts = pageShifts;
        this.chunkSize = chunkSize;
        directMemoryCacheAlignment = cacheAlignment;
        // 这种 mask 是掩码，用来获取二进制后面的位数
        directMemoryCacheAlignmentMask = cacheAlignment - 1;
        // pageSize 默认是 0x00100000_00000000
        // pageSize - 1 = 0x00011111_11111111
        // 取反得 0x11111111_11111111_11100000_00000000
        // 这种 OverflowMask 也是掩码，用来获取二进制前面的掩码
        subpageOverflowMask = ~(pageSize - 1);
        // 在构造函数里面初始化 tinySubpagePools，其实就是 new 一个数组
        tinySubpagePools = newSubpagePoolArray(numTinySubpagePools);
        for (int i = 0; i < tinySubpagePools.length; i ++) {
            // 初始化双向循环链表的链表头
            tinySubpagePools[i] = newSubpagePoolHead(pageSize);
        }

        // 这里直接减 9 也是醉了，难道是默认 pageShifts 是 13 即 page 是 8K 所以这样减，然后就能得到 4 了么
        // 这里的 4 刚好是 small subpage pools 的规格
        numSmallSubpagePools = pageShifts - 9;
        // 在构造函数里面初始化 smallSubpagePools，其实就是 new 一个数组
        smallSubpagePools = newSubpagePoolArray(numSmallSubpagePools);
        for (int i = 0; i < smallSubpagePools.length; i ++) {
            // 初始化双向循环链表的链表头
            smallSubpagePools[i] = newSubpagePoolHead(pageSize);
        }

        q100 = new PoolChunkList<T>(this, null, 100, Integer.MAX_VALUE, chunkSize);
        q075 = new PoolChunkList<T>(this, q100, 75, 100, chunkSize);
        q050 = new PoolChunkList<T>(this, q075, 50, 100, chunkSize);
        q025 = new PoolChunkList<T>(this, q050, 25, 75, chunkSize);
        q000 = new PoolChunkList<T>(this, q025, 1, 50, chunkSize);
        qInit = new PoolChunkList<T>(this, q000, Integer.MIN_VALUE, 25, chunkSize);

        q100.prevList(q075);
        q075.prevList(q050);
        q050.prevList(q025);
        q025.prevList(q000);
        q000.prevList(null);
        qInit.prevList(qInit);

        List<PoolChunkListMetric> metrics = new ArrayList<PoolChunkListMetric>(6);
        metrics.add(qInit);
        metrics.add(q000);
        metrics.add(q025);
        metrics.add(q050);
        metrics.add(q075);
        metrics.add(q100);
        chunkListMetrics = Collections.unmodifiableList(metrics);
    }

    /** 创建 PoolSubpage 头节点，该链表默认为双向循环链表 */
    private PoolSubpage<T> newSubpagePoolHead(int pageSize) {
        PoolSubpage<T> head = new PoolSubpage<T>(pageSize);
        // 默认指向自己
        head.prev = head;
        // 默认指向自己
        head.next = head;
        return head;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpagePoolArray(int size) {
        return new PoolSubpage[size];
    }

    abstract boolean isDirect();

    /** 由于 arena 存在多线程竞争的可能性，所以这里将 cache 传进来 */
    PooledByteBuf<T> allocate(PoolThreadCache cache, int reqCapacity, int maxCapacity) {
        // 创建一个 PooledByteBuf
        PooledByteBuf<T> buf = newByteBuf(maxCapacity);
        // 通过 cache 为 buf 初始化内存地址之类的值
        allocate(cache, buf, reqCapacity);
        return buf;
    }

    /**
     * 这里由于 tiny 的大小是 <= 512 的，所以下面通过 >>> 4 来 除以 16 便可以计算出数组下标
     * 数组长度便是 32 位
     * 当 normCapacity = 16 时，return 1;
     * 当 normCapacity = 32 时，return 2;
     * 当 normCapacity = 48 时，return 3;
     */
    static int tinyIdx(int normCapacity) {
        return normCapacity >>> 4;
    }

    /** 计算 small 数组的索引 */
    static int smallIdx(int normCapacity) {
        // 如果是 small 的话，small 的规格为 512B、1024B、2048B、4096B
        int tableIdx = 0;
        // 对每个规格除以 2^10 可得
        // 512B、1024B、2048B、4096B
        // 2^10                      /
        // ----------------------------
        // 0   、1    、2    、4
        // 0000、0001 、0010 、0100
        int i = normCapacity >>> 10;
        // 下面如果 elemSize 不为 0 则进入循环
        while (i != 0) {
            // 对于四种情况分别可以算出结果
            //            0000、0001 、0010 、0100
            // tableIdx   0   、1    、2    、3
            i >>>= 1;
            tableIdx ++;
        }
        // 返回索引
        return tableIdx;
    }

    // capacity < pageSize
    // 就像注释写的，下面起到的效果就是 normCapacity < pageSize
    // pageSize 默认是 8192
    boolean isTinyOrSmall(int normCapacity) {
        return (normCapacity & subpageOverflowMask) == 0;
    }

    // normCapacity < 512
    static boolean isTiny(int normCapacity) {
        return (normCapacity & 0xFFFFFE00) == 0;
    }

    private void allocate(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity) {
        // 对请求的 reqCapacity 进行规格化，reqCapacity 是请求的 buf 容量的长度
        final int normCapacity = normalizeCapacity(reqCapacity);
        if (isTinyOrSmall(normCapacity)) { // capacity < pageSize
            // 请求的容量小于一页的大小则进入 if
            int tableIdx;
            PoolSubpage<T>[] table;
            boolean tiny = isTiny(normCapacity);
            if (tiny) { // < 512
                if (cache.allocateTiny(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    // 通过缓存去分配
                    return;
                }
                // 通过规格化的容量可以计算出 tinySubpagePools 中的 index
                tableIdx = tinyIdx(normCapacity);
                // 获取 tiny subpage 的池子
                table = tinySubpagePools;
            } else { // pageSize > normCapacity >= 512
                if (cache.allocateSmall(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    return;
                }
                // 通过规格化的容量可以计算出 smallSubpagePools 中的 index
                tableIdx = smallIdx(normCapacity);
                // 获取 small subpage 的池子
                table = smallSubpagePools;
            }

            // 需要注意的是数组中存放的第一个元素是 head
            final PoolSubpage<T> head = table[tableIdx];

            /**
             * Synchronize on the head. This is needed as {@link PoolChunk#allocateSubpage(int)} and
             * {@link PoolChunk#free(long)} may modify the doubly linked list as well.
             */
            synchronized (head) {
                final PoolSubpage<T> s = head.next;
                if (s != head) {
                    // s != head，等同于 head.next != next，说明存在除了头节点之外的别的节点
                    assert s.doNotDestroy && s.elemSize == normCapacity;
                    // 分配 Subpage 内存块
                    long handle = s.allocate();
                    assert handle >= 0;
                    // 初始化 Subpage 内存块到 PooledByteBuf 对象中
                    s.chunk.initBufWithSubpage(buf, null, handle, reqCapacity);
                    // 增加 allocationsTiny 或 allocationsSmall 计数
                    incTinySmallAllocation(tiny);
                    // 返回，因为已经分配成功
                    return;
                }
            }
            synchronized (this) {
                allocateNormal(buf, reqCapacity, normCapacity);
            }

            incTinySmallAllocation(tiny);
            return;
        }
        if (normCapacity <= chunkSize) {
            if (cache.allocateNormal(this, buf, reqCapacity, normCapacity)) {
                // was able to allocate out of the cache so move on
                // 通过 cache 去分配 buf，分配成功则返回
                return;
            }
            // 当上面通过缓存分配失败之后，就走下面去申请
            synchronized (this) {
                // 申请 Normal Page 内存块
                allocateNormal(buf, reqCapacity, normCapacity);
                // 增加 allocationsNormal
                ++allocationsNormal;
            }
        } else {
            // 申请 Huge Page 内存块
            // Huge allocations are never served via the cache so just call allocateHuge
            allocateHuge(buf, reqCapacity);
        }
    }

    // Method must be called inside synchronized(this) { ... } block
    private void allocateNormal(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        if (q050.allocate(buf, reqCapacity, normCapacity) || q025.allocate(buf, reqCapacity, normCapacity) ||
            q000.allocate(buf, reqCapacity, normCapacity) || qInit.allocate(buf, reqCapacity, normCapacity) ||
            q075.allocate(buf, reqCapacity, normCapacity)) {
            // 首次分配时由于 qInit、q075、q050、q025、q000 都是空的，所以没法进行分配
            // 分配失败时不会进入 if
            return;
        }

        // Add a new chunk.
        // 创建一个 chunk 进行内存分配
        // 这里的几个参数的数值
        // pageSize = 8192，也就是 8KB
        // maxOrder = 11
        // pageShifts = 13，2 的 13 次方也就是 8192
        // chunkSize = 16777216，也就是 16MB（16777216 / 1024 / 1024 即 16）
        PoolChunk<T> c = newChunk(pageSize, maxOrder, pageShifts, chunkSize);
        // 通过新创建的 chunk 进行分配
        boolean success = c.allocate(buf, reqCapacity, normCapacity);
        assert success;
        qInit.add(c);
    }

    private void incTinySmallAllocation(boolean tiny) {
        if (tiny) {
            allocationsTiny.increment();
        } else {
            allocationsSmall.increment();
        }
    }

    private void allocateHuge(PooledByteBuf<T> buf, int reqCapacity) {
        PoolChunk<T> chunk = newUnpooledChunk(reqCapacity);
        activeBytesHuge.add(chunk.chunkSize());
        buf.initUnpooled(chunk, reqCapacity);
        allocationsHuge.increment();
    }

    void free(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, int normCapacity, PoolThreadCache cache) {
        if (chunk.unpooled) {
            // chunk 是非池化的
            int size = chunk.chunkSize();
            destroyChunk(chunk);
            activeBytesHuge.add(-size);
            deallocationsHuge.increment();
        }
        // chunk 是池化的
        else {
            // 计算需要释放的缓存大小
            SizeClass sizeClass = sizeClass(normCapacity);
            if (cache != null &&
                    // 将该内存区段加入到缓存当中，如果加入成功直接返回
                    // 通过 cache.add 尝试释放
                    cache.add(this, chunk, nioBuffer, handle, normCapacity, sizeClass)) {
                // cached so not free it.
                return;
            }

            // 如果将内存区段加入到缓存失败，则会释放该块缓存，将该内存区段标记为未使用
            freeChunk(chunk, handle, sizeClass, nioBuffer, false);
        }
    }

    private SizeClass sizeClass(int normCapacity) {
        if (!isTinyOrSmall(normCapacity)) {
            return SizeClass.Normal;
        }
        return isTiny(normCapacity) ? SizeClass.Tiny : SizeClass.Small;
    }

    void freeChunk(PoolChunk<T> chunk, long handle, SizeClass sizeClass, ByteBuffer nioBuffer, boolean finalizer) {
        final boolean destroyChunk;
        synchronized (this) {
            // We only call this if freeChunk is not called because of the PoolThreadCache finalizer as otherwise this
            // may fail due lazy class-loading in for example tomcat.
            if (!finalizer) {
                switch (sizeClass) {
                    case Normal:
                        ++deallocationsNormal;
                        break;
                    case Small:
                        ++deallocationsSmall;
                        break;
                    case Tiny:
                        ++deallocationsTiny;
                        break;
                    default:
                        throw new Error();
                }
            }
            // 释放 chunk 中的连续空间
            destroyChunk = !chunk.parent.free(chunk, handle, nioBuffer);
        }
        if (destroyChunk) {
            // destroyChunk not need to be called while holding the synchronized lock.
            destroyChunk(chunk);
        }
    }

    /** 从子页池中取出需要的 subpage */
    PoolSubpage<T> findSubpagePoolHead(int elemSize) {
        int tableIdx;
        PoolSubpage<T>[] table;
        if (isTiny(elemSize)) { // < 512
            // 大小为 tiny 的话则将大小除以 16 即可得到索引
            // 写成二进制形式就是 >>> 4
            // 下面便是获取索引
            tableIdx = elemSize >>> 4;
            // 获取使用的 pool
            table = tinySubpagePools;
        } else {
            // 如果是 small 的话，small 的规格为 512B、1024B、2048B、4096B
            tableIdx = 0;
            // 对每个规格除以 2^10 可得
            // 512B、1024B、2048B、4096B
            // 2^10                      /
            // ----------------------------
            // 0   、1    、2    、4
            // 0000、0001 、0010 、0100
            elemSize >>>= 10;
            // 下面如果 elemSize 不为 0 则进入循环
            while (elemSize != 0) {
                // 对于四种情况分别可以算出结果
                //            0000、0001 、0010 、0100
                // tableIdx   0   、1    、2    、3
                elemSize >>>= 1;
                tableIdx ++;
            }
            // 获取使用的 pool
            table = smallSubpagePools;
        }
        // 从池中取出需要的 subpage
        return table[tableIdx];
    }

    /**
     * 这里对请求容量规格化的原因是为了后续在释放该 buf 的时候不需要真的释放
     * 而是直接丢到缓存里面即可
     *
     * @param reqCapacity
     * @return
     */
    int normalizeCapacity(int reqCapacity) {
        checkPositiveOrZero(reqCapacity, "reqCapacity");

        if (reqCapacity >= chunkSize) {
            // 如果请求的 capacity 比 chunkSize 还要大的话则直接返回
            // 这里之前是 return reqCapacity; 后来改成了下面这样
            return directMemoryCacheAlignment == 0 ? reqCapacity : alignCapacity(reqCapacity);
        }

        if (!isTiny(reqCapacity)) { // >= 512
            // 对于不是 Tiny 规格的内存大小进行下面的步骤对大小进行规格化
            // Doubled
            // 这里的 Doubled 并不是说 reqCapacity 经过下面的代码后会翻倍
            // 而是寻找 reqCapacity 的下一个 2 的幂次的数
            // 比如说 reqCapacity = 513，那么计算出来的 normalizedCapacity 为 1024
            // 比如说 reqCapacity = 1222，那么计算出来的 normalizedCapacity 为 2048

            int normalizedCapacity = reqCapacity;
            normalizedCapacity --;
            normalizedCapacity |= normalizedCapacity >>>  1;
            normalizedCapacity |= normalizedCapacity >>>  2;
            normalizedCapacity |= normalizedCapacity >>>  4;
            normalizedCapacity |= normalizedCapacity >>>  8;
            normalizedCapacity |= normalizedCapacity >>> 16;
            normalizedCapacity ++;

            if (normalizedCapacity < 0) {
                normalizedCapacity >>>= 1;
            }
            assert directMemoryCacheAlignment == 0 || (normalizedCapacity & directMemoryCacheAlignmentMask) == 0;

            return normalizedCapacity;
        }

        if (directMemoryCacheAlignment > 0) {
            return alignCapacity(reqCapacity);
        }

        // Quantum-spaced
        if ((reqCapacity & 15) == 0) {
            return reqCapacity;
        }

        // 这里的计算是找 16 的倍数
        // 比如 reqCapacity = 0，那么计算结果便是 16
        // 比如 reqCapacity = 2，那么计算结果便是 16
        // 比如 reqCapacity = 15，那么计算结果便是 16
        // 比如 reqCapacity = 16，那么计算结果便是 32
        // 比如 reqCapacity = 17，那么计算结果便是 32
        // 比如 reqCapacity = 32，那么计算结果便是 48
        // 比如 reqCapacity = 33，那么计算结果便是 48

        // 对于 Tiny 类型的内存大小，将其规格化为 16 的倍数的内存大小
        return (reqCapacity & ~15) + 16;
    }

    int alignCapacity(int reqCapacity) {
        int delta = reqCapacity & directMemoryCacheAlignmentMask;
        return delta == 0 ? reqCapacity : reqCapacity + directMemoryCacheAlignment - delta;
    }

    void reallocate(PooledByteBuf<T> buf, int newCapacity, boolean freeOldMemory) {
        if (newCapacity < 0 || newCapacity > buf.maxCapacity()) {
            throw new IllegalArgumentException("newCapacity: " + newCapacity);
        }

        int oldCapacity = buf.length;
        if (oldCapacity == newCapacity) {
            return;
        }

        PoolChunk<T> oldChunk = buf.chunk;
        ByteBuffer oldNioBuffer = buf.tmpNioBuf;
        long oldHandle = buf.handle;
        T oldMemory = buf.memory;
        int oldOffset = buf.offset;
        int oldMaxLength = buf.maxLength;
        int readerIndex = buf.readerIndex();
        int writerIndex = buf.writerIndex();

        allocate(parent.threadCache(), buf, newCapacity);
        if (newCapacity > oldCapacity) {
            memoryCopy(
                    oldMemory, oldOffset,
                    buf.memory, buf.offset, oldCapacity);
        } else if (newCapacity < oldCapacity) {
            if (readerIndex < newCapacity) {
                if (writerIndex > newCapacity) {
                    writerIndex = newCapacity;
                }
                memoryCopy(
                        oldMemory, oldOffset + readerIndex,
                        buf.memory, buf.offset + readerIndex, writerIndex - readerIndex);
            } else {
                readerIndex = writerIndex = newCapacity;
            }
        }

        buf.setIndex(readerIndex, writerIndex);

        if (freeOldMemory) {
            free(oldChunk, oldNioBuffer, oldHandle, oldMaxLength, buf.cache);
        }
    }

    @Override
    public int numThreadCaches() {
        return numThreadCaches.get();
    }

    @Override
    public int numTinySubpages() {
        return tinySubpagePools.length;
    }

    @Override
    public int numSmallSubpages() {
        return smallSubpagePools.length;
    }

    @Override
    public int numChunkLists() {
        return chunkListMetrics.size();
    }

    @Override
    public List<PoolSubpageMetric> tinySubpages() {
        return subPageMetricList(tinySubpagePools);
    }

    @Override
    public List<PoolSubpageMetric> smallSubpages() {
        return subPageMetricList(smallSubpagePools);
    }

    @Override
    public List<PoolChunkListMetric> chunkLists() {
        return chunkListMetrics;
    }

    private static List<PoolSubpageMetric> subPageMetricList(PoolSubpage<?>[] pages) {
        List<PoolSubpageMetric> metrics = new ArrayList<PoolSubpageMetric>();
        for (PoolSubpage<?> head : pages) {
            if (head.next == head) {
                continue;
            }
            PoolSubpage<?> s = head.next;
            for (;;) {
                metrics.add(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
        return metrics;
    }

    @Override
    public long numAllocations() {
        final long allocsNormal;
        synchronized (this) {
            allocsNormal = allocationsNormal;
        }
        return allocationsTiny.value() + allocationsSmall.value() + allocsNormal + allocationsHuge.value();
    }

    @Override
    public long numTinyAllocations() {
        return allocationsTiny.value();
    }

    @Override
    public long numSmallAllocations() {
        return allocationsSmall.value();
    }

    @Override
    public synchronized long numNormalAllocations() {
        return allocationsNormal;
    }

    @Override
    public long numDeallocations() {
        final long deallocs;
        synchronized (this) {
            deallocs = deallocationsTiny + deallocationsSmall + deallocationsNormal;
        }
        return deallocs + deallocationsHuge.value();
    }

    @Override
    public synchronized long numTinyDeallocations() {
        return deallocationsTiny;
    }

    @Override
    public synchronized long numSmallDeallocations() {
        return deallocationsSmall;
    }

    @Override
    public synchronized long numNormalDeallocations() {
        return deallocationsNormal;
    }

    @Override
    public long numHugeAllocations() {
        return allocationsHuge.value();
    }

    @Override
    public long numHugeDeallocations() {
        return deallocationsHuge.value();
    }

    @Override
    public  long numActiveAllocations() {
        long val = allocationsTiny.value() + allocationsSmall.value() + allocationsHuge.value()
                - deallocationsHuge.value();
        synchronized (this) {
            val += allocationsNormal - (deallocationsTiny + deallocationsSmall + deallocationsNormal);
        }
        return max(val, 0);
    }

    @Override
    public long numActiveTinyAllocations() {
        return max(numTinyAllocations() - numTinyDeallocations(), 0);
    }

    @Override
    public long numActiveSmallAllocations() {
        return max(numSmallAllocations() - numSmallDeallocations(), 0);
    }

    @Override
    public long numActiveNormalAllocations() {
        final long val;
        synchronized (this) {
            val = allocationsNormal - deallocationsNormal;
        }
        return max(val, 0);
    }

    @Override
    public long numActiveHugeAllocations() {
        return max(numHugeAllocations() - numHugeDeallocations(), 0);
    }

    @Override
    public long numActiveBytes() {
        long val = activeBytesHuge.value();
        synchronized (this) {
            for (int i = 0; i < chunkListMetrics.size(); i++) {
                for (PoolChunkMetric m: chunkListMetrics.get(i)) {
                    val += m.chunkSize();
                }
            }
        }
        return max(0, val);
    }

    protected abstract PoolChunk<T> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize);
    protected abstract PoolChunk<T> newUnpooledChunk(int capacity);
    protected abstract PooledByteBuf<T> newByteBuf(int maxCapacity);
    protected abstract void memoryCopy(T src, int srcOffset, T dst, int dstOffset, int length);
    protected abstract void destroyChunk(PoolChunk<T> chunk);

    @Override
    public synchronized String toString() {
        StringBuilder buf = new StringBuilder()
            .append("Chunk(s) at 0~25%:")
            .append(StringUtil.NEWLINE)
            .append(qInit)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 0~50%:")
            .append(StringUtil.NEWLINE)
            .append(q000)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 25~75%:")
            .append(StringUtil.NEWLINE)
            .append(q025)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 50~100%:")
            .append(StringUtil.NEWLINE)
            .append(q050)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 75~100%:")
            .append(StringUtil.NEWLINE)
            .append(q075)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 100%:")
            .append(StringUtil.NEWLINE)
            .append(q100)
            .append(StringUtil.NEWLINE)
            .append("tiny subpages:");
        appendPoolSubPages(buf, tinySubpagePools);
        buf.append(StringUtil.NEWLINE)
           .append("small subpages:");
        appendPoolSubPages(buf, smallSubpagePools);
        buf.append(StringUtil.NEWLINE);

        return buf.toString();
    }

    private static void appendPoolSubPages(StringBuilder buf, PoolSubpage<?>[] subpages) {
        for (int i = 0; i < subpages.length; i ++) {
            PoolSubpage<?> head = subpages[i];
            if (head.next == head) {
                continue;
            }

            buf.append(StringUtil.NEWLINE)
                    .append(i)
                    .append(": ");
            PoolSubpage<?> s = head.next;
            for (;;) {
                buf.append(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
    }

    @Override
    protected final void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            destroyPoolSubPages(smallSubpagePools);
            destroyPoolSubPages(tinySubpagePools);
            destroyPoolChunkLists(qInit, q000, q025, q050, q075, q100);
        }
    }

    private static void destroyPoolSubPages(PoolSubpage<?>[] pages) {
        for (PoolSubpage<?> page : pages) {
            page.destroy();
        }
    }

    private void destroyPoolChunkLists(PoolChunkList<T>... chunkLists) {
        for (PoolChunkList<T> chunkList: chunkLists) {
            chunkList.destroy(this);
        }
    }

    static final class HeapArena extends PoolArena<byte[]> {

        HeapArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
                int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
                    directMemoryCacheAlignment);
        }

        private static byte[] newByteArray(int size) {
            return PlatformDependent.allocateUninitializedArray(size);
        }

        @Override
        boolean isDirect() {
            return false;
        }

        @Override
        protected PoolChunk<byte[]> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize) {
            return new PoolChunk<byte[]>(this, newByteArray(chunkSize), pageSize, maxOrder, pageShifts, chunkSize, 0);
        }

        @Override
        protected PoolChunk<byte[]> newUnpooledChunk(int capacity) {
            return new PoolChunk<byte[]>(this, newByteArray(capacity), capacity, 0);
        }

        @Override
        protected void destroyChunk(PoolChunk<byte[]> chunk) {
            // Rely on GC.
        }

        @Override
        protected PooledByteBuf<byte[]> newByteBuf(int maxCapacity) {
            return HAS_UNSAFE ? PooledUnsafeHeapByteBuf.newUnsafeInstance(maxCapacity)
                    : PooledHeapByteBuf.newInstance(maxCapacity);
        }

        @Override
        protected void memoryCopy(byte[] src, int srcOffset, byte[] dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            System.arraycopy(src, srcOffset, dst, dstOffset, length);
        }
    }

    static final class DirectArena extends PoolArena<ByteBuffer> {

        DirectArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
                int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
                    directMemoryCacheAlignment);
        }

        @Override
        boolean isDirect() {
            return true;
        }

        // mark as package-private, only for unit test
        int offsetCacheLine(ByteBuffer memory) {
            // We can only calculate the offset if Unsafe is present as otherwise directBufferAddress(...) will
            // throw an NPE.
            int remainder = HAS_UNSAFE
                    ? (int) (PlatformDependent.directBufferAddress(memory) & directMemoryCacheAlignmentMask)
                    : 0;

            // offset = alignment - address & (alignment - 1)
            return directMemoryCacheAlignment - remainder;
        }

        @Override
        protected PoolChunk<ByteBuffer> newChunk(int pageSize, int maxOrder,
                int pageShifts, int chunkSize) {
            if (directMemoryCacheAlignment == 0) {
                return new PoolChunk<ByteBuffer>(this,
                        // 通过 JDK 的 api 去申请堆外内存
                        allocateDirect(chunkSize),
                        pageSize, maxOrder,
                        pageShifts, chunkSize, 0);
            }
            final ByteBuffer memory = allocateDirect(chunkSize
                    + directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, memory, pageSize,
                    maxOrder, pageShifts, chunkSize,
                    offsetCacheLine(memory));
        }

        @Override
        protected PoolChunk<ByteBuffer> newUnpooledChunk(int capacity) {
            if (directMemoryCacheAlignment == 0) {
                return new PoolChunk<ByteBuffer>(this,
                        allocateDirect(capacity), capacity, 0);
            }
            final ByteBuffer memory = allocateDirect(capacity
                    + directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, memory, capacity,
                    offsetCacheLine(memory));
        }

        private static ByteBuffer allocateDirect(int capacity) {
            // 通过 JDK 的方法来直接申请 ByteBuffer 即直接内存
            return PlatformDependent.useDirectBufferNoCleaner() ?
                    PlatformDependent.allocateDirectNoCleaner(capacity) : ByteBuffer.allocateDirect(capacity);
        }

        @Override
        protected void destroyChunk(PoolChunk<ByteBuffer> chunk) {
            if (PlatformDependent.useDirectBufferNoCleaner()) {
                PlatformDependent.freeDirectNoCleaner(chunk.memory);
            } else {
                PlatformDependent.freeDirectBuffer(chunk.memory);
            }
        }

        @Override
        protected PooledByteBuf<ByteBuffer> newByteBuf(int maxCapacity) {
            if (HAS_UNSAFE) {
                return PooledUnsafeDirectByteBuf.newInstance(maxCapacity);
            } else {
                return PooledDirectByteBuf.newInstance(maxCapacity);
            }
        }

        @Override
        protected void memoryCopy(ByteBuffer src, int srcOffset, ByteBuffer dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            if (HAS_UNSAFE) {
                PlatformDependent.copyMemory(
                        PlatformDependent.directBufferAddress(src) + srcOffset,
                        PlatformDependent.directBufferAddress(dst) + dstOffset, length);
            } else {
                // We must duplicate the NIO buffers because they may be accessed by other Netty buffers.
                src = src.duplicate();
                dst = dst.duplicate();
                src.position(srcOffset).limit(srcOffset + length);
                dst.position(dstOffset);
                dst.put(src);
            }
        }
    }
}
