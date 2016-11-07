/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.ipc.netty.options;

import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ProtocolFamily;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.NetUtil;

/**
 * Encapsulates configuration options for http server.
 *
 * @author Stephane Maldini
 */
public class HttpServerOptions extends ServerOptions {

	/**
	 * Create a new server builder
	 * @return a new server builder
	 */
	public static HttpServerOptions create() {
		return new HttpServerOptions().daemon(false);
	}

	/**
	 * Create a server builder for listening on the current default localhost address
	 * (inet4 or inet6) and {@code port}.
	 * @param port the port to listen on.
	 * @return a new server builder
	 */
	public static HttpServerOptions on(int port) {
		return on(NetUtil.LOCALHOST.getHostAddress(), port);
	}
	/**
	 * Create a server builder for listening on the given {@code bindAddress} and port
	 * 0 which will resolve to the port 80.
	 * @param bindAddress the hostname to be resolved into an address to listen
	 * @return a new server builder
	 */
	public static HttpServerOptions on(String bindAddress) {
		return on(bindAddress, 80);
	}

	/**
	 * Create a server builder for listening on the given {@code bindAddress} and
	 * {@code port}.
	 * @param bindAddress the hostname to be resolved into an address to listen
	 * @param port the port to listen on.
	 * @return a new server builder
	 */
	public static HttpServerOptions on(String bindAddress, int port) {
		return new HttpServerOptions().listen(bindAddress, port)
		                              .daemon(false);
	}

	HttpServerOptions(){
	}

	HttpServerOptions(HttpServerOptions options){
		super(options);
	}

	@Override
	public HttpServerOptions afterChannelInit(Consumer<? super Channel> afterChannelInit) {
		super.afterChannelInit(afterChannelInit);
		return this;
	}

	@Override
	public HttpServerOptions backlog(int backlog) {
		super.backlog(backlog);
		return this;
	}

	@Override
	public HttpServerOptions daemon(boolean daemon) {
		super.daemon(daemon);
		return this;
	}

	@Override
	public HttpServerOptions duplicate() {
		super.duplicate();
		return this;
	}

	@Override
	public HttpServerOptions eventLoopSelector(Function<? super Boolean, ? extends EventLoopGroup> eventLoopSelector) {
		super.eventLoopSelector(eventLoopSelector);
		return this;
	}

	@Override
	public HttpServerOptions keepAlive(boolean keepAlive) {
		super.keepAlive(keepAlive);
		return this;
	}

	@Override
	public HttpServerOptions linger(int linger) {
		super.linger(linger);
		return this;
	}

	@Override
	public HttpServerOptions listen(int port) {
		super.listen(port);
		return this;
	}

	@Override
	public HttpServerOptions listen(String host, int port) {
		super.listen(host, port);
		return this;
	}

	@Override
	public HttpServerOptions listen(InetSocketAddress listenAddress) {
		super.listen(listenAddress);
		return this;
	}

	@Override
	public HttpServerOptions managed(boolean managed) {
		super.managed(managed);
		return this;
	}

	@Override
	public HttpServerOptions onChannelInit(Predicate<? super Channel> onChannelInit) {
		super.onChannelInit(onChannelInit);
		return this;
	}

	@Override
	public HttpServerOptions preferNative(boolean preferNative) {
		super.preferNative(preferNative);
		return this;
	}

	@Override
	public HttpServerOptions rcvbuf(int rcvbuf) {
		super.rcvbuf(rcvbuf);
		return this;
	}

	@Override
	public HttpServerOptions reuseAddr(boolean reuseAddr) {
		super.reuseAddr(reuseAddr);
		return this;
	}

	@Override
	public HttpServerOptions sndbuf(int sndbuf) {
		super.sndbuf(sndbuf);
		return this;
	}

	@Override
	public HttpServerOptions ssl(SslContextBuilder sslOptions) {
		super.ssl(sslOptions);
		return this;
	}

	@Override
	public HttpServerOptions sslConfigurer(Consumer<? super SslContextBuilder> sslConfigurer) {
		super.sslConfigurer(sslConfigurer);
		return this;
	}

	@Override
	public HttpServerOptions sslHandshakeTimeoutMillis(long sslHandshakeTimeoutMillis) {
		super.sslHandshakeTimeoutMillis(sslHandshakeTimeoutMillis);
		return this;
	}

	@Override
	public HttpServerOptions sslSelfSigned() {
		super.sslSelfSigned();
		return this;
	}

	@Override
	public HttpServerOptions tcpNoDelay(boolean tcpNoDelay) {
		super.tcpNoDelay(tcpNoDelay);
		return this;
	}

	@Override
	public HttpServerOptions timeoutMillis(long timeout) {
		super.timeoutMillis(timeout);
		return this;
	}

	/**
	 * @return Immutable {@link HttpServerOptions}
	 */
	public HttpServerOptions toImmutable(){
		return new ImmutableHttpServerOptions(this);
	}

	final static class ImmutableHttpServerOptions extends HttpServerOptions {

		public ImmutableHttpServerOptions(HttpServerOptions options) {
			super(options);
		}

		@Override
		public HttpServerOptions afterChannelInit(Consumer<? super Channel> afterChannelInit) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpServerOptions backlog(int backlog) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpServerOptions daemon(boolean daemon) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpServerOptions eventLoopSelector(Function<? super Boolean, ? extends EventLoopGroup> eventLoopGroup) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpServerOptions keepAlive(boolean keepAlive) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpServerOptions linger(int linger) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpServerOptions listen(int port) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpServerOptions listen(String host, int port) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpServerOptions listen(InetSocketAddress listenAddress) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpServerOptions managed(boolean managed) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpServerOptions multicastInterface(NetworkInterface iface) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpServerOptions onChannelInit(Predicate<? super Channel> onChannelInit) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpServerOptions preferNative(boolean preferNative) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpServerOptions protocolFamily(ProtocolFamily protocolFamily) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpServerOptions rcvbuf(int rcvbuf) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpServerOptions reuseAddr(boolean reuseAddr) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpServerOptions sndbuf(int sndbuf) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpServerOptions ssl(SslContextBuilder sslOptions) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpServerOptions sslConfigurer(Consumer<? super SslContextBuilder> consumer) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpServerOptions sslHandshakeTimeoutMillis(long sslHandshakeTimeoutMillis) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpServerOptions sslSelfSigned() {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpServerOptions tcpNoDelay(boolean tcpNoDelay) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpServerOptions timeoutMillis(long timeout) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpServerOptions toImmutable() {
			return this;
		}
	}

}
