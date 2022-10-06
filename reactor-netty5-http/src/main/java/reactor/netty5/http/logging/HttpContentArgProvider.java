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
package reactor.netty5.http.logging;

import io.netty5.buffer.Buffer;
import io.netty5.handler.codec.http.HttpContent;

import static reactor.netty5.http.logging.HttpMessageType.CONTENT;

class HttpContentArgProvider extends AbstractHttpMessageArgProvider {

	final Buffer content;

	HttpContentArgProvider(HttpContent<?> httpContent) {
		super(httpContent.decoderResult());
		this.content = httpContent.payload();
	}

	@Override
	public Buffer content() {
		return content;
	}

	@Override
	public HttpMessageType httpMessageType() {
		return CONTENT;
	}
}
