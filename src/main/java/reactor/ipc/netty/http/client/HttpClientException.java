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

import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * An error for signalling that an error occurred during a communication over HTTP version
 *
 * @author Stephane Maldini
 */
public class HttpClientException extends RuntimeException {

	private final HttpClientResponse response;

	public HttpClientException(HttpClientResponse response) {
		super("HTTP request failed with code: " + response.status().code() + ".\n" +
				"Failing URI: "+response.uri());
		this.response = response;
	}

	public HttpResponseStatus getResponseStatus() {
		return response.status();
	}

	public HttpClientResponse getResponse(){
		return response;
	}

	@Override
	public synchronized Throwable fillInStackTrace() {
		return null;
	}
}
