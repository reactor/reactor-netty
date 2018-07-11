/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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
package reactor.netty;

import io.netty.channel.Channel;

/**
 * @author Violeta Georgieva
 */
public final class LogFormatter {
	/**
	 * Specifies whether the channel ID will be prepended to the log message when possible.
	 * By default it will be prepended.
	 */
	static final boolean LOG_CHANNEL_INFO =
			Boolean.parseBoolean(System.getProperty(SystemPropertiesNames.LOG_CHANNEL_INFO, "true"));

	private LogFormatter() {
	}

	public static String format(Channel channel, String msg) {
		if (LOG_CHANNEL_INFO) {
			String channelStr = channel.toString();
			return new StringBuilder(channelStr.length() + 1 + msg.length())
					.append(channel)
					.append(' ')
					.append(msg)
					.toString();
		}
		else {
			return msg;
		}
	}
}
