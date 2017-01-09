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

package reactor.ipc.netty.channel;

import java.net.InetSocketAddress;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.logging.LoggingHandler;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.FutureMono;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.options.ServerOptions;

/**
 *
 * @author Stephane Maldini
 */
final class ServerContextHandler extends CloseableContextHandler<Channel>
		implements NettyContext {

	final ServerOptions serverOptions;

	ServerContextHandler(ChannelOperations.OnNew<Channel> channelOpFactory,
			ServerOptions options,
			MonoSink<NettyContext> sink,
			LoggingHandler loggingHandler) {
		super(channelOpFactory, options, sink, loggingHandler);
		this.serverOptions = options;
	}

	@Override
	protected void doStarted(Channel channel) {
		sink.success(this);
	}

	@Override
	public final void fireContextActive(NettyContext context) {
		//Ignore, child channels cannot trigger context innerActive
	}

	@Override
	public void fireContextError(Throwable t) {
		//Ignore, child channels cannot trigger context error
	}

	@Override
	public InetSocketAddress address() {
		Channel c = f.channel();
		if (c instanceof SocketChannel) {
			return ((SocketChannel) c).remoteAddress();
		}
		if (c instanceof ServerSocketChannel) {
			return ((ServerSocketChannel) c).localAddress();
		}
		if (c instanceof DatagramChannel) {
			return ((DatagramChannel) c).localAddress();
		}
		throw new IllegalStateException("Does not have an InetSocketAddress");
	}

	@Override
	public NettyContext addHandler(String name, ChannelHandler handler) {
		if(channel().pipeline().context(name) == null) {
			channel().pipeline()
			         .addLast(name, handler);
		}
		return this;
	}

	@Override
	public NettyContext onClose(Runnable onClose) {
		onClose().subscribe(null, e -> onClose.run(), onClose);
		return this;
	}

	@Override
	public Channel channel() {
		return f.channel();
	}

	@Override
	public boolean isDisposed() {
		return !f.channel()
		         .isOpen();
	}

	@Override
	public Mono<Void> onClose() {
		return FutureMono.from(f.channel()
		                        .closeFuture());
	}

	@Override
	public void terminateChannel(Channel channel) {
		if (!f.channel()
		     .isOpen()) {
			return;
		}
		if(channel.hasAttr(CLOSE_CHANNEL)){
			channel.close();
		}
	}

	@Override
	protected void doPipeline(ChannelPipeline pipeline) {
		addSslAndLogHandlers(options, sink, loggingHandler, true, pipeline);
	}
}
