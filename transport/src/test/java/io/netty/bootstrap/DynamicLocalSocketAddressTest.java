/*
 * Copyright 2019 The Netty Project
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
package io.netty.bootstrap;

import io.netty.util.NetUtil;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class DynamicLocalSocketAddressTest {

    @Test
    public void testEither() {
        DynamicLocalSocketAddress address = DynamicLocalSocketAddress.either(
                new InetSocketAddress(NetUtil.LOCALHOST4, 9999), new InetSocketAddress(NetUtil.LOCALHOST6, 9999));

        assertEquals(new InetSocketAddress(NetUtil.LOCALHOST4, 9999),
                address.address(new InetSocketAddress(NetUtil.LOCALHOST4, 0)));

        assertEquals(new InetSocketAddress(NetUtil.LOCALHOST6, 9999),
                address.address(new InetSocketAddress(NetUtil.LOCALHOST6, 0)));
    }

    @Test
    public void testAny() {
        DynamicLocalSocketAddress address = DynamicLocalSocketAddress.any(Arrays.asList(
                new InetSocketAddress(NetUtil.LOCALHOST4, 9999), new InetSocketAddress(NetUtil.LOCALHOST6, 9999)));

        assertEquals(new InetSocketAddress(NetUtil.LOCALHOST4, 9999),
                address.address(new InetSocketAddress(NetUtil.LOCALHOST4, 0)));

        assertEquals(new InetSocketAddress(NetUtil.LOCALHOST6, 9999),
                address.address(new InetSocketAddress(NetUtil.LOCALHOST6, 0)));
    }
}
