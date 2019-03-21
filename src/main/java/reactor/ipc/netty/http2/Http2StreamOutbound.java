/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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
package reactor.ipc.netty.http2;

import reactor.ipc.netty.NettyOutbound;

/**
 * @author Violeta Georgieva
 */
public interface Http2StreamOutbound extends NettyOutbound {

	int streamId();

	/**
	 * Return the assigned HTTP/2 status
	 * @return the assigned HTTP/2 status
	 */
	CharSequence status();

	/**
	 * Set an HTTP/2 status to be sent along with the headers
	 *
	 * @param status an HTTP/2 status to be sent along with the headers
	 * @return this response
	 */
	Http2StreamOutbound status(CharSequence status);
}
