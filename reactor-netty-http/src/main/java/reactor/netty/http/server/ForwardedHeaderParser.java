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

import io.netty.util.AsciiString;
import reactor.netty.transport.AddressUtils;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.net.InetSocketAddress;

import static reactor.netty.http.server.ConnectionInfo.getDefaultHostPort;
import static reactor.netty.http.server.DefaultHttpForwardedHeaderHandler.DEFAULT_FORWARDED_HEADER_VALIDATION;

/**
 * Parse the standard {@code Forwarded} header.
 *
 * @author Violeta Georgieva
 * @since 1.4.0
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7239">RFC 7239</a>
 */
final class ForwardedHeaderParser {
	static final String BY = "by";
	static final String FOR = "for";
	static final String HOST = "host";
	static final String PROTO = "proto";

	static final Logger log = Loggers.getLogger(ForwardedHeaderParser.class);

	private ForwardedHeaderParser() {}

	@SuppressWarnings("NullAway")
	static ConnectionInfo parse(ConnectionInfo connectionInfo, String forwardedHeader) {
		String forwardedBy = null;
		String forwardedFor = null;
		String forwardedHost = null;
		String forwardedProto = null;

		int length = forwardedHeader.length();
		int index = 0;
		while (index < length) {
			index = skipOptionalWhitespace(forwardedHeader, index);
			if (index == length || forwardedHeader.charAt(index) == ',') {
				break;
			}
			if (forwardedHeader.charAt(index) == ';') {
				index++;
				continue;
			}

			int nameStart = index;
			int equalsIndex = -1;
			while (index < length) {
				char c = forwardedHeader.charAt(index);
				if (c == '=') {
					equalsIndex = index;
					break;
				}
				if (c == ',' || c == ';' || c == '"') {
					break;
				}
				index++;
			}
			if (equalsIndex < 0) {
				index = skipToDelimiter(forwardedHeader, index);
				continue;
			}
			int nameEnd = trimTrailingWhitespace(forwardedHeader, nameStart, equalsIndex);
			int nameLength = nameEnd - nameStart;

			int valueStart = skipOptionalWhitespace(forwardedHeader, equalsIndex + 1);
			boolean quoted = valueStart < length && forwardedHeader.charAt(valueStart) == '"';
			int contentStart;
			int contentEnd;
			boolean malformed;
			if (quoted) {
				contentStart = valueStart + 1;
				int closingQuote = findClosingQuote(forwardedHeader, contentStart);
				if (closingQuote < 0) {
					contentEnd = length;
					index = length;
					malformed = true;
				}
				else {
					contentEnd = closingQuote;
					index = skipOptionalWhitespace(forwardedHeader, closingQuote + 1);
					malformed = index < length && forwardedHeader.charAt(index) != ',' && forwardedHeader.charAt(index) != ';';
				}
			}
			else {
				contentStart = valueStart;
				contentEnd = skipToDelimiter(forwardedHeader, valueStart);
				index = contentEnd;
				malformed = containsQuote(forwardedHeader, contentStart, contentEnd);
			}
			if (malformed) {
				index = skipToDelimiter(forwardedHeader, index);
				continue;
			}

			switch (nameLength) {
				case 2:
					if (forwardedBy == null && matchesParam(forwardedHeader, nameStart, BY)) {
						forwardedBy = extractValue(forwardedHeader, contentStart, contentEnd, quoted);
					}
					break;
				case 3:
					if (forwardedFor == null && matchesParam(forwardedHeader, nameStart, FOR)) {
						forwardedFor = extractValue(forwardedHeader, contentStart, contentEnd, quoted);
					}
					break;
				case 4:
					if (forwardedHost == null && matchesParam(forwardedHeader, nameStart, HOST)) {
						forwardedHost = extractValue(forwardedHeader, contentStart, contentEnd, quoted);
					}
					break;
				case 5:
					if (forwardedProto == null && matchesParam(forwardedHeader, nameStart, PROTO)) {
						forwardedProto = extractValue(forwardedHeader, contentStart, contentEnd, quoted);
					}
					break;
				default:
					break;
			}
		}

		if (forwardedProto != null) {
			if (isValidScheme(forwardedProto)) {
				connectionInfo = connectionInfo.withScheme(forwardedProto);
			}
			else {
				if (log.isDebugEnabled()) {
					log.debug("Invalid scheme for forwarded header: " + forwardedProto);
				}
			}
		}

		InetSocketAddress hostAddress = forwardedHost != null && !forwardedHost.isEmpty() ?
				AddressUtils.parseAddress(forwardedHost, getDefaultHostPort(connectionInfo.getScheme()), DEFAULT_FORWARDED_HEADER_VALIDATION) : null;
		InetSocketAddress byAddress = forwardedBy != null && !forwardedBy.isEmpty() ?
				// Deliberately suppress "NullAway"
				// This implementation is invoked always with InetSocketAddress and host address != null
				AddressUtils.parseAddress(forwardedBy, connectionInfo.getHostAddress().getPort(), DEFAULT_FORWARDED_HEADER_VALIDATION) : null;

		if (byAddress != null) {
			connectionInfo = hostAddress != null ?
					connectionInfo.withHostAddress(byAddress, hostAddress.getHostString(), hostAddress.getPort()) :
					connectionInfo.withHostAddress(byAddress, connectionInfo.getHostName(), connectionInfo.getHostPort());
		}
		else if (hostAddress != null) {
			connectionInfo = connectionInfo.withHostAddress(hostAddress);
		}

		if (forwardedFor != null && !forwardedFor.isEmpty()) {
			connectionInfo = connectionInfo.withRemoteAddress(
					// Deliberately suppress "NullAway"
					// This implementation is invoked always with InetSocketAddress and remote address != null
					AddressUtils.parseAddress(forwardedFor, connectionInfo.getRemoteAddress().getPort(), DEFAULT_FORWARDED_HEADER_VALIDATION));
		}
		return connectionInfo;
	}

