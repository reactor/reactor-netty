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

import io.netty.handler.codec.DecoderResult;

import java.util.Map;
import java.util.function.Function;

import static reactor.netty.http.logging.HttpMessageLogUtils.logHttpMessage;

/**
 * Reactor Netty factory for generating a log message based on a given {@link HttpMessageArgProvider}.
 *
 * @author Violeta Georgieva
 * @since 1.0.4
 */
public class ReactorNettyHttpMessageLogFactory extends AbstractHttpMessageLogFactory {

	public static final ReactorNettyHttpMessageLogFactory INSTANCE = new ReactorNettyHttpMessageLogFactory();

	static final Function<String, String> DEFAULT_URI_VALUE_FUNCTION = s -> {
		int index = s.indexOf('?');
		return index == -1 ? s : s.substring(0, index + 1) + "<filtered>";
	};

	static final Function<DecoderResult, String> DEFAULT_DECODER_RESULT_FUNCTION =
			decoderResult -> {
				if (decoderResult.isFinished()) {
					if (decoderResult.isSuccess()) {
						return "success";
					}

					String cause = decoderResult.cause().toString();
					return new StringBuilder(cause.length() + 17)
							.append("failure(")
							.append(cause)
							.append(')')
							.toString();
				}
				else {
					return "unfinished";
				}
			};

	static final Function<Map.Entry<String, String>, String> DEFAULT_HEADER_VALUE_FUNCTION = e -> "<filtered>";

	@Override
	public String common(HttpMessageArgProvider arg) {
		return logHttpMessage(arg, uriValueFunction(), decoderResultFunction(), headerValueFunction());
	}

	/**
	 * Returns the function that is used for sanitizing the uri.
	 *
	 * @return the function that is used for sanitizing the uri
	 */
	protected Function<String, String> uriValueFunction() {
		return DEFAULT_URI_VALUE_FUNCTION;
	}

	/**
	 * Returns the function that is used for sanitizing the decoding result.
	 *
	 * @return the function that is used for sanitizing the decoding result
	 */
	protected Function<DecoderResult, String> decoderResultFunction() {
		return DEFAULT_DECODER_RESULT_FUNCTION;
	}

	/**
	 * Returns the function that is used for sanitizing the headers values.
	 *
	 * @return the function that is used for sanitizing the headers values
	 */
	protected Function<Map.Entry<String, String>, String> headerValueFunction() {
		return DEFAULT_HEADER_VALUE_FUNCTION;
	}
}