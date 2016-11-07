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

import java.io.IOException;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import reactor.core.Cancellation;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.NettyState;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * @author Stephane Maldini
 */
final class ConnectHandler implements ChannelFutureListener, Cancellation {

	final MonoSink<NettyState> sink;
	final Cancellation         onClose;
	final ChannelFuture        f;
	final boolean              emitChannelState;

	ConnectHandler(ChannelFuture f,
			MonoSink<NettyState> sink,
			Cancellation onClose,
			boolean emitChannelState) {
		this.sink = sink;
		this.f = f;
		this.emitChannelState = emitChannelState;
		this.onClose = onClose;
	}

	@Override
	public void operationComplete(ChannelFuture f) throws Exception {
		if (log.isDebugEnabled()) {
			log.debug("CONNECT {} {}",
					f.isSuccess() ? "OK" : "FAILED",
					f.channel());
		}
		if (onClose != null) {
			f.channel()
			 .closeFuture()
			 .addListener(x -> onClose.dispose());
		}
		if (!f.isSuccess()) {
			if (f.cause() != null) {
				sink.error(f.cause());
			}
			else {
				sink.error(new IOException("error while connecting to " + f.channel()
				                                                           .remoteAddress()));
			}
		}
		else if (emitChannelState) {
			sink.success(NettyOperations.newNettyState(f.channel()));
		}
	}

	@Override
	public void dispose() {
		try {
			f.channel()
			 .close()
			 .sync();
			if (onClose != null) {
				onClose.dispose();
			}
		}
		catch (InterruptedException e) {
			log.error("error while disposing the channel", e);
		}
	}

	static final Logger log = Loggers.getLogger(ConnectHandler.class);
}
