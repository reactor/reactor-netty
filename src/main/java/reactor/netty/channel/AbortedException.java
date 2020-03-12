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

package reactor.netty.channel;

import java.io.IOException;
import java.net.SocketException;

/**
 * An exception marking prematurely or unexpectedly closed inbound
 *
 * @author Stephane Maldini
 * @since 0.6
 */
public class AbortedException extends RuntimeException {

	public AbortedException(String message) {
		super(message);
	}

	/**
	 * Return true if connection has been simply aborted on a tcp level by verifying if
	 * the given inbound error.
	 *
	 * @param err an inbound exception
	 *
	 * @return true if connection has been simply aborted on a tcp level
	 */
	public static boolean isConnectionReset(Throwable err) {
		return err instanceof AbortedException ||
		       (err instanceof IOException && (err.getMessage() == null ||
		                                       err.getMessage()
		                                          .contains("Broken pipe") ||
		                                       err.getMessage()
		                                          .contains("Connection reset by peer"))) ||
		       (err instanceof SocketException && err.getMessage() != null &&
		                                          err.getMessage()
		                                             .contains("Connection reset by peer"));
	}
}
