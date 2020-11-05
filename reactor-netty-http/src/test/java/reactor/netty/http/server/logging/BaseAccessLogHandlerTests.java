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

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.http.server.logging.AbstractAccessLogArgProvider.MISSING;

/**
 * @author limaoning
 */
class BaseAccessLogHandlerTests {

	@Test
	void accessLog() {
		BaseAccessLogHandler handler = new BaseAccessLogHandler(null);
		assertThat(handler.accessLog).isEqualTo(BaseAccessLogHandler.DEFAULT_ACCESS_LOG);

		Function<AccessLogArgProvider, AccessLog> accessLog =
				args -> AccessLog.create("{}", args.remoteAddress());
		handler = new BaseAccessLogHandler(accessLog);
		assertThat(handler.accessLog).isEqualTo(accessLog);
	}

	@Test
	void applyAddress() {
		assertThat(BaseAccessLogHandler.applyAddress(new InetSocketAddress("127.0.0.1", 8080)))
				.isEqualTo("127.0.0.1:8080");
		assertThat(BaseAccessLogHandler.applyAddress(null)).isEqualTo(MISSING);
	}

}
