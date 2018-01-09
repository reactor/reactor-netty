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
package reactor.ipc.netty.http.server;

import java.util.function.Function;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import reactor.ipc.netty.tcp.TcpServer;

/**
 * A configuration builder to fine tune the {@link io.netty.handler.codec.http.HttpServerCodec}
 * (or more precisely the {@link io.netty.handler.codec.http.HttpServerCodec.HttpServerRequestDecoder}).
 * <p>
 * Defaults are accessible as constants {@link #DEFAULT_MAX_INITIAL_LINE_LENGTH}, {@link #DEFAULT_MAX_HEADER_SIZE}
 * and {@link #DEFAULT_MAX_CHUNK_SIZE}.
 *
 * @author Simon Baslé
 */
public final class HttpRequestDecoderConfiguration {

	public static final int DEFAULT_MAX_INITIAL_LINE_LENGTH = 4096;
	public static final int DEFAULT_MAX_HEADER_SIZE         = 8192;
	public static final int DEFAULT_MAX_CHUNK_SIZE          = 8192;
	public static final boolean DEFAULT_VALIDATE_HEADERS    = true;
	public static final int DEFAULT_INITIAL_BUFFER_SIZE     = 128;

	static final AttributeKey<Integer> MAX_INITIAL_LINE_LENGTH = AttributeKey.newInstance("httpCodecMaxInitialLineLength");
	static final AttributeKey<Integer> MAX_HEADER_SIZE         = AttributeKey.newInstance("httpCodecMaxHeaderSize");
	static final AttributeKey<Integer> MAX_CHUNK_SIZE          = AttributeKey.newInstance("httpCodecMaxChunkSize");
	static final AttributeKey<Boolean> VALIDATE_HEADERS        = AttributeKey.newInstance("httpCodecValidateHeaders");
	static final AttributeKey<Integer> INITIAL_BUFFER_SIZE     = AttributeKey.newInstance("httpCodecInitialBufferSize");

	int maxInitialLineLength = DEFAULT_MAX_INITIAL_LINE_LENGTH;
	int maxHeaderSize        = DEFAULT_MAX_HEADER_SIZE;
	int maxChunkSize         = DEFAULT_MAX_CHUNK_SIZE;
	boolean validateHeaders  = DEFAULT_VALIDATE_HEADERS;
	int initialBufferSize    = DEFAULT_INITIAL_BUFFER_SIZE;

	/**
	 * Configure the maximum length that can be decoded for the HTTP request's initial
	 * line. Defaults to {@link #DEFAULT_MAX_INITIAL_LINE_LENGTH}.
	 *
	 * @param value the value for the maximum initial line length (strictly positive)
	 * @return this option builder for further configuration
	 */
	public HttpRequestDecoderConfiguration maxInitialLineLength(int value) {
		if (value <= 0) {
			throw new IllegalArgumentException(
					"maxInitialLineLength must be strictly positive");
		}
		this.maxInitialLineLength = value;
		return this;
	}

	/**
	 * Configure the maximum header size that can be decoded for the HTTP request.
	 * Defaults to {@link #DEFAULT_MAX_HEADER_SIZE}.
	 *
	 * @param value the value for the maximum header size (strictly positive)
	 * @return this option builder for further configuration
	 */
	public HttpRequestDecoderConfiguration maxHeaderSize(int value) {
		if (value <= 0) {
			throw new IllegalArgumentException("maxHeaderSize must be strictly positive");
		}
		this.maxHeaderSize = value;
		return this;
	}

	/**
	 * Configure the maximum chunk size that can be decoded for the HTTP request.
	 * Defaults to {@link #DEFAULT_MAX_CHUNK_SIZE}.
	 *
	 * @param value the value for the maximum chunk size (strictly positive)
	 * @return this option builder for further configuration
	 */
	public HttpRequestDecoderConfiguration maxChunkSize(int value) {
		if (value <= 0) {
			throw new IllegalArgumentException("maxChunkSize must be strictly positive");
		}
		this.maxChunkSize = value;
		return this;
	}

	/**
	 * Configure whether or not to validate headers when decoding requests. Defaults to
	 * #DEFAULT_VALIDATE_HEADERS.
	 *
	 * @param validate true to validate headers, false otherwise
	 * @return this option builder for further configuration
	 */
	public HttpRequestDecoderConfiguration validateHeaders(boolean validate) {
		this.validateHeaders = validate;
		return this;
	}

	/**
	 * Configure the initial buffer size for HTTP request decoding. Defaults to
	 * {@link #DEFAULT_INITIAL_BUFFER_SIZE}.
	 *
	 * @param value the initial buffer size to use (strictly positive)
	 * @return
	 */
	public HttpRequestDecoderConfiguration initialBufferSize(int value) {
		if (value <= 0) {
			throw new IllegalArgumentException("initialBufferSize must be strictly positive");
		}
		this.initialBufferSize = value;
		return this;
	}

	/**
	 * Build a {@link Function} that applies the http request decoder configuration to a
	 * {@link TcpServer} by enriching its attributes.
	 */
	Function<TcpServer, TcpServer> build() {
		return tcp -> tcp.attr(MAX_INITIAL_LINE_LENGTH, maxInitialLineLength)
		                 .attr(MAX_HEADER_SIZE, maxHeaderSize)
		                 .attr(MAX_CHUNK_SIZE, maxChunkSize)
		                 .attr(VALIDATE_HEADERS, validateHeaders)
		                 .attr(INITIAL_BUFFER_SIZE, initialBufferSize);
	}

	/**
	 * Create a {@link HttpServerCodec} using the request decoder configuration attributes
	 * that would be configured through a {@link HttpRequestDecoderConfiguration}.
	 *
	 * @param channel the {@link Channel} on which the attributes have been registered.
	 * @return a new {@link HttpServerCodec}
	 */
	public static HttpServerCodec serverCodecFromAttributes(Channel channel) {
		Attribute<Integer> lineAttr = channel.attr(MAX_INITIAL_LINE_LENGTH);
		int line;
		if (lineAttr == null || lineAttr.get() == null) line = DEFAULT_MAX_INITIAL_LINE_LENGTH;
		else line = lineAttr.get();

		Attribute<Integer> headerAttr = channel.attr(MAX_HEADER_SIZE);
		int header;
		if (headerAttr == null || headerAttr.get() == null) header = DEFAULT_MAX_HEADER_SIZE;
		else header = headerAttr.get();

		Attribute<Integer> chunkAttr = channel.attr(MAX_CHUNK_SIZE);
		int chunk;
		if (chunkAttr == null || chunkAttr.get() == null) chunk = DEFAULT_MAX_CHUNK_SIZE;
		else chunk = chunkAttr.get();

		Attribute<Boolean> validateAttr = channel.attr(VALIDATE_HEADERS);
		boolean validate;
		if (validateAttr == null || validateAttr.get() == null) validate = DEFAULT_VALIDATE_HEADERS;
		else validate = validateAttr.get();

		Attribute<Integer> bufferAttr = channel.attr(INITIAL_BUFFER_SIZE);
		int buffer;
		if (bufferAttr == null || bufferAttr.get() == null) buffer = DEFAULT_INITIAL_BUFFER_SIZE;
		else buffer = bufferAttr.get();

		return new HttpServerCodec(line, header, chunk, validate, buffer);
	}

}
