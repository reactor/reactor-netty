package reactor.ipc.netty.http.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContentCompressor;

/**
 * @author mostroverkhov
 */
public class FilteringHttpContentCompressor extends HttpContentCompressor {

    public FilteringHttpContentCompressor() {
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof FilterMessage) {
            FilterMessage filterMsg = (FilterMessage) msg;
            ctx.write(filterMsg.unwrap(), promise);
        } else {
            if (msg instanceof ByteBuf) {
              msg = new DefaultHttpContent((ByteBuf) msg);
            }
            super.write(ctx, msg, promise);
        }
    }

    static final class FilterMessage {
        private final Object message;

        static FilterMessage wrap(Object msg){
            return new FilterMessage(msg);
        }

        FilterMessage(Object message) {
            this.message = message;
        }

        Object unwrap() {
            return message;
        }
    }
}
