/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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
package reactor.netty.http.server;

import java.net.InetSocketAddress;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.netty.channel.Channel;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.ssl.SslHandler;
import reactor.netty.tcp.InetSocketAddressUtil;
/**
 * Resolve information about the current connection, including the
 * host (server) address, the remote (client) address and the scheme.
 *
 * <p>Depending on the chosen factory method, the information
 * can be retrieved directly from the channel or additionally
 * using the {@code "Forwarded"}, or {@code "X-Forwarded-*"}
 * HTTP request headers.
 *
 * @author Brian Clozel
 * @since 0.8
 * @see <a href="https://tools.ietf.org/html/rfc7239">rfc7239</a>
 */
final class ConnectionInfo {

	static final String  FORWARDED_HEADER        = "Forwarded";

	static final Pattern FORWARDED_HOST_PATTERN  = Pattern.compile("host=\"?([^;,\"]+)\"?");
	static final Pattern FORWARDED_PROTO_PATTERN = Pattern.compile("proto=\"?([^;,\"]+)\"?");
	static final Pattern FORWARDED_FOR_PATTERN   = Pattern.compile("for=\"?([^;,\"]+)\"?");
	static final String XFORWARDED_IP_HEADER = "X-Forwarded-For";

	static final String XFORWARDED_HOST_HEADER = "X-Forwarded-Host";
	static final String XFORWARDED_PORT_HEADER = "X-Forwarded-Port";
	static final String XFORWARDED_PROTO_HEADER = "X-Forwarded-Proto";
	final InetSocketAddress hostAddress;

	final InetSocketAddress remoteAddress;

	final String scheme;

	static ConnectionInfo from(Channel channel, boolean headers, HttpRequest request) {
		if (headers) {
			return ConnectionInfo.newForwardedConnectionInfo(request, channel);
		}
		else {
			return ConnectionInfo.newConnectionInfo(channel);
		}
	}

	/**
	 * Retrieve the connection information from the current connection directly
	 * @param c the current channel
	 * @return the connection information
	 */
	static ConnectionInfo newConnectionInfo(Channel c) {
		SocketChannel channel = (SocketChannel) c; 
		InetSocketAddress hostAddress = channel.localAddress();
		InetSocketAddress remoteAddress = channel.remoteAddress();
		String scheme = channel.pipeline().get(SslHandler.class) != null ? "https" : "http";
		return new ConnectionInfo(hostAddress, remoteAddress, scheme);
	}

	/**
	 * Retrieve the connection information from the {@code "Forwarded"}/{@code "X-Forwarded-*"}
	 * HTTP request headers, or from the current connection directly if none are found.
	 * @param request the current server request
	 * @param channel the current channel
	 * @return the connection information
	 */
	static ConnectionInfo newForwardedConnectionInfo(HttpRequest request, Channel channel) {
		if (request.headers().contains(FORWARDED_HEADER)) {
			return parseForwardedInfo(request, (SocketChannel)channel);
		}
		else {
			return parseXForwardedInfo(request, (SocketChannel)channel);
		}
	}

	static ConnectionInfo parseForwardedInfo(HttpRequest request, SocketChannel channel) {
		InetSocketAddress hostAddress = channel.localAddress();
		InetSocketAddress remoteAddress = channel.remoteAddress();
		String scheme = channel.pipeline().get(SslHandler.class) != null ? "https" : "http";

		String forwarded = request.headers().get(FORWARDED_HEADER).split(",")[0];
		Matcher hostMatcher = FORWARDED_HOST_PATTERN.matcher(forwarded);
		if (hostMatcher.find()) {
			hostAddress = parseAddress(hostMatcher.group(1), hostAddress.getPort());
		}
		Matcher protoMatcher = FORWARDED_PROTO_PATTERN.matcher(forwarded);
		if (protoMatcher.find()) {
			scheme = protoMatcher.group(1).trim();
		}
		Matcher forMatcher = FORWARDED_FOR_PATTERN.matcher(forwarded);
		if(forMatcher.find()) {
			remoteAddress = parseAddress(forMatcher.group(1).trim(), remoteAddress.getPort());
		}
		return new ConnectionInfo(hostAddress, remoteAddress, scheme);
	}

	static InetSocketAddress parseAddress(String address, int defaultPort) {
		int portSeparatorIdx = address.lastIndexOf(":");
		if (portSeparatorIdx > address.lastIndexOf("]")) {
			return InetSocketAddressUtil.createUnresolved(address.substring(0, portSeparatorIdx),
					Integer.parseInt(address.substring(portSeparatorIdx + 1)));
		}
		else {
			return InetSocketAddressUtil.createUnresolved(address, defaultPort);
		}
	}

	static ConnectionInfo parseXForwardedInfo(HttpRequest request, SocketChannel channel) {
		InetSocketAddress hostAddress = channel.localAddress();
		InetSocketAddress remoteAddress = channel.remoteAddress();
		String scheme = channel.pipeline().get(SslHandler.class) != null ? "https" : "http";
		if (request.headers().contains(XFORWARDED_IP_HEADER)) {
			String hostValue = request.headers().get(XFORWARDED_IP_HEADER).split(",")[0];
			hostAddress = parseAddress(hostValue, hostAddress.getPort());
		}
		else if(request.headers().contains(XFORWARDED_HOST_HEADER)) {
			if(request.headers().contains(XFORWARDED_PORT_HEADER)) {
				hostAddress = InetSocketAddressUtil.createUnresolved(
						request.headers().get(XFORWARDED_HOST_HEADER).split(",")[0].trim(),
						Integer.parseInt(request.headers().get(XFORWARDED_PORT_HEADER).split(",")[0].trim()));
			}
			else {
				hostAddress = InetSocketAddressUtil.createUnresolved(
						request.headers().get(XFORWARDED_HOST_HEADER).split(",")[0].trim(),
						channel.localAddress().getPort());
			}
		}
		if (request.headers().contains(XFORWARDED_PROTO_HEADER)) {
			scheme = request.headers().get(XFORWARDED_PROTO_HEADER).trim();
		}
		return new ConnectionInfo(hostAddress, remoteAddress, scheme);
	}

	ConnectionInfo(InetSocketAddress hostAddress, InetSocketAddress remoteAddress, String scheme) {
		this.hostAddress = hostAddress;
		this.remoteAddress = remoteAddress;
		this.scheme = scheme;
	}

	InetSocketAddress getHostAddress() {
		return hostAddress;
	}

	InetSocketAddress getRemoteAddress() {
		return remoteAddress;
	}

	String getScheme() {
		return scheme;
	}
}
