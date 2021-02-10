/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
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

package reactor.netty.transport;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.function.Supplier;

import io.netty.channel.unix.DomainSocketAddress;
import io.netty.util.NetUtil;
import reactor.util.annotation.Nullable;

import static java.util.Objects.requireNonNull;

/**
 * Internal class that creates unresolved or resolved InetSocketAddress instances
 *
 * Numeric IPv4 and IPv6 addresses will be detected and parsed by using Netty's
 * {@link NetUtil#createByteArrayFromIpAddressString} utility method and the
 * InetSocketAddress instances will created in a way that these instances are resolved
 * initially. This removes the need to do unnecessary reverse DNS lookups.
 */
public final class AddressUtils {

	private AddressUtils() {
	}

	/**
	 * Creates InetSocketAddress instance. Numeric IP addresses will be detected and
	 * resolved without doing reverse DNS lookups.
	 *
	 * @param hostname ip-address or hostname
	 * @param port port number
	 * @param resolve when true, resolve given hostname at instance creation time
	 * @return InetSocketAddress for given parameters
	 */
	public static InetSocketAddress createInetSocketAddress(String hostname, int port, boolean resolve) {
		requireNonNull(hostname, "hostname");
		InetSocketAddress inetAddressForIpString = createForIpString(hostname, port);
		if (inetAddressForIpString != null) {
			return inetAddressForIpString;
		}
		else {
			return resolve ? new InetSocketAddress(hostname, port) : InetSocketAddress.createUnresolved(hostname, port);
		}
	}

	/**
	 * Creates InetSocketAddress that is always resolved. Numeric IP addresses will be
	 * detected and resolved without doing reverse DNS lookups.
	 *
	 * @param hostname ip-address or hostname
	 * @param port port number
	 * @return InetSocketAddress for given parameters
	 */
	public static InetSocketAddress createResolved(String hostname, int port) {
		return createInetSocketAddress(hostname, port, true);
	}

	/**
	 * Creates unresolved InetSocketAddress. Numeric IP addresses will be detected and
	 * resolved.
	 *
	 * @param hostname ip-address or hostname
	 * @param port port number
	 * @return InetSocketAddress for given parameters
	 */
	public static InetSocketAddress createUnresolved(String hostname, int port) {
		return createInetSocketAddress(hostname, port, false);
	}

	/**
	 * Parse unresolved InetSocketAddress. Numeric IP addresses will be detected and
	 * resolved.
	 *
	 * @param address ip-address or hostname
	 * @param defaultPort the default port
	 * @return InetSocketAddress for given parameters
	 */
	public static InetSocketAddress parseAddress(String address, int defaultPort) {
		requireNonNull(address, "address");
		int separatorIdx = address.lastIndexOf(':');
		int ipV6HostSeparatorIdx = address.lastIndexOf(']');
		if (separatorIdx > ipV6HostSeparatorIdx) {
			if (separatorIdx == address.indexOf(':') || ipV6HostSeparatorIdx > -1) {
				String port = address.substring(separatorIdx + 1);
				if (port.chars().allMatch(Character::isDigit)) {
					return AddressUtils.createUnresolved(address.substring(0, separatorIdx),
							Integer.parseInt(port));
				}
				else {
					return AddressUtils.createUnresolved(address.substring(0, separatorIdx), defaultPort);
				}
			}
		}
		return AddressUtils.createUnresolved(address, defaultPort);
	}

	/**
	 * Replaces an unresolved InetSocketAddress with a resolved instance in the case that
	 * the passed address is a numeric IP address (both IPv4 and IPv6 are supported).
	 *
	 * @param inetSocketAddress socket address instance to process
	 * @return processed socket address instance
	 */
	public static InetSocketAddress replaceUnresolvedNumericIp(InetSocketAddress inetSocketAddress) {
		requireNonNull(inetSocketAddress, "inetSocketAddress");
		if (!inetSocketAddress.isUnresolved()) {
			return inetSocketAddress;
		}
		InetSocketAddress inetAddressForIpString = createForIpString(
				inetSocketAddress.getHostString(), inetSocketAddress.getPort());
		if (inetAddressForIpString != null) {
			return inetAddressForIpString;
		}
		else {
			return inetSocketAddress;
		}
	}

	/**
	 * Replaces an unresolved InetSocketAddress with a resolved instance in the case that
	 * the passed address is unresolved.
	 *
	 * @param inetSocketAddress socket address instance to process
	 * @return resolved instance with same host string and port
	 */
	public static InetSocketAddress replaceWithResolved(InetSocketAddress inetSocketAddress) {
		requireNonNull(inetSocketAddress, "inetSocketAddress");
		if (!inetSocketAddress.isUnresolved()) {
			return inetSocketAddress;
		}
		inetSocketAddress = replaceUnresolvedNumericIp(inetSocketAddress);
		if (!inetSocketAddress.isUnresolved()) {
			return inetSocketAddress;
		}
		else {
			return new InetSocketAddress(inetSocketAddress.getHostString(), inetSocketAddress.getPort());
		}
	}

	/**
	 * Update the provided address with the new host string.
	 *
	 * @param address the address supplier
	 * @param host the new host string
	 * @return the updated address
	 */
	public static SocketAddress updateHost(@Nullable Supplier<? extends SocketAddress> address, String host) {
		if (address == null) {
			return createUnresolved(host, 0);
		}

		SocketAddress socketAddress = address.get();
		if (socketAddress instanceof DomainSocketAddress) {
			throw new IllegalArgumentException("Cannot update DomainSocketAddress with host name [" + host + "].");
		}

		if (!(socketAddress instanceof InetSocketAddress)) {
			return createUnresolved(host, 0);
		}

		InetSocketAddress inet = (InetSocketAddress) socketAddress;

		return createUnresolved(host, inet.getPort());
	}

	/**
	 * Update the provided address with the new port.
	 *
	 * @param address the address supplier
	 * @param port the new port
	 * @return the updated address
	 */
	public static SocketAddress updatePort(@Nullable Supplier<? extends SocketAddress> address, int port) {
		if (address == null) {
			return createUnresolved(NetUtil.LOCALHOST.getHostAddress(), port);
		}

		SocketAddress socketAddress = address.get();
		if (socketAddress instanceof DomainSocketAddress) {
			throw new IllegalArgumentException("Cannot update DomainSocketAddress with post number [" + port + "].");
		}

		if (!(address.get() instanceof InetSocketAddress)) {
			return createUnresolved(NetUtil.LOCALHOST.getHostAddress(), port);
		}

		InetSocketAddress inet = (InetSocketAddress) address.get();

		InetAddress addr = inet.getAddress();

		String host = addr == null ? inet.getHostName() : addr.getHostAddress();

		return createUnresolved(host, port);
	}

	@Nullable
	static InetAddress attemptParsingIpString(String hostname) {
		byte[] ipAddressBytes = NetUtil.createByteArrayFromIpAddressString(hostname);

		if (ipAddressBytes != null) {
			try {
				if (ipAddressBytes.length == 4) {
					return Inet4Address.getByAddress(ipAddressBytes);
				}
				else {
					return Inet6Address.getByAddress(null, ipAddressBytes, -1);
				}
			}
			catch (UnknownHostException e) {
				throw new RuntimeException(e); // Should never happen
			}
		}

		return null;
	}

	@Nullable
	static InetSocketAddress createForIpString(String hostname, int port) {
		InetAddress inetAddressForIpString = attemptParsingIpString(hostname);
		if (inetAddressForIpString != null) {
			return new InetSocketAddress(inetAddressForIpString, port);
		}
		return null;
	}

}
