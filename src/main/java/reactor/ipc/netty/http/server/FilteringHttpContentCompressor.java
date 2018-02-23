/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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
package reactor.ipc.netty.http.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * @author mostroverkhov
 */
final class FilteringHttpContentCompressor extends HttpContentCompressor {

	FilteringHttpContentCompressor() {
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
			throws Exception {
		if (msg instanceof FilterMessage) {
			FilterMessage filterMsg = (FilterMessage) msg;
			ctx.write(filterMsg.unwrap(), promise);
		}
		else {
			if (msg instanceof ByteBuf) {
				msg = new DefaultHttpContent((ByteBuf) msg);
			}
			super.write(ctx, msg, promise);
		}
	}

	static final class FilterMessage {

		private final Object message;

		static FilterMessage wrap(Object msg) {
			return new FilterMessage(msg);
		}

		FilterMessage(Object message) {
			this.message = message;
		}

		Object unwrap() {
			return message;
		}
	}
}
