/*
 * Copyright 2016 The Netty Project
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
package io.netty.util.concurrent;

import io.netty.util.internal.UnstableApi;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default implementation which uses simple round-robin to choose next {@link EventExecutor}.
 */
@UnstableApi
public final class DefaultEventExecutorChooserFactory implements EventExecutorChooserFactory {
    /** 单例 */
    public static final DefaultEventExecutorChooserFactory INSTANCE = new DefaultEventExecutorChooserFactory();

    private DefaultEventExecutorChooserFactory() { }

    @SuppressWarnings("unchecked")
    @Override
    public EventExecutorChooser newChooser(EventExecutor[] executors) {
        // 判断 executors 的长度是否是 2 的幂
        if (isPowerOfTwo(executors.length)) {
            // 如果是 2 的幂那么则创建一个 PowerOfTwoEventExecutorChooser
            // PowerOfTwoEventExecutorChooser 是一个 netty 优化过的 chooser
            return new PowerOfTwoEventExecutorChooser(executors);
        } else {
            // 如果不是 2 的幂则创建一个 GenericEventExecutorChooser
            // GenericEventExecutorChooser 是一个普通的 Chooser
            return new GenericEventExecutorChooser(executors);
        }
    }

    /** 是否为 2 的幂次方 */
    private static boolean isPowerOfTwo(int val) {
        // 这个路子有点野
        return (val & -val) == val;
    }

    private static final class PowerOfTwoEventExecutorChooser implements EventExecutorChooser {
        /** 自增序列 */
        private final AtomicInteger idx = new AtomicInteger();
        /** EventExecutor 数组 */
        private final EventExecutor[] executors;

        PowerOfTwoEventExecutorChooser(EventExecutor[] executors) {
            this.executors = executors;
        }

        @Override
        public EventExecutor next() {
            // 注意这里是先 -1 再求 &
            return executors[idx.getAndIncrement() & (executors.length - 1)];
        }
    }

    private static final class GenericEventExecutorChooser implements EventExecutorChooser {
        private final AtomicInteger idx = new AtomicInteger();
        private final EventExecutor[] executors;

        GenericEventExecutorChooser(EventExecutor[] executors) {
            this.executors = executors;
        }

        @Override
        public EventExecutor next() {
            // Math.abs(idx.getAndIncrement() % executors.length) 的作用便是选择从 0 到最后一个
            // 然后再从 0 开始
            return executors[Math.abs(idx.getAndIncrement() % executors.length)];
        }
    }
}
