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
package reactor.netty.http.server.logging;

import static reactor.netty.http.server.logging.AbstractAccessLogArgProvider.MISSING;

/**
 * A factory for {@link AccessLog}.
 *
 * @author limaoning
 */
@FunctionalInterface
public interface AccessLogFactory {

	String DEFAULT_LOG_FORMAT =
			"{} - {} [{}] \"{} {} {}\" {} {} {} {} ms";
	AccessLogFactory DEFAULT = args -> AccessLog.create(DEFAULT_LOG_FORMAT, args.address(), args.user(),
			args.zonedDateTime(), args.method(), args.uri(), args.protocol(), args.status(),
			(args.contentLength() > -1 ? args.contentLength() : MISSING), args.port(), args.duration());

	/**
	 * Create an {@link AccessLog}.
	 *
	 * @param accessLogArgProvider the provider of the args required for access log
	 * @return the {@link AccessLog}
	 */
	AccessLog create(AccessLogArgProvider accessLogArgProvider);

}
