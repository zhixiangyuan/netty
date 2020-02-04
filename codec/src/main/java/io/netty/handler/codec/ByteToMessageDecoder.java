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
package io.netty.handler.codec;

import static io.netty.util.internal.ObjectUtil.checkPositive;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.util.internal.StringUtil;

import java.util.List;

/**
 * {@link ChannelInboundHandlerAdapter} which decodes bytes in a stream-like fashion from one {@link ByteBuf} to an
 * other Message type.
 *
 * For example here is an implementation which reads all readable bytes from
 * the input {@link ByteBuf} and create a new {@link ByteBuf}.
 *
 * <pre>
 *     public class SquareDecoder extends {@link ByteToMessageDecoder} {
 *         {@code @Override}
 *         public void decode({@link ChannelHandlerContext} ctx, {@link ByteBuf} in, List&lt;Object&gt; out)
 *                 throws {@link Exception} {
 *             out.add(in.readBytes(in.readableBytes()));
 *         }
 *     }
 * </pre>
 *
 * <h3>Frame detection</h3>
 * <p>
 * Generally frame detection should be handled earlier in the pipeline by adding a
 * {@link DelimiterBasedFrameDecoder}, {@link FixedLengthFrameDecoder}, {@link LengthFieldBasedFrameDecoder},
 * or {@link LineBasedFrameDecoder}.
 * <p>
 * If a custom frame decoder is required, then one needs to be careful when implementing
 * one with {@link ByteToMessageDecoder}. Ensure there are enough bytes in the buffer for a
 * complete frame by checking {@link ByteBuf#readableBytes()}. If there are not enough bytes
 * for a complete frame, return without modifying the reader index to allow more bytes to arrive.
 * <p>
 * To check for complete frames without modifying the reader index, use methods like {@link ByteBuf#getInt(int)}.
 * One <strong>MUST</strong> use the reader index when using methods like {@link ByteBuf#getInt(int)}.
 * For example calling <tt>in.getInt(0)</tt> is assuming the frame starts at the beginning of the buffer, which
 * is not always the case. Use <tt>in.getInt(in.readerIndex())</tt> instead.
 * <h3>Pitfalls</h3>
 * <p>
 * Be aware that sub-classes of {@link ByteToMessageDecoder} <strong>MUST NOT</strong>
 * annotated with {@link @Sharable}.
 * <p>
 * Some methods such as {@link ByteBuf#readBytes(int)} will cause a memory leak if the returned buffer
 * is not released or added to the <tt>out</tt> {@link List}. Use derived buffers like {@link ByteBuf#readSlice(int)}
 * to avoid leaking memory.
 */
public abstract class ByteToMessageDecoder extends ChannelInboundHandlerAdapter {

