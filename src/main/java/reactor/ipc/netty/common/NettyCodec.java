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

import java.nio.charset.Charset;
import java.util.Objects;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.ipc.buffer.Buffer;
import reactor.ipc.codec.Codec;
import reactor.ipc.codec.DelimitedCodec;
import reactor.ipc.codec.StandardCodecs;
import reactor.ipc.codec.StringCodec;
import reactor.ipc.codec.compress.GzipCodec;
import reactor.ipc.codec.json.JsonCodec;

/**
 * @author Stephane Maldini
 * @since 2.5
 */
public final class NettyCodec<IN, OUT> {

	static {
		try {
			NettyCodec.class.getClassLoader()
			          .loadClass("reactor.ipc.codec.Codec");
		}
		catch (ClassNotFoundException cfne){
			throw new IllegalStateException("io.projectreactor.ipc:reactor-codec dependency is missing from the classpath" +
					".");
		}
	}

	/**
	 *
	 * @return
	 */
	static public NettyCodec<String, String> delimitedString(){
		return from(StandardCodecs.DELIMITED_STRING_CODEC);
	}

	/**
	 *
	 * @param charset
	 * @return
	 */
	static public NettyCodec<String, String> delimitedString(Charset charset){
		return delimitedString(charset, Codec.DEFAULT_DELIMITER);
	}

	/**
	 *
	 * @param charset
	 * @param delimiter
	 * @return
	 */
	static public NettyCodec<String, String> delimitedString(Charset charset, byte delimiter){
		return from(new DelimitedCodec<>(new StringCodec(delimiter, charset)));
	}

	/**
	 *
	 * @param codec
	 * @param <IN>
	 * @param <OUT>
	 * @return
	 */
	static public <IN,OUT> NettyCodec<IN, OUT> from(Codec<Buffer, IN, OUT> codec){
		return new NettyCodec<>(codec);
	}

	/**
	 *
	 * @return
	 */
	static public NettyCodec<Buffer, Buffer> gzip(){
		return from(new GzipCodec<>(StandardCodecs.PASS_THROUGH_CODEC));
	}

	/**
	 *
	 * @param tClass
	 * @param <T>
	 *
	 * @return
	 */
	static public <T> NettyCodec<T, T> json(Class<T> tClass){
		return from(new JsonCodec<T, T>(tClass));
	}

	/**
	 *
	 * @return
	 */
	static public NettyCodec<String, String> linefeed(){
		return from(StandardCodecs.LINE_FEED_CODEC);
	}

	/**
	 *
	 * @param charset
	 * @return
	 */
	static public NettyCodec<String, String> linefeed(Charset charset){
		return from(new DelimitedCodec<>(new StringCodec(charset)));
	}

	/**
	 *
	 * @param charset
	 * @param delimiter
	 * @return
	 */
	static public NettyCodec<String, String> linefeed(Charset charset, byte delimiter){
		return linefeed(charset, delimiter, true);
	}

	/**
	 *
	 * @param charset
	 * @param delimiter
	 * @param stripDelimiter
	 * @return
	 */
	static public NettyCodec<String, String> linefeed(Charset charset, byte delimiter, boolean stripDelimiter){
		return from(new DelimitedCodec<>(delimiter, stripDelimiter, new StringCodec(charset)));
	}

	/**
	 *
	 * @param charset
	 * @return
	 */
	static public NettyCodec<String, String> string(Charset charset){
		return from(new StringCodec(charset));
	}


	private final Codec<Buffer, IN, OUT> codec;

	private NettyCodec(
			Codec<Buffer, IN, OUT> codec
	) {
		this.codec = Objects.requireNonNull(codec, "Delegate codec cannot be null");
	}

	/**
	 *
	 * @return
	 */
	public Function<? super Publisher<ByteBuf>, ? extends Publisher<IN>> decoder() {
		return flux -> codec.decode(Flux.from(flux)
		                                .map(bb -> new Buffer(bb.nioBuffer())));
	}

	/**
	 *
	 * @return
	 */
	public Function<? super Flux<? extends OUT>, ? extends Publisher<ByteBuf>> encoder() {
		return flux -> codec.encode(flux).map(b -> Unpooled.wrappedBuffer(b.byteBuffer()));
	}
}
