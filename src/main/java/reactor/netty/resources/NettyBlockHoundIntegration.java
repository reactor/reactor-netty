package reactor.netty.resources;

import io.netty.channel.ChannelInitializer;
import io.netty.util.concurrent.AbstractEventExecutor;
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

		//prevent blocking call in any netty event executor
		builder.disallowBlockingCallsInside(AbstractEventExecutor.class.getName(), "safeExecute");
	}

}
