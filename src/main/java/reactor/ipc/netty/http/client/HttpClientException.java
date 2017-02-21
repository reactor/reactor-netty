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

package reactor.ipc.netty.http.client;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.ipc.netty.channel.AbortedException;

/**
 * An exception signalling that an error occurred during a communication over HTTP version
 *
 * @author Stephane Maldini
 */
public class HttpClientException extends AbortedException {

	final HttpResponse responseMessage;
	final String       uri;

	public HttpClientException(String uri, HttpResponse response) {
		super("HTTP request failed with code: " + response.status()
		                                                  .code() + ".\n" + "Failing URI: " + uri);
		this.responseMessage = response;
		this.uri = uri;
	}

	/**
	 * Return the HTTP status
	 *
	 * @return the HTTP status
	 */
	public HttpResponseStatus status() {
		return responseMessage.status();
	}

	/**
	 * Return the HTTP response headers
	 *
	 * @return the HTTP response headers
	 */
	public HttpHeaders headers() {
		return responseMessage.headers();
	}

	/**
	 * Return the netty HTTP response message
	 *
	 * @return the HTTP response message
	 */
	public HttpResponse message() {
		return responseMessage;
	}

	/**
	 * Return the original request uri
	 *
	 * @return the original request uri
	 */
	public String uri() {
		return uri;
	}

	@Override
	public synchronized Throwable fillInStackTrace() {
		return null;
	}
}
