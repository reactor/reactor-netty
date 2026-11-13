/*
 * Copyright (c) 2019-2026 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.http.client;

import reactor.netty.http.HttpDecoderSpec;

/**
 * A configuration builder to fine tune the {@link io.netty.handler.codec.http.HttpClientCodec}
 * (or more precisely the {@link io.netty.handler.codec.http.HttpClientCodec.Decoder}).
 * <p>
 * Defaults are accessible as constants:
 * <table>
 *     <tr><td>{@link #DEFAULT_ALLOW_DUPLICATE_CONTENT_LENGTHS}</td><td>false</td></tr>
 *     <tr><td>{@link #DEFAULT_FAIL_ON_MISSING_RESPONSE}</td><td>false</td></tr>
 *     <tr><td>{@link #DEFAULT_H2C_MAX_CONTENT_LENGTH}</td><td>65536</td></tr>
 *     <tr><td>{@link #DEFAULT_INITIAL_BUFFER_SIZE}</td><td>128</td></tr>
 *     <tr><td>{@link #DEFAULT_MAX_CHUNK_SIZE}</td><td>8192</td></tr>
 *     <tr><td>{@link #DEFAULT_MAX_DECOMPRESSION_BUFFER_SIZE}</td><td>0</td></tr>
 *     <tr><td>{@link #DEFAULT_MAX_HEADER_SIZE}</td><td>8192</td></tr>
 *     <tr><td>{@link #DEFAULT_MAX_INITIAL_LINE_LENGTH}</td><td>4096</td></tr>
 *     <tr><td>{@link #DEFAULT_PARSE_HTTP_AFTER_CONNECT_REQUEST}</td><td>false</td></tr>
 *     <tr><td>{@link #DEFAULT_VALIDATE_HEADERS}</td><td>true</td></tr>
 *     <tr><td>{@link #DEFAULT_ALLOW_PARTIAL_CHUNKS}</td><td>true</td></tr>
 * </table>
 *
 * @author Violeta Georgieva
 */
public final class HttpResponseDecoderSpec extends HttpDecoderSpec<HttpResponseDecoderSpec> {

	// tag::snippet-code[]
	public static final boolean DEFAULT_FAIL_ON_MISSING_RESPONSE         = false;
	public static final boolean DEFAULT_PARSE_HTTP_AFTER_CONNECT_REQUEST = false;

	/**
	 * The maximum length of the content of the HTTP/2.0 clear-text upgrade request.
	 * By default, the client will allow an upgrade request with up to 65536 as
	 * the maximum length of the aggregated content.
	 */
	public static final int DEFAULT_H2C_MAX_CONTENT_LENGTH = 65536;

	/**
	 * The default maximum allowed size of the decompression buffer, in bytes, used when
	 * decompressing a compressed HTTP response body. {@code 0} means that the maximum size
	 * is not limited.
	 */
	public static final int DEFAULT_MAX_DECOMPRESSION_BUFFER_SIZE = 0;
	// end::snippet-code[]

	boolean failOnMissingResponse        = DEFAULT_FAIL_ON_MISSING_RESPONSE;
	boolean parseHttpAfterConnectRequest = DEFAULT_PARSE_HTTP_AFTER_CONNECT_REQUEST;
	int maxDecompressionBufferSize       = DEFAULT_MAX_DECOMPRESSION_BUFFER_SIZE;

	HttpResponseDecoderSpec() {
		this.h2cMaxContentLength = DEFAULT_H2C_MAX_CONTENT_LENGTH;
	}

	@Override
	public HttpResponseDecoderSpec get() {
		return this;
	}

	/**
	 * Configure whether to throw an exception on a channel inactive
	 * in case there was a missing response.
	 *
	 * @param failOnMissingResponse true - throw an exception on a channel inactive
	 *                              in case there was a missing response, otherwise false
	 * @return this option builder for further configuration
	 */
	public HttpResponseDecoderSpec failOnMissingResponse(boolean failOnMissingResponse) {
		this.failOnMissingResponse = failOnMissingResponse;
		return this;
	}

	/**
	 * Configure whether the HTTP decoding will continue even after HTTP CONNECT.
	 *
	 * @param parseHttpAfterConnectRequest true to continue HTTP decoding, otherwise false
	 * @return this option builder for further configuration
	 */
	public HttpResponseDecoderSpec parseHttpAfterConnectRequest(boolean parseHttpAfterConnectRequest) {
		this.parseHttpAfterConnectRequest = parseHttpAfterConnectRequest;
		return this;
	}

	/**
	 * Configure the maximum allowed size, in bytes, of the buffer used to decompress a compressed
	 * HTTP response body (applies to {@code gzip}/{@code deflate}/{@code br}/{@code zstd} when
	 * {@link reactor.netty.http.client.HttpClient#compress(boolean) compression} is enabled). When
	 * the decompression buffer reaches this size and cannot be expanded further, a
	 * {@code io.netty.handler.codec.compression.DecompressionException} is thrown.
	 * Defaults to {@link #DEFAULT_MAX_DECOMPRESSION_BUFFER_SIZE} ({@code 0}), which means that the
	 * maximum size is not limited.
	 *
	 * @param value the maximum allowed size of the decompression buffer, in bytes;
	 *              {@code 0} means the maximum size is not limited (non-negative)
	 * @return this option builder for further configuration
	 * @since 1.0.53
	 */
	public HttpResponseDecoderSpec maxDecompressionBufferSize(int value) {
		if (value < 0) {
			throw new IllegalArgumentException("maxDecompressionBufferSize must be positive or zero");
		}
		this.maxDecompressionBufferSize = value;
		return this;
	}

	/**
	 * Return the configured maximum allowed size, in bytes, of the buffer used to decompress a
	 * compressed HTTP response body. {@code 0} means the maximum size is not limited.
	 *
	 * @return the configured maximum allowed size of the decompression buffer, in bytes
	 * @since 1.0.53
	 */
	public int maxDecompressionBufferSize() {
		return maxDecompressionBufferSize;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		if (!super.equals(o)) {
			return false;
		}
		HttpResponseDecoderSpec that = (HttpResponseDecoderSpec) o;
		return failOnMissingResponse == that.failOnMissingResponse &&
				parseHttpAfterConnectRequest == that.parseHttpAfterConnectRequest &&
				maxDecompressionBufferSize == that.maxDecompressionBufferSize;
	}

	@Override
	public int hashCode() {
		int result = super.hashCode();
		result = 31 * result + Boolean.hashCode(failOnMissingResponse);
		result = 31 * result + Boolean.hashCode(parseHttpAfterConnectRequest);
		result = 31 * result + maxDecompressionBufferSize;
		return result;
	}

	/**
	 * Build a {@link HttpResponseDecoderSpec}.
	 */
	HttpResponseDecoderSpec build() {
		HttpResponseDecoderSpec decoder = new HttpResponseDecoderSpec();
		decoder.initialBufferSize = initialBufferSize;
		decoder.maxChunkSize = maxChunkSize;
		decoder.maxHeaderSize = maxHeaderSize;
		decoder.maxInitialLineLength = maxInitialLineLength;
		decoder.validateHeaders = validateHeaders;
		decoder.allowDuplicateContentLengths = allowDuplicateContentLengths;
		decoder.failOnMissingResponse = failOnMissingResponse;
		decoder.parseHttpAfterConnectRequest = parseHttpAfterConnectRequest;
		decoder.h2cMaxContentLength = h2cMaxContentLength;
		decoder.allowPartialChunks = allowPartialChunks;
		decoder.maxDecompressionBufferSize = maxDecompressionBufferSize;
		return decoder;
	}
}
