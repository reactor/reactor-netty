package reactor.ipc.netty.channel;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.ReadTimeoutException;

public class TimeoutHandler extends ChannelDuplexHandler {

	private final Duration responseTimeout;
	private final String exceptionPrefix;

	public TimeoutHandler(String exceptionPrefix, Duration responseTimeout) {
		this.responseTimeout = responseTimeout;
		this.exceptionPrefix = exceptionPrefix;
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		if (cause instanceof ReadTimeoutException) {
			ctx.fireExceptionCaught(new TimeoutException(exceptionPrefix + responseTimeout));
		} else {
			super.exceptionCaught(ctx, cause);
		}
	}
}
