package reactor.ipc.netty.channel;

import java.util.concurrent.TimeoutException;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.ReadTimeoutException;

public class TimeoutHandler extends ChannelDuplexHandler {

	private static final TimeoutException INSTANCE = new TimeoutException();

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		if (cause instanceof ReadTimeoutException) {
			ctx.fireExceptionCaught(INSTANCE);
		} else {
			super.exceptionCaught(ctx, cause);
		}
	}
}
