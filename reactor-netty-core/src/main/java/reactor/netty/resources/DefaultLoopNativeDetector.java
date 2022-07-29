/*
 * Copyright (c) 2018-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.resources;

/**
 * Provides an {@link DefaultLoop} instance based on the available transport.
 *
 * @author Violeta Georgieva
 */
final class DefaultLoopNativeDetector {

	static final DefaultLoop INSTANCE;

	static final DefaultLoop NIO;

	static {
		NIO = new DefaultLoopNIO();

		if (DefaultLoopIOUring.isIoUringAvailable) {
			INSTANCE = new DefaultLoopIOUring();
		}
		else if (DefaultLoopEpoll.isEpollAvailable) {
			INSTANCE = new DefaultLoopEpoll();
		}
		else if (DefaultLoopKQueue.isKqueueAvailable) {
			INSTANCE = new DefaultLoopKQueue();
		}
		else {
			INSTANCE = NIO;
		}
	}
}
