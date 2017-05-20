package reactor.ipc.netty.http;

import io.netty.handler.codec.http.HttpHeaders;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.client.HttpClientResponse;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.ipc.netty.http.server.HttpServerOptions;

import java.net.InetSocketAddress;
import java.time.Duration;

/**
 * @author mostroverkhov
 */
public class HttpCompressionClientServerTests {

    @Test
    public void compressionDefault() throws Exception {
        HttpServer server = HttpServer.create(0);

        NettyContext nettyContext = server.newHandler((in, out) -> out.sendString(
                Mono.just("reply"))).block(Duration.ofMillis(10_000));


        HttpClient client = HttpClient.create(o -> o
                .supportsCompression(true)
                .connect(address(nettyContext)));
        HttpClientResponse resp = client.get("/test", req ->
                req.header("Accept-Encoding", "gzip"))
                .block();

        HttpHeaders headers = resp.responseHeaders();
        Assert.assertFalse(headers.contains("Content-Encoding", "gzip", true));
        String reply = resp.receive().asString().blockFirst();
        Assert.assertEquals("reply", reply);
        nettyContext.dispose();
        nettyContext.onClose().block();

    }

    @Test
    public void compressionDisabled() throws Exception {
        HttpServer server = HttpServer.create(o -> {
            o.listen(0).compression(new HttpServerOptions.Compression.CompressionBuilder()
                    .setEnabled(false).setMinResponseSize(0).build());
        });

        NettyContext nettyContext = server.newHandler((in, out) -> out.sendString(
                Mono.just("reply"))).block(Duration.ofMillis(10_000));


        HttpClient client = HttpClient.create(o -> o
                .supportsCompression(true)
                .connect(address(nettyContext)));
        HttpClientResponse resp = client.get("/test", req ->
                req.header("Accept-Encoding", "gzip"))
                .block();

        HttpHeaders headers = resp.responseHeaders();
        Assert.assertFalse(headers.contains("Content-Encoding", "gzip", true));
        String reply = resp.receive().asString().blockFirst();
        Assert.assertEquals("reply", reply);
        nettyContext.dispose();
        nettyContext.onClose().block();
    }

    @Test
    public void compressionAlwaysEnabled() throws Exception {
        HttpServer server = HttpServer.create(o -> {
            o.listen(0).compression(new HttpServerOptions.Compression.CompressionBuilder()
                    .setEnabled(true).setMinResponseSize(0).build());
        });

        NettyContext nettyContext = server.newHandler((in, out) -> out.sendString(
                Mono.just("reply"))).block(Duration.ofMillis(10_000));


        HttpClient client = HttpClient.create(o -> o
                .supportsCompression(true)
                .connect(address(nettyContext)));
        HttpClientResponse resp = client.get("/test", req ->
                req.header("Accept-Encoding", "gzip"))
                .block();

        String reply = resp.receive().asString().blockFirst();
        Assert.assertEquals("reply", reply);
        nettyContext.dispose();
        nettyContext.onClose().block();
    }

    @Test
    public void compressionEnabledSmallResponse() throws Exception {
        HttpServer server = HttpServer.create(o -> {
            o.listen(0).compression(new HttpServerOptions.Compression.CompressionBuilder()
                    .setEnabled(true).setMinResponseSize(25).build());
        });

        NettyContext nettyContext = server.newHandler((in, out) -> out.sendString(
                Mono.just("reply"))).block(Duration.ofMillis(10_000));


        HttpClient client = HttpClient.create(o -> o
                .supportsCompression(true)
                .connect(address(nettyContext)));
        HttpClientResponse resp = client.get("/test", req ->
                req.header("Accept-Encoding", "gzip"))
                .block();

        HttpHeaders headers = resp.responseHeaders();
        Assert.assertFalse(headers.contains("Content-Encoding", "gzip", true));
        String reply = resp.receive().asString().blockFirst();
        Assert.assertEquals("reply", reply);
        nettyContext.dispose();
        nettyContext.onClose().block();
    }

    @Test
    public void compressionEnabledBigResponse() throws Exception {
        HttpServer server = HttpServer.create(o -> {
            o.listen(0).compression(new HttpServerOptions.Compression.CompressionBuilder()
                    .setEnabled(true).setMinResponseSize(4).build());
        });

        NettyContext nettyContext = server.newHandler((in, out) -> out.sendString(
                Mono.just("reply"))).block(Duration.ofMillis(10_000));


        HttpClient client = HttpClient.create(o -> o
                .supportsCompression(true)
                .connect(address(nettyContext)));
        HttpClientResponse resp = client.get("/test", req ->
                req.header("Accept-Encoding", "gzip"))
                .block();

        String reply = resp.receive().asString().blockFirst();
        Assert.assertEquals("reply", reply);
        nettyContext.dispose();
        nettyContext.onClose().block();
    }

    @Test
    public void compressionServerEnabledClientDisabled() throws Exception {
        HttpServer server = HttpServer.create(o -> {
            o.listen(0).compression(new HttpServerOptions.Compression.CompressionBuilder()
                    .setEnabled(true).build());
        });

        String serverReply = "reply";
        NettyContext nettyContext = server.newHandler((in, out) -> out.sendString(
                Mono.just(serverReply))).block(Duration.ofMillis(10_000));


        HttpClient client = HttpClient.create(o -> o
                .supportsCompression(false)
                .connect(address(nettyContext)));
        HttpClientResponse resp = client.get("/test", req ->
                req.header("Accept-Encoding", "gzip"))
                .block();
        String reply = resp.receive().asString().blockFirst();
        Assert.assertNotEquals(serverReply, reply);
        nettyContext.dispose();
        nettyContext.onClose().block();
    }

    @Test
    public void compressionServerDefaultClientDefault() throws Exception {
        HttpServer server = HttpServer.create(o -> {
            o.listen(0).compression(new HttpServerOptions.Compression.CompressionBuilder().build());
        });

        String serverReply = "reply";
        NettyContext nettyContext = server.newHandler((in, out) -> out.sendString(
                Mono.just(serverReply))).block(Duration.ofMillis(10_000));


        HttpClient client = HttpClient.create(o ->
                o.connect(address(nettyContext)));
        HttpClientResponse resp = client.get("/test", req ->
                req.header("Accept-Encoding", "gzip"))
                .block();
        String reply = resp.receive().asString().blockFirst();
        Assert.assertEquals(serverReply, reply);
        nettyContext.dispose();
        nettyContext.onClose().block();
    }

    private InetSocketAddress address(NettyContext nettyContext) {
        return new InetSocketAddress(nettyContext.address().getPort());
    }
}
