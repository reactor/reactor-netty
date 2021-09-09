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
package reactor.netty.quic;

import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;

/**
 * Exposes information for the {@link QuicStreamChannel} as stream id etc.
 *
 * @author Violeta Georgieva
 */
public interface QuicStreamInfo {

	/**
	 * Returns {@code true} if the stream was created by this peer.
	 *
	 * @return {@code true} if created by this peer, {@code false} otherwise.
	 */
	boolean isLocalStream();

	/**
	 * The id of the stream.
	 *
	 * @return the stream id of this {@link QuicStreamChannel}.
	 */
	long streamId();

	/**
	 * Returns the {@link QuicStreamType} of the stream.
	 *
	 * @return {@link QuicStreamType} of this stream.
	 */
	QuicStreamType streamType();
}