	static boolean containsQuote(String header, int start, int end) {
		for (int i = start; i < end; i++) {
			if (header.charAt(i) == '"') {
				return true;
			}
		}
		return false;
	}

	static String extractValue(String header, int start, int end, boolean quoted) {
		if (start >= end) {
			return "";
		}
		int escapedIndex = quoted ? header.indexOf('\\', start) : -1;
		if (escapedIndex < 0 || escapedIndex >= end) {
			return header.substring(start, end).trim();
		}
		StringBuilder value = new StringBuilder(end - start);
		value.append(header, start, escapedIndex);
		for (int i = escapedIndex; i < end; i++) {
			char c = header.charAt(i);
			if (c == '\\' && i + 1 < end) {
				c = header.charAt(++i);
			}
			value.append(c);
		}
		return value.toString().trim();
	}

	static int findClosingQuote(String header, int contentStart) {
		int length = header.length();
		for (int i = contentStart; i < length; i++) {
			char c = header.charAt(i);
			if (c == '"') {
				return i;
			}
			else if (c == '\\') {
				i++;
			}
		}
		return -1;
	}

	static boolean isAlpha(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
	}

	static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	static boolean isValidScheme(String scheme) {
		int length = scheme.length();
		if (length == 0 || !isAlpha(scheme.charAt(0))) {
			return false;
		}
		for (int i = 1; i < length; i++) {
			char c = scheme.charAt(i);
			if (!isAlpha(c) && !isDigit(c) && c != '+' && c != '-' && c != '.') {
				return false;
			}
		}
		return true;
	}

	static boolean matchesParam(String header, int nameStart, String param) {
		return AsciiString.regionMatches(header, true, nameStart, param, 0, param.length());
	}

	static int skipOptionalWhitespace(String header, int index) {
		int length = header.length();
		while (index < length) {
			char c = header.charAt(index);
			if (c != ' ' && c != '\t') {
				break;
			}
			index++;
		}
		return index;
	}

	static int skipToDelimiter(String header, int index) {
		int length = header.length();
		while (index < length) {
			char c = header.charAt(index);
			if (c == ',' || c == ';') {
				break;
			}
			if (c == '"') {
				int closingQuote = findClosingQuote(header, index + 1);
				if (closingQuote < 0) {
					return length;
				}
				index = closingQuote + 1;
				continue;
			}
			index++;
		}
		return index;
	}

	static int trimTrailingWhitespace(String header, int start, int end) {
		while (start < end) {
			char c = header.charAt(end - 1);
			if (c != ' ' && c != '\t') {
				break;
			}
			end--;
		}
		return end;
	}
}
