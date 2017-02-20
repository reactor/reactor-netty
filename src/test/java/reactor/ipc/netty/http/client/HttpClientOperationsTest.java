package reactor.ipc.netty.http.client;

import java.nio.charset.Charset;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.json.JsonObjectDecoder;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.Future;
import org.junit.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.ipc.netty.channel.ContextHandler;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

/**
 * @author Simon Baslé
 */
public class HttpClientOperationsTest {

	ContextHandler handler = new ContextHandler((a, b, c) -> null, null, null, null, null) {
		@Override
		public void fireContextActive(NettyContext context) {

		}

		@Override
		public void setFuture(Future future) {

		}

		@Override
		protected void doPipeline(Channel ch) {

		}

		@Override
		protected Publisher<Void> onCloseOrRelease(Channel channel) {
			return Mono.never();
		}

		@Override
		public void accept(Object o) {

		}

		@Override
		public void dispose() {

		}
	};

	@Test
	public void addDecoderReplaysLastHttp() throws Exception {
		ByteBuf buf = Unpooled.copiedBuffer("{\"foo\":1}", CharsetUtil.UTF_8);
		EmbeddedChannel channel = new EmbeddedChannel();
		HttpClientOperations ops = new HttpClientOperations(channel,
				(response, request) -> null, handler);

		ops.addDecoder(new JsonObjectDecoder());
		channel.writeInbound(new DefaultLastHttpContent(buf));

		assertThat(channel.pipeline().names().iterator().next(), is("JsonObjectDecoder$extract"));
		assertThat(channel.readInbound(), instanceOf(ByteBuf.class));
		assertThat(channel.readInbound(), instanceOf(LastHttpContent.class));
		assertThat(channel.readInbound(), nullValue());
	}

	@Test
	public void addNamedDecoderReplaysLastHttp() throws Exception {
		ByteBuf buf = Unpooled.copiedBuffer("{\"foo\":1}", CharsetUtil.UTF_8);
		EmbeddedChannel channel = new EmbeddedChannel();
		HttpClientOperations ops = new HttpClientOperations(channel,
				(response, request) -> null, handler);

		ops.addDecoder("json", new JsonObjectDecoder());
		channel.writeInbound(new DefaultLastHttpContent(buf));

		assertThat(channel.pipeline().names().iterator().next(), is("json$extract"));
		assertThat(channel.readInbound(), instanceOf(ByteBuf.class));
		assertThat(channel.readInbound(), instanceOf(LastHttpContent.class));
		assertThat(channel.readInbound(), nullValue());
	}

	@Test
	public void setNamedDecoderReplaysLastHttp() throws Exception {
		ByteBuf buf = Unpooled.copiedBuffer("{\"foo\":1}", CharsetUtil.UTF_8);
		EmbeddedChannel channel = new EmbeddedChannel();
		HttpClientOperations ops = new HttpClientOperations(channel,
				(response, request) -> null, handler);

		ops.setDecoder("json", new JsonObjectDecoder());
		channel.writeInbound(new DefaultLastHttpContent(buf));

		assertThat(channel.pipeline().names().iterator().next(), is("json$extract"));
		assertThat(channel.readInbound(), instanceOf(ByteBuf.class));
		assertThat(channel.readInbound(), instanceOf(LastHttpContent.class));
		assertThat(channel.readInbound(), nullValue());
	}

	@Test
	public void addEncoderReplaysLastHttp() throws Exception {
		ByteBuf buf = Unpooled.copiedBuffer("{\"foo\":1}", CharsetUtil.UTF_8);
		EmbeddedChannel channel = new EmbeddedChannel();
		HttpClientOperations ops = new HttpClientOperations(channel,
				(response, request) -> null, handler);

		ops.addEncoder(new JsonObjectDecoder());
		channel.writeInbound(new DefaultLastHttpContent(buf));

		assertThat(channel.pipeline().names().iterator().next(), is("JsonObjectDecoder$extract"));
		assertThat(channel.readInbound(), instanceOf(ByteBuf.class));
		assertThat(channel.readInbound(), instanceOf(LastHttpContent.class));
		assertThat(channel.readInbound(), nullValue());
	}

	@Test
	public void addNamedEncoderReplaysLastHttp() throws Exception {
		ByteBuf buf = Unpooled.copiedBuffer("{\"foo\":1}", CharsetUtil.UTF_8);
		EmbeddedChannel channel = new EmbeddedChannel();
		HttpClientOperations ops = new HttpClientOperations(channel,
				(response, request) -> null, handler);

		ops.addEncoder("json", new JsonObjectDecoder());
		channel.writeInbound(new DefaultLastHttpContent(buf));

		assertThat(channel.pipeline().names().iterator().next(), is("json$extract"));
		assertThat(channel.readInbound(), instanceOf(ByteBuf.class));
		assertThat(channel.readInbound(), instanceOf(LastHttpContent.class));
		assertThat(channel.readInbound(), nullValue());
	}

	@Test
	public void setNamedEncoderReplaysLastHttp() throws Exception {
		ByteBuf buf = Unpooled.copiedBuffer("{\"foo\":1}", CharsetUtil.UTF_8);
		EmbeddedChannel channel = new EmbeddedChannel();
		HttpClientOperations ops = new HttpClientOperations(channel,
				(response, request) -> null, handler);

		ops.setEncoder("json", new JsonObjectDecoder());
		channel.writeInbound(new DefaultLastHttpContent(buf));

		assertThat(channel.pipeline().names().iterator().next(), is("json$extract"));
		assertThat(channel.readInbound(), instanceOf(ByteBuf.class));
		assertThat(channel.readInbound(), instanceOf(LastHttpContent.class));
		assertThat(channel.readInbound(), nullValue());
	}

}