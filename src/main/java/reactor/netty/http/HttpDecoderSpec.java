/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http;

import java.util.function.Supplier;

/**
 * A configuration builder to fine tune the {@code HttpCodec} (or more precisely
 * the settings for the decoder)
 *
 * @author Violeta Georgieva
 */
public abstract class HttpDecoderSpec<T extends HttpDecoderSpec<T>> implements Supplier<T> {

	public static final int DEFAULT_MAX_INITIAL_LINE_LENGTH = 4096;
	public static final int DEFAULT_MAX_HEADER_SIZE         = 8192;
	public static final int DEFAULT_MAX_CHUNK_SIZE          = 8192;
	public static final boolean DEFAULT_VALIDATE_HEADERS    = true;
	public static final int DEFAULT_INITIAL_BUFFER_SIZE     = 128;

	protected int maxInitialLineLength = DEFAULT_MAX_INITIAL_LINE_LENGTH;
	protected int maxHeaderSize        = DEFAULT_MAX_HEADER_SIZE;
	protected int maxChunkSize         = DEFAULT_MAX_CHUNK_SIZE;
	protected boolean validateHeaders  = DEFAULT_VALIDATE_HEADERS;
	protected int initialBufferSize    = DEFAULT_INITIAL_BUFFER_SIZE;

	/**
	 * Configure the maximum length that can be decoded for the HTTP request's initial
	 * line. Defaults to {@link #DEFAULT_MAX_INITIAL_LINE_LENGTH}.
	 *
	 * @param value the value for the maximum initial line length (strictly positive)
	 * @return this option builder for further configuration
	 */
	public T maxInitialLineLength(int value) {
		if (value <= 0) {
			throw new IllegalArgumentException(
					"maxInitialLineLength must be strictly positive");
		}
		this.maxInitialLineLength = value;
		return get();
	}

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

	public int maxHeaderSize() {
		return maxHeaderSize;
	}

	/**
	 * Configure the maximum chunk size that can be decoded for the HTTP request.
	 * Defaults to {@link #DEFAULT_MAX_CHUNK_SIZE}.
	 *
	 * @param value the value for the maximum chunk size (strictly positive)
	 * @return this option builder for further configuration
	 */
	public T maxChunkSize(int value) {
		if (value <= 0) {
			throw new IllegalArgumentException("maxChunkSize must be strictly positive");
		}
		this.maxChunkSize = value;
		return get();
	}

	public int maxChunkSize() {
		return maxChunkSize;
	}

	/**
	 * Configure whether or not to validate headers when decoding requests. Defaults to
	 * #DEFAULT_VALIDATE_HEADERS.
	 *
	 * @param validate true to validate headers, false otherwise
	 * @return this option builder for further configuration
	 */
	public T validateHeaders(boolean validate) {
		this.validateHeaders = validate;
		return get();
	}

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

	public int initialBufferSize() {
		return initialBufferSize;
	}
}
