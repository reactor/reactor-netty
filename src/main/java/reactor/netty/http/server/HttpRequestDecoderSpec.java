/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.http.server;

import java.util.function.Function;

import reactor.netty.tcp.TcpServer;

/**
 * A configuration builder to fine tune the {@link io.netty.handler.codec.http.HttpServerCodec}
 * (or more precisely the {@link io.netty.handler.codec.http.HttpServerCodec.HttpServerRequestDecoder}).
 * <p>
 * Defaults are accessible as constants {@link #DEFAULT_MAX_INITIAL_LINE_LENGTH}, {@link #DEFAULT_MAX_HEADER_SIZE}
 * and {@link #DEFAULT_MAX_CHUNK_SIZE}.
 *
 * @author Simon Baslé
 */
public final class HttpRequestDecoderSpec {

	public static final int DEFAULT_MAX_INITIAL_LINE_LENGTH = 4096;
	public static final int DEFAULT_MAX_HEADER_SIZE         = 8192;
	public static final int DEFAULT_MAX_CHUNK_SIZE          = 8192;
	public static final boolean DEFAULT_VALIDATE_HEADERS    = true;
	public static final int DEFAULT_INITIAL_BUFFER_SIZE     = 128;

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
	public HttpRequestDecoderSpec maxInitialLineLength(int value) {
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
	public HttpRequestDecoderSpec maxHeaderSize(int value) {
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
	public HttpRequestDecoderSpec maxChunkSize(int value) {
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
	public HttpRequestDecoderSpec validateHeaders(boolean validate) {
		this.validateHeaders = validate;
		return this;
	}

	/**
	 * Configure the initial buffer size for HTTP request decoding. Defaults to
	 * {@link #DEFAULT_INITIAL_BUFFER_SIZE}.
	 *
	 * @param value the initial buffer size to use (strictly positive)
	 * @return this option builder for further configuration
	 */
	public HttpRequestDecoderSpec initialBufferSize(int value) {
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
		HttpRequestDecoderSpec decoder = new HttpRequestDecoderSpec();
		decoder.initialBufferSize = initialBufferSize;
		decoder.maxChunkSize = maxChunkSize;
		decoder.maxHeaderSize = maxHeaderSize;
		decoder.maxInitialLineLength = maxInitialLineLength;
		decoder.validateHeaders = validateHeaders;
		return tcp -> tcp.bootstrap(b -> HttpServerConfiguration.decoder(b, decoder));
	}

}
