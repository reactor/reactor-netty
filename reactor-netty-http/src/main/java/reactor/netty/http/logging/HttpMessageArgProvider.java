/*
 * Copyright (c) 2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.logging;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;

/**
 * A provider of the args required for logging {@link HttpMessage} details.
 *
 * @author Violeta Georgieva
 * @since 1.0.24
 */
public interface HttpMessageArgProvider {

	/**
	 * Return the data which is held by the {@link HttpMessage}.
	 *
	 * @return the data which is held by the {@link HttpMessage}
	 */
	default ByteBuf content() {
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns the result of decoding the {@link HttpMessage}.
	 *
	 * @return the result of decoding the {@link HttpMessage}
	 */
	default DecoderResult decoderResult() {
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns the request/response headers.
	 *
	 * @return the request/response headers
	 */
	default HttpHeaders headers() {
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns the {@link HttpMessage} type.
	 *
	 * @return the {@link HttpMessage} type
	 */
	HttpMessageType httpMessageType();

	/**
	 * Returns the name of this method, (e.g. "GET").
	 *
	 * @return the name of this method
	 */
	default String method() {
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns the protocol version, (e.g. "HTTP/1.1" or "HTTP/2.0").
	 *
	 * @return the protocol version
	 */
	default String protocol() {
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns the response status, (e.g. 200 OK).
	 *
	 * @return the response status
	 */
	default String status() {
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns the request/response trailing headers.
	 *
	 * @return the request/response trailing headers
	 */
	default HttpHeaders trailingHeaders() {
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns the requested URI, (e.g. "/hello").
	 *
	 * @return the requested URI
	 */
	default String uri() {
		throw new UnsupportedOperationException();
	}
}
