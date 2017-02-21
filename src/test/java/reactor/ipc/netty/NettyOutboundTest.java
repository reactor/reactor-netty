package reactor.ipc.netty;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.*;

/**
 * @author Simon Baslé
 */
public class NettyOutboundTest {

	@Test
	public void onWriteIdleReplaces() throws Exception {
		EmbeddedChannel channel = new EmbeddedChannel();
		NettyContext mockContext = () -> channel;
		NettyOutbound outbound = () -> mockContext;

		AtomicLong idle1 = new AtomicLong();
		AtomicLong idle2 = new AtomicLong();

		outbound.onWriteIdle(100, idle1::incrementAndGet);
		outbound.onWriteIdle(150, idle2::incrementAndGet);
		ReactorNetty.OutboundIdleStateHandler idleStateHandler =
				(ReactorNetty.OutboundIdleStateHandler) channel.pipeline().get(NettyPipeline.OnChannelWriteIdle);
		idleStateHandler.onWriteIdle.run();

		assertThat(channel.pipeline().names(), is(Arrays.asList(
				NettyPipeline.OnChannelWriteIdle,
				"DefaultChannelPipeline$TailContext#0")));

		assertThat(idle1.intValue(), is(0));
		assertThat(idle2.intValue(), is(1));
	}

}