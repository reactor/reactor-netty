/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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
package reactor.netty.resources;

import io.netty.channel.ChannelInitializer;
import io.netty.util.concurrent.AbstractEventExecutor;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import reactor.blockhound.BlockHound;
import reactor.blockhound.integration.BlockHoundIntegration;
import reactor.core.scheduler.NonBlocking;

/**
 * An internal service for automatic integration with {@link BlockHound#install(BlockHoundIntegration...)}
 *
 * @author Stephane Maldini
 */
public class NettyBlockHoundIntegration implements BlockHoundIntegration {
	@Override
	public void applyTo(BlockHound.Builder builder) {
		builder.nonBlockingThreadPredicate(current -> current.or(NonBlocking.class::isInstance));

		//allow set initialization that might use Yield
		builder.allowBlockingCallsInside(ChannelInitializer.class.getName(), "initChannel");
		//allow unbounded linked blocking queue
		builder.allowBlockingCallsInside(GlobalEventExecutor.class.getName(), "addTask");
		//allow unbounded linked blocking queue
		builder.allowBlockingCallsInside(SingleThreadEventExecutor.class.getName(), "confirmShutdown");

		//prevent blocking call in arbitrary netty event executor tasks
		builder.disallowBlockingCallsInside(AbstractEventExecutor.class.getName(), "safeExecute");
		//prevent blocking call in event loops
		builder.disallowBlockingCallsInside(SingleThreadEventExecutor.class.getName()+"$5", "run");
	}

}
