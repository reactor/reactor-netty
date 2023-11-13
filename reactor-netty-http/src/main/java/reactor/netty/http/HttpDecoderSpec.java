/*
 * Copyright (c) 2019-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http;

import java.util.function.Supplier;

/**
 * A configuration builder to fine tune the {@code HttpCodec} (or more precisely
 * the settings for the decoder).
 *
 * @author Violeta Georgieva
 */
public abstract class HttpDecoderSpec<T extends HttpDecoderSpec<T>> implements Supplier<T> {

	public static final int DEFAULT_MAX_INITIAL_LINE_LENGTH             = 4096;
	public static final int DEFAULT_MAX_HEADER_SIZE                     = 8192;
	/**
	 * Default max chunk size.
	 *
	 * @deprecated as of 1.1.0. This will be removed in 2.0.0 as Netty 5 does not support this configuration.
	 */
	@Deprecated
	public static final int DEFAULT_MAX_CHUNK_SIZE                      = 8192;
	public static final boolean DEFAULT_VALIDATE_HEADERS                = true;
	public static final int DEFAULT_INITIAL_BUFFER_SIZE                 = 128;
	public static final boolean DEFAULT_ALLOW_DUPLICATE_CONTENT_LENGTHS = false;

	protected int maxInitialLineLength             = DEFAULT_MAX_INITIAL_LINE_LENGTH;
	protected int maxHeaderSize                    = DEFAULT_MAX_HEADER_SIZE;
	protected int maxChunkSize                     = DEFAULT_MAX_CHUNK_SIZE;
	protected boolean validateHeaders              = DEFAULT_VALIDATE_HEADERS;
	protected int initialBufferSize                = DEFAULT_INITIAL_BUFFER_SIZE;
	protected boolean allowDuplicateContentLengths = DEFAULT_ALLOW_DUPLICATE_CONTENT_LENGTHS;
	protected int h2cMaxContentLength;

	/**
	 * Configure the maximum length that can be decoded for the HTTP request's initial
	 * line. Defaults to {@link #DEFAULT_MAX_INITIAL_LINE_LENGTH}.
	 *
	 * @param value the value for the maximum initial line length (strictly positive)
	 * @return this option builder for further configuration
	 */
	public T maxInitialLineLength(int value) {
		if (value <= 0) {
			throw new IllegalArgumentException("maxInitialLineLength must be strictly positive");
		}
		this.maxInitialLineLength = value;
		return get();
	}

	/**
	 * Return the configured maximum length that can be decoded for the HTTP request's initial line.
	 *
	 * @return the configured maximum length that can be decoded for the HTTP request's initial line
	 */
	public int maxInitialLineLength() {
		return maxInitialLineLength;
	}

	/**
	 * Configure the maximum header size that can be decoded for the HTTP request.
	 * Defaults to {@link #DEFAULT_MAX_HEADER_SIZE}.
	 *
	 * @param value the value for the maximum header size (strictly positive)
	 * @return this option builder for further configuration
	 */
	public T maxHeaderSize(int value) {
		if (value <= 0) {
			throw new IllegalArgumentException("maxHeaderSize must be strictly positive");
		}
		this.maxHeaderSize = value;
		return get();
	}

	/**
	 * Return the configured maximum header size that can be decoded for the HTTP request.
	 *
	 * @return the configured maximum header size that can be decoded for the HTTP request
	 */
	public int maxHeaderSize() {
		return maxHeaderSize;
	}

	/**
	 * Configure the maximum chunk size that can be decoded for the HTTP request.
	 * Defaults to {@link #DEFAULT_MAX_CHUNK_SIZE}.
	 *
	 * @param value the value for the maximum chunk size (strictly positive)
	 * @return this option builder for further configuration
	 * @deprecated as of 1.1.0. This will be removed in 2.0.0 as Netty 5 does not support this configuration.
	 */
	@Deprecated
	public T maxChunkSize(int value) {
		if (value <= 0) {
			throw new IllegalArgumentException("maxChunkSize must be strictly positive");
		}
		this.maxChunkSize = value;
		return get();
	}

	/**
	 * Return the configured maximum chunk size that can be decoded for the HTTP request.
	 *
	 * @return the configured maximum chunk size that can be decoded for the HTTP request
	 * @deprecated as of 1.1.0. This will be removed in 2.0.0 as Netty 5 does not support this configuration.
	 */
	@Deprecated
	public int maxChunkSize() {
		return maxChunkSize;
	}

