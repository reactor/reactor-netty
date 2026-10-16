/*
 * Copyright (c) 2026 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server;

import java.util.Locale;
import java.util.Objects;

final class AcceptQueryHeaderCodec {

	static String format(String... mediaRanges) {
		Objects.requireNonNull(mediaRanges, "mediaRanges");
		if (mediaRanges.length == 0) {
			throw new IllegalArgumentException("At least one media range must be provided");
		}

		StringBuilder headerValue = new StringBuilder();
		for (int i = 0; i < mediaRanges.length; i++) {
			String mediaRange = Objects.requireNonNull(mediaRanges[i], "mediaRange");
			if (i > 0) {
				headerValue.append(", ");
			}
			appendMediaRange(headerValue, mediaRange);
		}
		return headerValue.toString();
	}

	static void appendMediaRange(StringBuilder headerValue, String mediaRange) {
		String[] parts = mediaRange.split(";");
		String type = parts[0].trim();
		if (type.isEmpty()) {
			throw new IllegalArgumentException("Media range must not be empty");
		}

		headerValue.append('"')
		           .append(escape(type))
		           .append('"');

		for (int i = 1; i < parts.length; i++) {
			String parameter = parts[i].trim();
			if (parameter.isEmpty()) {
				continue;
			}

			int separatorIndex = parameter.indexOf('=');
			if (separatorIndex <= 0 || separatorIndex == parameter.length() - 1) {
				throw new IllegalArgumentException("Invalid media range parameter: " + mediaRange);
			}

			String parameterName = parameter.substring(0, separatorIndex)
			                                .trim()
			                                .toLowerCase(Locale.ROOT);
			String parameterValue = unquote(parameter.substring(separatorIndex + 1).trim());
			if (parameterName.isEmpty()) {
				throw new IllegalArgumentException("Invalid media range parameter: " + mediaRange);
			}

			headerValue.append(';')
			           .append(parameterName)
			           .append("=\"")
			           .append(escape(parameterValue))
			           .append('"');
		}
	}

	static String escape(String value) {
		return value.replace("\\", "\\\\")
		            .replace("\"", "\\\"");
	}

	static String unquote(String value) {
		if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
			return value.substring(1, value.length() - 1);
		}
		return value;
	}

	private AcceptQueryHeaderCodec() {
	}
}
