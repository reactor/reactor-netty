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
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.internal.StringUtil;

import java.util.Map;
import java.util.function.Function;

/**
 * Utilities for creating log message based on a given {@link HttpMessageArgProvider}.
 *
 * @author Violeta Georgieva
 * @since 1.0.4
 */
public final class HttpMessageLogUtils {

	/**
	 * Creates a log message based on the provided {@link HttpMessageArgProvider}.
	 *
	 * @param argProvider           the provided {@link HttpMessageArgProvider}
	 * @param uriValueFunction      a function for sanitizing the uri
	 * @param decoderResultFunction a function for sanitizing the decoding result
	 * @param headerValueFunction   a function for sanitizing the headers values
	 * @return a log message for the given {@link HttpMessageArgProvider}
	 */
	public static String logHttpMessage(
			HttpMessageArgProvider argProvider,
			Function<String, String> uriValueFunction,
			Function<DecoderResult, String> decoderResultFunction,
			Function<Map.Entry<String, String>, String> headerValueFunction) {
		switch (argProvider.httpMessageType()) {
			case REQUEST:
				return logRequest(argProvider, uriValueFunction, decoderResultFunction, headerValueFunction);
			case FULL_REQUEST:
				return logFullRequest(argProvider, uriValueFunction, decoderResultFunction, headerValueFunction);
			case RESPONSE:
				return logResponse(argProvider, decoderResultFunction, headerValueFunction);
			case FULL_RESPONSE:
				return logFullResponse(argProvider, decoderResultFunction, headerValueFunction);
			case CONTENT:
				return logContent(argProvider, decoderResultFunction);
			case LAST_CONTENT:
				return logLastContent(argProvider, decoderResultFunction, headerValueFunction);
			default:
				throw new IllegalArgumentException("Unknown HttpMessageType: " + argProvider.httpMessageType());
		}
	}

	static String logContent(
			HttpMessageArgProvider argProvider,
			Function<DecoderResult, String> decoderResultFunction) {
		StringBuilder buf = new StringBuilder(256);
		logContent(buf, argProvider, decoderResultFunction);
		removeLastNewLine(buf);
		return buf.toString();
	}

	static String logFullRequest(
			HttpMessageArgProvider argProvider,
			Function<String, String> uriValueFunction,
			Function<DecoderResult, String> decoderResultFunction,
			Function<Map.Entry<String, String>, String> headerValueFunction) {
		StringBuilder buf = new StringBuilder(256);
		logFullCommon(buf, argProvider, decoderResultFunction);
		logInitialLine(buf, argProvider, uriValueFunction);
		logHeaders(buf, argProvider.headers(), headerValueFunction);
		logHeaders(buf, argProvider.trailingHeaders(), headerValueFunction);
		removeLastNewLine(buf);
		return buf.toString();
	}

	static String logFullResponse(
			HttpMessageArgProvider argProvider,
			Function<DecoderResult, String> decoderResultFunction,
			Function<Map.Entry<String, String>, String> headerValueFunction) {
		StringBuilder buf = new StringBuilder(256);
		logFullCommon(buf, argProvider, decoderResultFunction);
		logInitialLine(buf, argProvider);
		logHeaders(buf, argProvider.headers(), headerValueFunction);
		logHeaders(buf, argProvider.trailingHeaders(), headerValueFunction);
		removeLastNewLine(buf);
		return buf.toString();
	}

	static String logLastContent(
			HttpMessageArgProvider argProvider,
			Function<DecoderResult, String> decoderResultFunction,
			Function<Map.Entry<String, String>, String> headerValueFunction) {
		StringBuilder buf = new StringBuilder(256);
		logContent(buf, argProvider, decoderResultFunction);
		logHeaders(buf, argProvider.trailingHeaders(), headerValueFunction);
		removeLastNewLine(buf);
		return buf.toString();
	}

	static String logRequest(
			HttpMessageArgProvider argProvider,
			Function<String, String> uriValueFunction,
			Function<DecoderResult, String> decoderResultFunction,
			Function<Map.Entry<String, String>, String> headerValueFunction) {
		StringBuilder buf = new StringBuilder(256);
		logCommon(buf, argProvider, decoderResultFunction);
		logInitialLine(buf, argProvider, uriValueFunction);
		logHeaders(buf, argProvider.headers(), headerValueFunction);
		removeLastNewLine(buf);
		return buf.toString();
	}

	static String logResponse(
			HttpMessageArgProvider argProvider,
			Function<DecoderResult, String> decoderResultFunction,
			Function<Map.Entry<String, String>, String> headerValueFunction) {
		StringBuilder buf = new StringBuilder(256);
		logCommon(buf, argProvider, decoderResultFunction);
		logInitialLine(buf, argProvider);
		logHeaders(buf, argProvider.headers(), headerValueFunction);
		removeLastNewLine(buf);
		return buf.toString();
	}

	static void logCommon(
			StringBuilder buf,
			HttpMessageArgProvider argProvider,
			Function<DecoderResult, String> decoderResultFunction) {
		buf.append(argProvider.httpMessageType());
		buf.append("(decodeResult: ");
		buf.append(decoderResultFunction.apply(argProvider.decoderResult()));
		buf.append(", version: ");
		buf.append(argProvider.protocol());
		buf.append(')');
		buf.append(StringUtil.NEWLINE);
	}

	static void logContent(
			StringBuilder buf,
			HttpMessageArgProvider argProvider,
			Function<DecoderResult, String> decoderResultFunction) {
		buf.append(argProvider.httpMessageType());
		buf.append("(decodeResult: ");
		buf.append(decoderResultFunction.apply(argProvider.decoderResult()));
		buf.append(", content: ");
		buf.append(argProvider.content());
		buf.append(')');
		buf.append(StringUtil.NEWLINE);
	}

	static void logFullCommon(
			StringBuilder buf,
			HttpMessageArgProvider argProvider,
			Function<DecoderResult, String> decoderResultFunction) {
		buf.append(argProvider.httpMessageType());
		buf.append("(decodeResult: ");
		buf.append(decoderResultFunction.apply(argProvider.decoderResult()));
		buf.append(", version: ");
		buf.append(argProvider.protocol());
		buf.append(", content: ");
		buf.append(argProvider.content());
		buf.append(')');
		buf.append(StringUtil.NEWLINE);
	}

	static void logHeaders(
			StringBuilder buf,
			HttpHeaders headers,
			Function<Map.Entry<String, String>, String> headerValueFunction) {
		for (Map.Entry<String, String> e : headers) {
			buf.append(e.getKey());
			buf.append(": ");
			buf.append(headerValueFunction.apply(e));
			buf.append(StringUtil.NEWLINE);
		}
	}

	static void logInitialLine(StringBuilder buf, HttpMessageArgProvider argProvider) {
		buf.append(argProvider.protocol());
		buf.append(' ');
		buf.append(argProvider.status());
		buf.append(StringUtil.NEWLINE);
	}

	static void logInitialLine(
			StringBuilder buf,
			HttpMessageArgProvider argProvider,
			Function<String, String> uriValueFunction) {
		buf.append(argProvider.method());
		buf.append(' ');
		buf.append(uriValueFunction.apply(argProvider.uri()));
		buf.append(' ');
		buf.append(argProvider.protocol());
		buf.append(StringUtil.NEWLINE);
	}

	static void removeLastNewLine(StringBuilder buf) {
		buf.setLength(buf.length() - StringUtil.NEWLINE.length());
	}

	private HttpMessageLogUtils() {
	}
}
