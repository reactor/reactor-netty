/*
 * Copyright (c) 2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.logging;

/**
 * An abstract factory for generating one and the same type of log message
 * no matter the log level that will be used.
 *
 * @author Violeta Georgieva
 * @since 1.0.24
 */
public abstract class AbstractHttpMessageLogFactory implements HttpMessageLogFactory {

	@Override
	public String trace(HttpMessageArgProvider arg) {
		return common(arg);
	}

	@Override
	public String debug(HttpMessageArgProvider arg) {
		return common(arg);
	}

	@Override
	public String info(HttpMessageArgProvider arg) {
		return common(arg);
	}

	@Override
	public String warn(HttpMessageArgProvider arg) {
		return common(arg);
	}

	@Override
	public String error(HttpMessageArgProvider arg) {
		return common(arg);
	}

	public abstract String common(HttpMessageArgProvider arg);
}
