package reactor.ipc.netty.http.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpMessage;
import reactor.ipc.netty.NettyPipeline;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * @author mostroverkhov
 */
class CompressionHandler extends ChannelDuplexHandler {

    private final HttpServerOptions.Compression compression;
    private int bodyCompressThreshold;
    private final Queue<Object> messages = new ArrayDeque<>();

    CompressionHandler(HttpServerOptions.Compression compression) {
        this.compression = compression;
        this.bodyCompressThreshold = compression.getMinResponseSize();
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (compression.isEnabled()) {
            if (msg instanceof ByteBuf) {
                offerByteBuf(ctx, msg, promise);
            } else if (msg instanceof HttpMessage) {
                offerHttpMessage(msg, promise);
            }
        } else {
            super.write(ctx, msg, promise);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt == NettyPipeline.responseWriteCompletedEvent()) {
            if (bodyCompressThreshold > 0 || !messages.isEmpty()) {
                while (!messages.isEmpty()) {
                    Object msg = messages.poll();
                    writeSkipCompress(ctx, msg);
                }
            }
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        releaseMsgs();
        super.exceptionCaught(ctx, cause);
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        releaseMsgs();
        super.close(ctx, promise);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        ChannelPipeline cp = ctx.pipeline();
        if (compression.isEnabled()) {
            addCompressionHandlerOnce(ctx, cp);
        }
    }

    private void offerHttpMessage(Object msg, ChannelPromise p) {
        messages.offer(msg);
        p.setSuccess();
    }

    private void offerByteBuf(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        ByteBuf byteBuf = (ByteBuf) msg;
        messages.offer(byteBuf);
        if (bodyCompressThreshold > 0) {
            bodyCompressThreshold -= byteBuf.readableBytes();
        }
        drain(ctx, promise);
    }

    private void drain(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        if (bodyCompressThreshold <= 0) {
            while (!messages.isEmpty()) {
                Object message = messages.poll();
                writeCompress(ctx, message, promise);
            }
        }
    }

    private void writeCompress(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        ctx.write(msg, promise);
    }

    private void writeSkipCompress(ChannelHandlerContext ctx, Object msg) throws Exception {
        ctx.write(FilteringHttpContentCompressor.FilterMessage.wrap(msg));
    }

    private void releaseMsgs() {
        while (!(messages.isEmpty())) {
            Object msg = messages.poll();
            if (msg instanceof ByteBuf) {
                ((ByteBuf) msg).release();
            }
        }
    }

    private void addCompressionHandlerOnce(ChannelHandlerContext ctx, ChannelPipeline cp) {
        if (cp.get(FilteringHttpContentCompressor.class) == null) {
            ctx.pipeline().addBefore(NettyPipeline.CompressionHandler, NettyPipeline.HttpCompressor,
                    new FilteringHttpContentCompressor());
        }
    }
}


