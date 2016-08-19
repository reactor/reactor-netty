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
package reactor.ipc.netty.common;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.ipc.Inbound;

/**
 * @author Stephane Maldini
 */
public interface NettyInbound extends Inbound<ByteBuf> {

	@Override
	io.netty.channel.Channel delegate();

	/**
	 * Assign event handlers to certain channel lifecycle events.
	 *
	 * @return Lifecycle to build the events handlers
	 */
	NettyChannel.Lifecycle on();

	@Override
	default ByteBufEncodedFlux receive() {
		return new ByteBufEncodedFlux(receiveObject().map(objectMapper), delegate().alloc());
	}

	/**
	 * Get the inbound publisher (incoming tcp traffic for instance) and decode its traffic
	 *
	 * @param codec a decoding {@link NettyCodec} providing a target type {@link Publisher}
	 *
	 * @return A {@link Flux} to signal reads and stop reading when un-requested.
	 */
	default <NEW_IN> Flux<NEW_IN> receive(NettyCodec<NEW_IN, ?> codec) {
		return receive(codec.decoder());
	}

	/**
	 * a {@link ByteBuffer} inbound {@link Flux}
	 *
	 * @return a {@link ByteBuffer} inbound {@link Flux}
	 */
	default Flux<ByteBuffer> receiveByteBuffer() {
		return receive().map(ByteBuf::nioBuffer);
	}

	/**
	 * a {@literal byte[]} inbound {@link Flux}
	 *
	 * @return a {@literal byte[]} inbound {@link Flux}
	 */
	default Flux<byte[]> receiveByteArray() {
		return receive().map(bb -> {
			byte[] bytes = new byte[bb.readableBytes()];
			bb.readBytes(bytes);
			return bytes;
		});
	}


	/**
	 * a {@link InputStream} inbound {@link Flux}
	 *
	 * @return a {@link InputStream} inbound {@link Flux}
	 */
	default Flux<InputStream> receiveInputStream() {
		return receive().map(ReleasingBufferInputStream::new);
	}

	/**
	 * a {@literal Object} inbound {@link Flux}
	 *
	 * @return a {@literal Object} inbound {@link Flux}
	 */
	Flux<?> receiveObject();

	/**
	 * a {@link String} inbound {@link Flux}
	 *
	 * @return a {@link String} inbound {@link Flux}
	 */
	default Flux<String> receiveString() {
		return receiveString(Charset.defaultCharset());
	}

	/**
	 * a {@link String} inbound {@link Flux}
	 *
	 * @param charset the decoding charset
	 *
	 * @return a {@link String} inbound {@link Flux}
	 */
	default Flux<String> receiveString(Charset charset) {
		return receive().map(s -> s.toString(charset));
	}

	/**
	 * Get the address of the remote peer.
	 *
	 * @return the peer's address
	 */
	InetSocketAddress remoteAddress();

	/**
	 * A channel object to bytebuf transformer
	 */
	Function<Object, ByteBuf> objectMapper =  o -> {
		if(o instanceof ByteBuf){
			return (ByteBuf)o;
		}
		if(o instanceof ByteBufHolder){
			return ((ByteBufHolder)o).content();
		}
		throw new IllegalArgumentException("Object "+o+" of type "+o.getClass()+" " +
				"cannot be converted to ByteBuf");
	};

}
