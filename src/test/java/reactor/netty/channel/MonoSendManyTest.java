package reactor.netty.channel;

import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Stephane Maldini
 */
public class MonoSendManyTest {

	@Test
	public void testPromiseSendTimeout() {
		//use an extra handler
		EmbeddedChannel channel = new EmbeddedChannel(new WriteTimeoutHandler(1), new ChannelHandlerAdapter() {});

		Mono<Void> m = MonoSendMany.objectSource(Flux.just("test"), channel, false);

		StepVerifier.create(m)
		            .then(() -> {
		            	channel.runPendingTasks(); //run flush
			            assertThat(channel.<String>readOutbound()).isEqualToIgnoringCase("test");
		            })
		            .verifyComplete();
	}
}
