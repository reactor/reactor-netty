/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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
import java.net.SocketAddress;

import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.logging.LoggingHandler;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.options.ServerOptions;

/**
 *
 * @author Stephane Maldini
 */
final class ServerContextHandler extends CloseableContextHandler<Channel>
		implements Connection {

	final ServerOptions serverOptions;

	ServerContextHandler(ChannelOperations.OnNew<Channel> channelOpFactory,
			ServerOptions options,
			MonoSink<Connection> sink,
			LoggingHandler loggingHandler,
			SocketAddress providedAddress) {
		super(channelOpFactory, options, sink, loggingHandler, providedAddress);
		this.serverOptions = options;
	}

	@Override
	protected void doStarted(Channel channel) {
		sink.success(this);
	}

	@Override
	public final void fireContextActive(Connection context) {
		//Ignore, child channels cannot trigger context innerActive
	}

	@Override
	public void fireContextError(Throwable err) {
		if (AbortedException.isConnectionReset(err)) {
			if (log.isDebugEnabled()) {
				log.error("Connection closed remotely", err);
			}
		}
		else if (log.isErrorEnabled()) {
			log.error("Handler failure while no child channelOperation was present", err);
		}
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
	public Connection onClose(Runnable onClose) {
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
		         .isActive();
	}

	@Override
	public void terminateChannel(Channel channel) {
		if (!f.channel()
		     .isActive()) {
			return;
		}
		if(!Connection.isPersistent(channel)){
			channel.close();
		}
	}

	@Override
	protected void doPipeline(Channel ch) {
		addSslAndLogHandlers(options, this, loggingHandler, true, getSNI(), ch.pipeline());
	}
}
