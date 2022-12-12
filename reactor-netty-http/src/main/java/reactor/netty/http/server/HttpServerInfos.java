/*
 * Copyright (c) 2021-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.handler.codec.http.cookie.Cookie;
import reactor.netty.http.HttpInfos;

import java.util.List;
import java.util.Map;

/**
 * An Http Reactive Channel with several accessors related to HTTP flow: headers, params,
 * URI, method, websocket...
 *
 * @author Violeta Georgieva
 * @since 1.0.8
 */
public interface HttpServerInfos extends HttpInfos, ConnectionInformation {

	/**
	 * Returns resolved HTTP cookies. As opposed to {@link #cookies()}, this
	 * returns all cookies, even if they have the same name.
	 *
	 * @return Resolved HTTP cookies
	 */
	Map<CharSequence, List<Cookie>> allCookies();
}
