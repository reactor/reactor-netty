/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.http;

import reactor.ipc.netty.options.ChannelResources;

/**
 * Hold the default Http event loops
 *
 * @author Stephane Maldini
 * @since 0.6
 */
public final class HttpResources {

	/**
	 * Return the global HTTP event loop selector
	 * @return the global HTTP event loop selector
	 */
	public static ChannelResources defaultHttpLoops(){
		return DEFAULT_HTTP_LOOPS;
	}

	static final ChannelResources DEFAULT_HTTP_LOOPS = ChannelResources.create("http");

	HttpResources(){}
}
