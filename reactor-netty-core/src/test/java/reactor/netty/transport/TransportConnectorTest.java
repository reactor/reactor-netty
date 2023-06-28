package reactor.netty.transport;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import org.junit.jupiter.api.Test;
import reactor.netty.tcp.TcpClient;
import reactor.netty.tcp.TcpClientConfig;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportConnectorTest {

	@Test
	void bind_whenBindException_thenChannelIsUnregistered() {
		final TcpClientConfig transportConfig = TcpClient.newConnection().configuration();
		final Channel channel1 = TransportConnector.bind(
				transportConfig,
				new RecordingChannelInitializer(),
				new InetSocketAddress("localhost", 0),
				false).block();
		final RecordingChannelInitializer channelInitializer = new RecordingChannelInitializer();
		assertThrows(Throwable.class, () -> TransportConnector.bind(
				transportConfig,
				channelInitializer,
				new InetSocketAddress("localhost", ((InetSocketAddress) channel1.localAddress()).getPort()),
				false).block());
		final Channel channel2 = channelInitializer.channel;
		assertTrue(channel1.isRegistered());
		assertFalse(channel2.isRegistered());
		channel1.close();
	}

	private static class RecordingChannelInitializer extends ChannelInitializer<Channel> {
		Channel channel;
		@Override
		protected void initChannel(final Channel ch) {
			channel = ch;
		}
	}
}