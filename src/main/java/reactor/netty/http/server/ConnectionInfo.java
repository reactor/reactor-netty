/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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

import java.net.InetSocketAddress;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.netty.channel.Channel;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AttributeKey;
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
	static final Pattern PORT_PATTERN = Pattern.compile("\\d*");
	static final String XFORWARDED_IP_HEADER = "X-Forwarded-For";

	static final String XFORWARDED_HOST_HEADER = "X-Forwarded-Host";
	static final String XFORWARDED_PORT_HEADER = "X-Forwarded-Port";
	static final String XFORWARDED_PROTO_HEADER = "X-Forwarded-Proto";

	static final AttributeKey<InetSocketAddress> REMOTE_ADDRESS_FROM_PROXY_PROTOCOL =
			AttributeKey.valueOf("remoteAddressFromProxyProtocol");

	final InetSocketAddress hostAddress;

	final InetSocketAddress remoteAddress;

	final String scheme;

	static ConnectionInfo from(Channel channel, boolean headers, HttpRequest request, boolean secured) {
		if (headers) {
			return ConnectionInfo.newForwardedConnectionInfo(request, channel, secured);
		}
		else {
			return ConnectionInfo.newConnectionInfo(channel, secured);
		}
	}

	/**
	 * Retrieve the connection information from the current connection directly
	 * @param c the current channel
	 * @param secured is transport secure (SSL)
	 * @return the connection information
	 */
	static ConnectionInfo newConnectionInfo(Channel c, boolean secured) {
		SocketChannel channel = (SocketChannel) c; 
		InetSocketAddress hostAddress = channel.localAddress();
		InetSocketAddress remoteAddress = getRemoteAddress(channel);
		String scheme = secured ? "https" : "http";
		return new ConnectionInfo(hostAddress, remoteAddress, scheme);
	}

	/**
	 * Retrieve the connection information from the {@code "Forwarded"}/{@code "X-Forwarded-*"}
	 * HTTP request headers, or from the current connection directly if none are found.
	 * @param request the current server request
	 * @param channel the current channel
	 * @param secured is transport secure (SSL)
	 * @return the connection information
	 */
	static ConnectionInfo newForwardedConnectionInfo(HttpRequest request, Channel channel, boolean secured) {
		if (request.headers().contains(FORWARDED_HEADER)) {
			return parseForwardedInfo(request, (SocketChannel)channel, secured);
		}
		else {
			return parseXForwardedInfo(request, (SocketChannel)channel, secured);
		}
	}

	static ConnectionInfo parseForwardedInfo(HttpRequest request, SocketChannel channel, boolean secured) {
		InetSocketAddress hostAddress = channel.localAddress();
		InetSocketAddress remoteAddress = getRemoteAddress(channel);
		String scheme = secured ? "https" : "http";

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
		int separatorIdx = address.lastIndexOf(":");
		int ipV6HostSeparatorIdx = address.lastIndexOf("]");
		if(separatorIdx > ipV6HostSeparatorIdx) {
			if(separatorIdx == address.indexOf(":") || ipV6HostSeparatorIdx > -1) {
				String port = address.substring(separatorIdx + 1);
				if (PORT_PATTERN.matcher(port).matches()) {
					return InetSocketAddressUtil.createUnresolved(address.substring(0, separatorIdx),
							Integer.parseInt(port));
				}
				else {
					return InetSocketAddressUtil.createUnresolved(address.substring(0, separatorIdx), defaultPort);
				}
			}
		}
		return InetSocketAddressUtil.createUnresolved(address, defaultPort);
	}

	static ConnectionInfo parseXForwardedInfo(HttpRequest request, SocketChannel channel, boolean secured) {
		InetSocketAddress hostAddress = channel.localAddress();
		InetSocketAddress remoteAddress = getRemoteAddress(channel);
		String scheme =  secured ? "https" : "http";
		if (request.headers().contains(XFORWARDED_IP_HEADER)) {
			String remoteIpValue = request.headers().get(XFORWARDED_IP_HEADER).split(",")[0];
			remoteAddress = parseAddress(remoteIpValue, remoteAddress.getPort());
		}
		if(request.headers().contains(XFORWARDED_HOST_HEADER)) {
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

	private static InetSocketAddress getRemoteAddress(SocketChannel channel) {
		if (HAProxyMessageReader.hasProxyProtocol()) {
			InetSocketAddress remoteAddressFromProxyProtocol =
					channel.attr(REMOTE_ADDRESS_FROM_PROXY_PROTOCOL).getAndSet(null);

			if (remoteAddressFromProxyProtocol != null) {
				return remoteAddressFromProxyProtocol;
			}
		}

		return channel.remoteAddress();
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
