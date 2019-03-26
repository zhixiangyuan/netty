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

import io.netty.util.internal.ObjectUtil;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Iterator;

/**
 * {@link SocketAddress} which will provide the local {@link SocketAddress} to bind to after the remote address
 * is resolved and so can be used with {@link Bootstrap#connect(SocketAddress, SocketAddress)}.
 */
public abstract class DynamicLocalSocketAddress extends SocketAddress {

    /**
     * Return a {@link SocketAddress} to bind to for the given {@code remote} {@link SocketAddress} or {@code null}
     * if no specific should be used.
     */
    public abstract SocketAddress address(SocketAddress remote);

    public static DynamicLocalSocketAddress networkInterface(NetworkInterface networkInterface, int port) {
        return new DynamicInetSocketAddress(networkInterface, port);
    }

    public static DynamicLocalSocketAddress any(Iterable<? extends InetSocketAddress> iterable) {
        return new DynamicInetSocketAddress(iterable);
    }

    public static DynamicLocalSocketAddress either(InetSocketAddress inet4Address, InetSocketAddress inet6Address) {
        if (!(inet4Address.getAddress() instanceof Inet4Address)) {
            throw new IllegalArgumentException();
        }
        if (!(inet6Address.getAddress() instanceof Inet6Address)) {
            throw new IllegalArgumentException();
        }

        return new DynamicInetSocketAddress(Arrays.asList(inet4Address, inet6Address));
    }

    private static final class DynamicInetSocketAddress extends DynamicLocalSocketAddress {
        private final Iterable<? extends InetSocketAddress> iterable;

        DynamicInetSocketAddress(final NetworkInterface networkInterface, final int port) {
            this(new Iterable<InetSocketAddress>() {
                @Override
                public Iterator<InetSocketAddress> iterator() {
                    return new EnumerationIterator(networkInterface.getInetAddresses(), port);
                }
            });
            ObjectUtil.checkNotNull(networkInterface, "networkInterface");
        }

        DynamicInetSocketAddress(Iterable<? extends InetSocketAddress> iterable) {
            this.iterable = ObjectUtil.checkNotNull(iterable, "iterable");
        }

        @Override
        public SocketAddress address(SocketAddress remote) {
            if (!(remote instanceof InetSocketAddress)) {
                throw new IllegalArgumentException("Only InetSocketAddress is supported");
            }

            return inetSocketAddress0(((InetSocketAddress) remote).getAddress().getClass());
        }

        private InetSocketAddress inetSocketAddress0(Class<? extends InetAddress> clazz) {
            for (InetSocketAddress address: iterable) {
                if (clazz.isInstance(address.getAddress())) {
                    return address;
                }
            }
            return null;
        }

        private static final class EnumerationIterator implements Iterator<InetSocketAddress>  {

            private final Enumeration<? extends InetAddress> enumeration;
            private final int port;

            EnumerationIterator(Enumeration<? extends InetAddress> enumeration, int port) {
                this.enumeration = enumeration;
                this.port = port;
            }

            @Override
            public boolean hasNext() {
                return enumeration.hasMoreElements();
            }

            @Override
            public InetSocketAddress next() {
                return new InetSocketAddress(enumeration.nextElement(), port);
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        }
    }

}