    /**
     * Cumulate {@link ByteBuf}s by merge them into one {@link ByteBuf}'s, using memory copies.
     *
     * MERGE_CUMULATOR 思路是，不断使用老的 ByteBuf 累积。如果空间不够，扩容出新的 ByteBuf ，再继续进行累积。
     */
    public static final Cumulator MERGE_CUMULATOR = new Cumulator() {
        @Override
        public ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
            try {
                final ByteBuf buffer;
                if (
                    // 超过空间大小，需要扩容
                        cumulation.writerIndex() > cumulation.maxCapacity() - in.readableBytes()
                                // 引用大于 1 ，说明用户使用了 slice().retain() 或
                                // duplicate().retain() 使refCnt增加并且大于 1
                                // 此时扩容返回一个新的累积区 ByteBuf，方便用户对老的
                                // 累积区 ByteBuf 进行后续处理
                                || cumulation.refCnt() > 1
                                // 只读，不可累加，所以需要改成可写
                                || cumulation.isReadOnly()) {
                    // Expand cumulation (by replace it) when either there is not more room in the buffer
                    // or if the refCnt is greater then 1 which may happen when the user use slice().retain() or
                    // duplicate().retain() or if its read-only.
                    //
                    // See:
                    // - https://github.com/netty/netty/issues/2327
                    // - https://github.com/netty/netty/issues/1764
                    // 扩容，返回新的 buffer
                    buffer = expandCumulation(alloc, cumulation, in.readableBytes());
                } else {
                    // 使用老的 buffer
                    buffer = cumulation;
                }
                // 写入 in 到 buffer 中
                buffer.writeBytes(in);
                // 返回 buffer
                return buffer;
            } finally {
                // We must release in in all cases as otherwise it may produce a leak if writeBytes(...) throw
                // for whatever release (for example because of OutOfMemoryError)
                // 释放输入 in
                in.release();
            }
        }
    };

    /**
     * Cumulate {@link ByteBuf}s by add them to a {@link CompositeByteBuf} and so do no memory copy whenever possible.
     * Be aware that {@link CompositeByteBuf} use a more complex indexing implementation so depending on your use-case
     * and the decoder implementation this may be slower then just use the {@link #MERGE_CUMULATOR}.
     *
     * COMPOSITE_CUMULATOR 思路是，使用 CompositeByteBuf ，组合新输入的 ByteBuf 对象，从而避免内存拷贝
     *
     * 相比 MERGE_CUMULATOR 来说：
     *
     * 好处是，内存零拷贝
     * 坏处是，因为维护复杂索引，所以某些使用场景下，慢于 MERGE_CUMULATOR
     */
    public static final Cumulator COMPOSITE_CUMULATOR = new Cumulator() {
        @Override
        public ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
            ByteBuf buffer;
            try {
                // 和 MERGE_CUMULATOR 的情况类似
                if (cumulation.refCnt() > 1) {
                    // Expand cumulation (by replace it) when the refCnt is greater then 1 which may happen when the
                    // user use slice().retain() or duplicate().retain().
                    //
                    // See:
                    // - https://github.com/netty/netty/issues/2327
                    // - https://github.com/netty/netty/issues/1764
                    buffer = expandCumulation(alloc, cumulation, in.readableBytes());
                    buffer.writeBytes(in);
                } else {
                    CompositeByteBuf composite;
                    if (cumulation instanceof CompositeByteBuf) {
                        // 原来是 CompositeByteBuf 类型，直接使用
                        composite = (CompositeByteBuf) cumulation;
                    } else {
                        // 原来不是 CompositeByteBuf 类型，创建，并添加到其中
                        composite = alloc.compositeBuffer(Integer.MAX_VALUE);
                        composite.addComponent(true, cumulation);
                    }
                    // 添加 in 到 composite 中
                    composite.addComponent(true, in);
                    in = null;
                    // 赋值给 buffer
                    buffer = composite;
                }
                // 返回 buffer
                return buffer;
            } finally {
                if (in != null) {
                    // We must release if the ownership was not transferred as otherwise it may produce a leak if
                    // writeBytes(...) throw for whatever release (for example because of OutOfMemoryError).
                    // 为防止内存泄漏释放 in 的数据
                    in.release();
                }
            }
        }
    };

    private static final byte STATE_INIT = 0;
    private static final byte STATE_CALLING_CHILD_DECODE = 1;
    private static final byte STATE_HANDLER_REMOVED_PENDING = 2;

    ByteBuf cumulation;
    /** 已累积的 ByteBuf 对象，默认使用 {@link #MERGE_CUMULATOR} */
    private Cumulator cumulator = MERGE_CUMULATOR;
    /**
     * 是否每次只解码一条消息，默认为 false 。
     *
     * 部分解码器为 true ，例如：Socks4ClientDecoder
     *
     * @see #callDecode(ChannelHandlerContext, ByteBuf, List)
     */
    private boolean singleDecode;
    /**
     * 是否解码到消息。
     *
     * WasNull ，说明就是没解码到消息
     *
     * @see #channelReadComplete(ChannelHandlerContext)
     */
    private boolean decodeWasNull;
    /**
     * 是否首次读取，即 {@link #cumulation} 为空
     */
    private boolean first;
    /**
     * A bitmask where the bits are defined as
     * <ul>
     *     <li>{@link #STATE_INIT}</li>
     *     <li>{@link #STATE_CALLING_CHILD_DECODE}</li>
     *     <li>{@link #STATE_HANDLER_REMOVED_PENDING}</li>
     * </ul>
     *
     * 解码状态
     *
     * 0 - 初始化
     * 1 - 调用 {@link #decode(ChannelHandlerContext, ByteBuf, List)} 方法中，正在进行解码
     * 2 - 准备移除
     */
    private byte decodeState = STATE_INIT;
    /**
     * 读取释放阀值
     */
    private int discardAfterReads = 16;
    /**
     * 已读取次数。
     *
     * 再读取 {@link #discardAfterReads} 次数据后，如果无法全部解码完，则进行释放，避免 OOM
     */
    private int numReads;

    protected ByteToMessageDecoder() {
        // 校验，不可共享
        ensureNotSharable();
    }

    /**
     * If set then only one message is decoded on each {@link #channelRead(ChannelHandlerContext, Object)}
     * call. This may be useful if you need to do some protocol upgrade and want to make sure nothing is mixed up.
     *
     * Default is {@code false} as this has performance impacts.
     */
    public void setSingleDecode(boolean singleDecode) {
        this.singleDecode = singleDecode;
    }

    /**
     * If {@code true} then only one message is decoded on each
     * {@link #channelRead(ChannelHandlerContext, Object)} call.
     *
     * Default is {@code false} as this has performance impacts.
     */
    public boolean isSingleDecode() {
        return singleDecode;
    }

    /**
     * Set the {@link Cumulator} to use for cumulate the received {@link ByteBuf}s.
     */
    public void setCumulator(Cumulator cumulator) {
        if (cumulator == null) {
            throw new NullPointerException("cumulator");
        }
        this.cumulator = cumulator;
    }

    /**
     * Set the number of reads after which {@link ByteBuf#discardSomeReadBytes()} are called and so free up memory.
     * The default is {@code 16}.
     */
    public void setDiscardAfterReads(int discardAfterReads) {
        checkPositive(discardAfterReads, "discardAfterReads");
        this.discardAfterReads = discardAfterReads;
    }

    /**
     * Returns the actual number of readable bytes in the internal cumulative
     * buffer of this decoder. You usually do not need to rely on this value
     * to write a decoder. Use it only when you must use it at your own risk.
     * This method is a shortcut to {@link #internalBuffer() internalBuffer().readableBytes()}.
     */
    protected int actualReadableBytes() {
        return internalBuffer().readableBytes();
    }

    /**
     * Returns the internal cumulative buffer of this decoder. You usually
     * do not need to access the internal buffer directly to write a decoder.
     * Use it only when you must use it at your own risk.
     */
    protected ByteBuf internalBuffer() {
        if (cumulation != null) {
            return cumulation;
        } else {
            return Unpooled.EMPTY_BUFFER;
        }
    }

    @Override
    public final void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        // 状态处于 STATE_CALLING_CHILD_DECODE 时，标记状态为 STATE_HANDLER_REMOVED_PENDING
        if (decodeState == STATE_CALLING_CHILD_DECODE) {
            decodeState = STATE_HANDLER_REMOVED_PENDING;
            return; // 返回！！！！结合 `#decodeRemovalReentryProtection(...)` 方法，一起看。
        }
        ByteBuf buf = cumulation;
        if (buf != null) {
            // 置空 cumulation
            // Directly set this to null so we are sure we not access it in any other method here anymore.
            cumulation = null;

            int readable = buf.readableBytes();
            // 有可读字节
            if (readable > 0) {
                // 读取剩余字节，并释放 buf
                ByteBuf bytes = buf.readBytes(readable);
                buf.release();
                // 触发 Channel Read 到下一个节点
                ctx.fireChannelRead(bytes);
            }
            // 无可读字节
            else {
                // 释放 buf
                buf.release();
            }

            // 置空 numReads
            numReads = 0;
            // 触发 Channel ReadComplete 到下一个节点
            ctx.fireChannelReadComplete();
        }
        // 执行移除逻辑
        handlerRemoved0(ctx);
    }

    /**
     * Gets called after the {@link ByteToMessageDecoder} was removed from the actual context and it doesn't handle
     * events anymore.
     */
    protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception {
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            // 创建 CodecOutputList 对象
            CodecOutputList out = CodecOutputList.newInstance();
            try {
                ByteBuf data = (ByteBuf) msg;
                // 判断是否首次
                first = cumulation == null;
                // 若首次，直接使用读取的 data
                if (first) {
                    cumulation = data;
                }
                // 若非首次，将读取的 data ，累积到 cumulation 中
                else {
                    cumulation = cumulator.cumulate(ctx.alloc(), cumulation, data);
                }
                // 执行解码
                callDecode(ctx, cumulation, out);
            } catch (DecoderException e) {
                // 抛出异常
                throw e;
            } catch (Exception e) {
                // 封装成 DecoderException 异常，抛出
                throw new DecoderException(e);
            } finally {
                // cumulation 中所有数据被读取完，直接释放全部
                if (cumulation != null && !cumulation.isReadable()) {
                    // 重置 numReads 次数
                    numReads = 0;
                    // 释放 cumulation
                    cumulation.release();
                    // 置空 cumulation
                    cumulation = null;
                }
                // 读取次数到达 discardAfterReads 上限，释放部分的已读
                else if (++numReads >= discardAfterReads) {
                    // We did enough reads already try to discard some bytes so we not risk to see a OOME.
                    // See https://github.com/netty/netty/issues/4275
                    // 重置 numReads 次数
                    numReads = 0;
                    // 释放部分的已读
                    discardSomeReadBytes();
                }

                // 解码消息的数量
                int size = out.size();
                // 是否解码到消息
                decodeWasNull = !out.insertSinceRecycled();
                // 触发 Channel Read 事件。可能是多条消息
                fireChannelRead(ctx, out, size);
                // 回收 CodecOutputList 对象
                out.recycle();
            }
        } else {
            // 不是 ByteBuf 类型的消息，直接触发 ChannelRead 事件到下一个节点
            ctx.fireChannelRead(msg);
        }
    }

    /**
     * Get {@code numElements} out of the {@link List} and forward these through the pipeline.
     */
    static void fireChannelRead(ChannelHandlerContext ctx, List<Object> msgs, int numElements) {
        if (msgs instanceof CodecOutputList) {
            // 如果是 CodecOutputList 类型，特殊优化
            fireChannelRead(ctx, (CodecOutputList) msgs, numElements);
        } else {
            for (int i = 0; i < numElements; i++) {
                ctx.fireChannelRead(msgs.get(i));
            }
        }
    }

    /**
     * Get {@code numElements} out of the {@link CodecOutputList} and forward these through the pipeline.
     */
    static void fireChannelRead(ChannelHandlerContext ctx, CodecOutputList msgs, int numElements) {
        for (int i = 0; i < numElements; i++) {
            // getUnsafe 是自定义的方法，减少越界判断，效率更高
            ctx.fireChannelRead(msgs.getUnsafe(i));
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        // 重置 numReads
        numReads = 0;
        // 释放部分的已读
        discardSomeReadBytes();
        // 未解码到消息，并且未开启自动读取，则再次发起读取，期望读取到更多数据，以便解码到消息
        if (decodeWasNull) {
            // 重置 decodeWasNull
            decodeWasNull = false;
            if (!ctx.channel().config().isAutoRead()) {
                ctx.read();
            }
        }
        // 触发 Channel ReadComplete 事件到下一个节点
        ctx.fireChannelReadComplete();
    }

    protected final void discardSomeReadBytes() {
        // 如果用户使用了 slice().retain() 和 duplicate().retain() 使 refCnt > 1 ，表明
        // 该累积区还在被用户使用，丢弃数据可能导致用户的困惑，所以须确定用户不再使用该累积区的已读数据，此时才丢弃
        if (cumulation != null && !first && cumulation.refCnt() == 1) {
            // discard some bytes if possible to make more room in the
            // buffer but only if the refCnt == 1  as otherwise the user may have
            // used slice().retain() or duplicate().retain().
            //
            // See:
            // - https://github.com/netty/netty/issues/2327
            // - https://github.com/netty/netty/issues/1764
            cumulation.discardSomeReadBytes();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        channelInputClosed(ctx, true);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof ChannelInputShutdownEvent) {
            // The decodeLast method is invoked when a channelInactive event is encountered.
            // This method is responsible for ending requests in some situations and must be called
            // when the input has been shutdown.
            channelInputClosed(ctx, false);
        }
        // 继续传播 evt 到下一个节点
        super.userEventTriggered(ctx, evt);
    }

    private void channelInputClosed(ChannelHandlerContext ctx, boolean callChannelInactive) throws Exception {
        // 创建 CodecOutputList 对象
        CodecOutputList out = CodecOutputList.newInstance();
        try {
            // 当 Channel 读取关闭时，执行解码剩余消息的逻辑
            channelInputClosed(ctx, out);
        } catch (DecoderException e) {
            throw e;
        } catch (Exception e) {
            throw new DecoderException(e);
        } finally {
            try {
                // 释放 cumulation
                if (cumulation != null) {
                    cumulation.release();
                    cumulation = null;
                }
                int size = out.size();
                // 触发 Channel Read 事件到下一个节点。可能是多条消息
                fireChannelRead(ctx, out, size);
                // 如果有解码到消息，则触发 Channel ReadComplete 事件到下一个节点
                if (size > 0) {
                    // Something was read, call fireChannelReadComplete()
                    ctx.fireChannelReadComplete();
                }
                // 如果方法调用来源是 `#channelInactive(...)` ，则触发 Channel Inactive 事件到下一个节点
                if (callChannelInactive) {
                    ctx.fireChannelInactive();
                }
            } finally {
                // 回收 CodecOutputList 对象
                // Recycle in all cases
                out.recycle();
            }
        }
    }

    /**
     * Called when the input of the channel was closed which may be because it changed to inactive or because of
     * {@link ChannelInputShutdownEvent}.
     */
    void channelInputClosed(ChannelHandlerContext ctx, List<Object> out) throws Exception {
        if (cumulation != null) {
            // 执行解码
            callDecode(ctx, cumulation, out);
            // 最后一次，执行解码
            decodeLast(ctx, cumulation, out);
        } else {
            // 最后一次，执行解码
            decodeLast(ctx, Unpooled.EMPTY_BUFFER, out);
        }
    }

    /**
     * Called once data should be decoded from the given {@link ByteBuf}. This method will call
     * {@link #decode(ChannelHandlerContext, ByteBuf, List)} as long as decoding should take place.
     *
     * @param ctx the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in  the {@link ByteBuf} from which to read data
     * @param out the {@link List} to which decoded messages should be added
     */
    protected void callDecode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        try {
            // 循环读取，直到不可读
            while (in.isReadable()) {
                // 记录一下需要向下传递的 list 的大小
                int outSize = out.size();

                // out 非空，说明上一次解码有解码到消息
                if (outSize > 0) {
                    // 触发 Channel Read 事件。可能是多条消息
                    fireChannelRead(ctx, out, outSize);
                    // 清空 out
                    out.clear();

                    // Check if this handler was removed before continuing with decoding.
                    // If it was removed, it is not safe to continue to operate on the buffer.
                    //
                    // See:
                    // - https://github.com/netty/netty/issues/4635
                    // 如果此时用户主动删除该 Handler ，继续操作 in 是不安全的
                    if (ctx.isRemoved()) {
                        break;
                    }
                    outSize = 0;
                }

                // 先记录一下老的 inputLength
                // 记录当前可读字节数
                int oldInputLength = in.readableBytes();
                // 执行解码。如果 Handler 准备移除，在解码完成后，进行移除。
                decodeRemovalReentryProtection(ctx, in, out);

                // Check if this handler was removed before continuing the loop.
                // If it was removed, it is not safe to continue to operate on the buffer.
                //
                // See https://github.com/netty/netty/issues/1664
                if (ctx.isRemoved()) {
                    // 如果用户主动删除该 Handler ，继续操作 in 是不安全的
                    break;
                }

                // 如果需要向下传递的 outSize 的大小没有变化
                // 这里改成判断 `out.size() == 0` 比较合适
                // 因为，如果 `outSize > 0` 那段，已经清理了 out
                if (outSize == out.size()) {
                    // 如果 inputLength 没有发生变化
                    if (oldInputLength == in.readableBytes()) {
                        // 那么直接 break，这个时候说明需要被读取的数据的长度还不够
                        break;
                    } else {
                        // 如果可读字节发生变化，继续读取
                        continue;
                    }
                }

                // 如果解码了消息，但是可读字节数未变，抛出 DecoderException 异常。说明，有问题
                if (oldInputLength == in.readableBytes()) {
                    throw new DecoderException(
                            StringUtil.simpleClassName(getClass()) +
                                    ".decode() did not read anything but decoded a message.");
                }

                // 如果开启 singleDecode ，表示只解析一次，结束循环
                if (isSingleDecode()) {
                    break;
                }
            }
        } catch (DecoderException e) {
            throw e;
        } catch (Exception cause) {
            throw new DecoderException(cause);
        }
    }

    /**
     * Decode the from one {@link ByteBuf} to an other. This method will be called till either the input
     * {@link ByteBuf} has nothing to read when return from this method or till nothing was read from the input
     * {@link ByteBuf}.
     *
     * @param ctx the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in  the {@link ByteBuf} from which to read data
     * @param out the {@link List} to which decoded messages should be added
     * @throws Exception is thrown if an error occurs
     */
    protected abstract void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception;

    /**
     * Decode the from one {@link ByteBuf} to an other. This method will be called till either the input
     * {@link ByteBuf} has nothing to read when return from this method or till nothing was read from the input
     * {@link ByteBuf}.
     *
     * @param ctx the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in  the {@link ByteBuf} from which to read data
     * @param out the {@link List} to which decoded messages should be added
     * @throws Exception is thrown if an error occurs
     */
    final void decodeRemovalReentryProtection(ChannelHandlerContext ctx, ByteBuf in, List<Object> out)
            throws Exception {
        // 设置状态为 STATE_CALLING_CHILD_DECODE
        decodeState = STATE_CALLING_CHILD_DECODE;
        try {
            // 执行解码
            decode(ctx, in, out);
        } finally {
            // 判断是否准备移除
            boolean removePending = decodeState == STATE_HANDLER_REMOVED_PENDING;
            // 设置状态为 STATE_INIT
            decodeState = STATE_INIT;
            // 移除当前 Handler
            if (removePending) {
                handlerRemoved(ctx);
            }
        }
    }

    /**
     * Is called one last time when the {@link ChannelHandlerContext} goes in-active. Which means the
     * {@link #channelInactive(ChannelHandlerContext)} was triggered.
     *
     * By default this will just call {@link #decode(ChannelHandlerContext, ByteBuf, List)} but sub-classes may
     * override this for some special cleanup operation.
     */
    protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.isReadable()) {
            // Only call decode() if there is something left in the buffer to decode.
            // See https://github.com/netty/netty/issues/4386
            decodeRemovalReentryProtection(ctx, in, out);
        }
    }

    static ByteBuf expandCumulation(ByteBufAllocator alloc, ByteBuf cumulation, int readable) {
        // 记录老的 ByteBuf 对象
        ByteBuf oldCumulation = cumulation;
        // 分配新的 ByteBuf 对象
        cumulation = alloc.buffer(oldCumulation.readableBytes() + readable);
        // 将老的数据，写入到新的 ByteBuf 对象
        cumulation.writeBytes(oldCumulation);
        // 释放老的 ByteBuf 对象
        oldCumulation.release();
        // 返回新的 ByteBuf 对象
        return cumulation;
    }

    /**
     * Cumulate {@link ByteBuf}s.
     *
     * ByteBuf 累积器接口
     */
    public interface Cumulator {
        /**
         * Cumulate the given {@link ByteBuf}s and return the {@link ByteBuf} that holds the cumulated bytes.
         * The implementation is responsible to correctly handle the life-cycle of the given {@link ByteBuf}s and so
         * call {@link ByteBuf#release()} if a {@link ByteBuf} is fully consumed.
         *
         * @param alloc      ByteBuf 分配器
         * @param cumulation ByteBuf 当前累积结果
         * @param in         当前读取( 输入 ) ByteBuf
         * @return ByteBuf 新的累积结果
         */
        ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in);
    }
}
