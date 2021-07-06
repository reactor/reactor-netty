/*
 * Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty;

/**
 * Provides short/long string representation of the channel.
 *
 * @author Violeta Georgieva
 * @since 1.0.5
 */
public interface ChannelOperationsId {

	/**
	 * The short string is a combination of the id of the underlying connection
	 * and in case of HTTP, the serial number of the request received on that connection.
	 * <p>Format of the short string:
	 * <pre>
	 * {@code <CONNECTION_ID>-<REQUEST_NUMBER>}
	 * </pre>
	 * </p>
	 * <p>
	 * Example:
	 * <pre>
	 * {@code
	 *     <CONNECTION_ID>: 329c6ffd
	 *     <REQUEST_NUMBER>: 5
	 *
	 *     Result: 329c6ffd-5
	 * }
	 * </pre>
	 * </p>
	 */
	String asShortText();

	/**
	 * The long string is a combination of the id of the underlying connection, local and remote addresses,
	 * and in case of HTTP, the serial number of the request received on that connection.
	 * <p>Format of the long string:
	 * <pre>
	 * {@code <CONNECTION_ID>-<REQUEST_NUMBER>, L:<LOCAL_ADDRESS> <CONNECTION_OPENED_CLOSED> R:<REMOTE_ADDRESS>}
	 * </pre>
	 * </p>
	 * <p>
	 * Example:
	 * <pre>
	 * {@code
	 * Opened connection
	 *     <CONNECTION_ID>: 329c6ffd
	 *     <REQUEST_NUMBER>: 5
	 *     <LOCAL_ADDRESS>: /0:0:0:0:0:0:0:1:64286
	 *     <CONNECTION_OPENED_CLOSED>: - (opened)
	 *     <REMOTE_ADDRESS>: /0:0:0:0:0:0:0:1:64284
	 *
	 *     Result: 329c6ffd-5, L:/0:0:0:0:0:0:0:1:64286 - R:/0:0:0:0:0:0:0:1:64284
	 *
	 * Closed connection
	 *     <CONNECTION_ID>: 329c6ffd
	 *     <REQUEST_NUMBER>: 5
	 *     <LOCAL_ADDRESS>: /0:0:0:0:0:0:0:1:64286
	 *     <CONNECTION_OPENED_CLOSED>: ! (closed)
	 *     <REMOTE_ADDRESS>: /0:0:0:0:0:0:0:1:64284
	 *
	 *     Result: 329c6ffd-5, L:/0:0:0:0:0:0:0:1:64286 ! R:/0:0:0:0:0:0:0:1:64284
	 * }
	 * </pre>
	 * </p>
	 */
	String asLongText();
}