	/**
	 * Configure whether to validate headers when decoding requests. Defaults to
	 * {@link #DEFAULT_VALIDATE_HEADERS}.
	 *
	 * @param validate true to validate headers, false otherwise
	 * @return this option builder for further configuration
	 */
	public T validateHeaders(boolean validate) {
		this.validateHeaders = validate;
		return get();
	}

	/**
	 * Return the configuration whether to validate headers when decoding requests.
	 *
	 * @return the configuration whether to validate headers when decoding requests
	 */
	public boolean validateHeaders() {
		return validateHeaders;
	}

	/**
	 * Configure the initial buffer size for HTTP request decoding. Defaults to
	 * {@link #DEFAULT_INITIAL_BUFFER_SIZE}.
	 *
	 * @param value the initial buffer size to use (strictly positive)
	 * @return this option builder for further configuration
	 */
	public T initialBufferSize(int value) {
		if (value <= 0) {
			throw new IllegalArgumentException("initialBufferSize must be strictly positive");
		}
		this.initialBufferSize = value;
		return get();
	}

	/**
	 * Return the configured initial buffer size for HTTP request decoding.
	 *
	 * @return the configured initial buffer size for HTTP request decoding
	 */
	public int initialBufferSize() {
		return initialBufferSize;
	}

	/**
	 * Configure whether to allow duplicate {@code Content-Length} headers. Defaults to
	 * {@link #DEFAULT_ALLOW_DUPLICATE_CONTENT_LENGTHS} which means that a message with duplicate
	 * {@code Content-Length} headers is rejected. When this is configured to {@code true},
	 * duplicate {@code Content-Length} headers are accepted only if they are all the same value, otherwise
	 * such message is rejected. The duplicated {@code Content-Length} headers are replaced with a single valid
	 * {@code Content-Length} header.
	 *
	 * @param allow true to allow duplicate {@code Content-Length} headers with the same value, false otherwise
	 * @return this option builder for further configuration
	 * @since 1.0.8
	 */
	public T allowDuplicateContentLengths(boolean allow) {
		this.allowDuplicateContentLengths = allow;
		return get();
	}

	/**
	 * Return the configuration whether to allow duplicate {@code Content-Length} headers.
	 *
	 * @return the configuration whether to allow duplicate {@code Content-Length} headers
	 * @since 1.0.8
	 */
	public boolean allowDuplicateContentLengths() {
		return allowDuplicateContentLengths;
	}

	/**
	 * Configure the maximum length of the content of the HTTP/2.0 clear-text upgrade request.
	 * <ul>
	 * <li>By default the server will reject an upgrade request with non-empty content,
	 * because the upgrade request is most likely a GET request. If the client sends
	 * a non-GET upgrade request, {@link #h2cMaxContentLength} specifies the maximum
	 * length of the content of the upgrade request.</li>
	 * <li>By default the client will allow an upgrade request with up to 65536 as
	 * the maximum length of the aggregated content.</li>
	 * </ul>
	 *
	 * @param h2cMaxContentLength the maximum length of the content of the upgrade request
	 * @return this builder for further configuration
	 */
	public T h2cMaxContentLength(int h2cMaxContentLength) {
		if (h2cMaxContentLength < 0) {
			throw new IllegalArgumentException("h2cMaxContentLength must be non negative");
		}
		this.h2cMaxContentLength = h2cMaxContentLength;
		return get();
	}

	/**
	 * Return the configured maximum length of the content of the HTTP/2.0 clear-text upgrade request.
	 *
	 * @return the configured maximum length of the content of the HTTP/2.0 clear-text upgrade request
	 */
	public int h2cMaxContentLength() {
		return h2cMaxContentLength;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof HttpDecoderSpec)) {
			return false;
		}
		HttpDecoderSpec<?> that = (HttpDecoderSpec<?>) o;
		return maxInitialLineLength == that.maxInitialLineLength &&
				maxHeaderSize == that.maxHeaderSize &&
				maxChunkSize == that.maxChunkSize &&
				validateHeaders == that.validateHeaders &&
				initialBufferSize == that.initialBufferSize &&
				allowDuplicateContentLengths == that.allowDuplicateContentLengths &&
				h2cMaxContentLength == that.h2cMaxContentLength;
	}

	@Override
	public int hashCode() {
		int result = 1;
		result = 31 * result + maxInitialLineLength;
		result = 31 * result + maxHeaderSize;
		result = 31 * result + maxChunkSize;
		result = 31 * result + Boolean.hashCode(validateHeaders);
		result = 31 * result + initialBufferSize;
		result = 31 * result + Boolean.hashCode(allowDuplicateContentLengths);
		result = 31 * result + h2cMaxContentLength;
		return result;
	}
}
