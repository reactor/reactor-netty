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
package reactor.netty.http.server;

import reactor.netty.http.HttpDecoderSpec;

import java.util.Objects;

/**
 * A configuration builder to fine tune the {@link io.netty.handler.codec.http.HttpServerCodec}
 * (or more precisely the {@link io.netty.handler.codec.http.HttpServerCodec.HttpServerRequestDecoder}) for HTTP/1.1
 * or {@link io.netty.handler.codec.http.HttpServerUpgradeHandler} for H2C.
 * <p>
 * Defaults are accessible as constants {@link #DEFAULT_MAX_INITIAL_LINE_LENGTH}, {@link #DEFAULT_MAX_HEADER_SIZE},
 * {@link #DEFAULT_MAX_CHUNK_SIZE}, {@link #DEFAULT_INITIAL_BUFFER_SIZE}, {@link #DEFAULT_VALIDATE_HEADERS} and
 * {@link #DEFAULT_H2C_MAX_CONTENT_LENGTH}.
 *
 * @author Simon Baslé
 * @author Violeta Georgieva
 */
public final class HttpRequestDecoderSpec extends HttpDecoderSpec<HttpRequestDecoderSpec> {

	/**
	 * The maximum length of the content of the H2C upgrade request.
	 * By default the server will reject an upgrade request with non-empty content,
	 * because the upgrade request is most likely a GET request.
	 */
	public static final int DEFAULT_H2C_MAX_CONTENT_LENGTH = 0;

	HttpRequestDecoderSpec() {
		this.h2cMaxContentLength = DEFAULT_H2C_MAX_CONTENT_LENGTH;
	}

	@Override
	public HttpRequestDecoderSpec get() {
		return this;
	}

	/**
	 * Build a {@link HttpRequestDecoderSpec}.
	 */
	HttpRequestDecoderSpec build() {
		HttpRequestDecoderSpec decoder = new HttpRequestDecoderSpec();
		decoder.initialBufferSize = initialBufferSize;
		decoder.maxChunkSize = maxChunkSize;
		decoder.maxHeaderSize = maxHeaderSize;
		decoder.maxInitialLineLength = maxInitialLineLength;
		decoder.validateHeaders = validateHeaders;
		decoder.h2cMaxContentLength = h2cMaxContentLength;
		return decoder;
	}
}
