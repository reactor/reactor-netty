# QUIC functionality for the Reactor Netty library

This module contains integration with [Netty's QUIC Codec](https://github.com/netty/netty/tree/4.2/codec-native-quic).

## Getting it
With `Gradle` from [repo.spring.io](https://repo.spring.io) or `Maven Central` repositories (stable releases only):

```groovy
    repositories {
      //maven { url 'https://repo.spring.io/snapshot' }
      mavenCentral()
    }

    dependencies {
      //compile "io.projectreactor.netty:reactor-netty-quic:1.0.0-SNAPSHOT"
      compile "io.projectreactor.netty:reactor-netty-quic:1.0.0-SNAPSHOT"
    }
```

## Getting Started
Here is a very simple `QUIC` server and the corresponding `QUIC` client example

```java
public class ServerApplication {

	public static void main(String[] args) throws Exception {
		X509Bundle cert =
				new CertificateBuilder().subject("CN=localhost").setIsCertificateAuthority(true).buildSelfSigned();
		QuicSslContext serverCtx =
				QuicSslContextBuilder.forServer(ssc.toTempPrivateKeyPem(), null, ssc.toTempCertChainPem())
				                     .applicationProtocols("http/1.1")
				                     .build();

		Connection server =
				QuicServer.create()
				          .host("127.0.0.1")
				          .port(8080)
				          .secure(serverCtx)
				          .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
				          .wiretap(true)
				          .idleTimeout(Duration.ofSeconds(5))
				          .initialSettings(spec ->
				              spec.maxData(10000000)
				                  .maxStreamDataBidirectionalRemote(1000000)
				                  .maxStreamsBidirectional(100))
				          .handleStream((in, out) -> out.send(in.receive().retain()))
				          .bindNow();

		server.onDispose()
		      .block();
	}
}
```

```java
public class ClientApplication {

	public static void main(String[] args) throws Exception {
		QuicSslContext clientCtx =
				QuicSslContextBuilder.forClient()
				                     .trustManager(InsecureTrustManagerFactory.INSTANCE)
				                     .applicationProtocols("http/1.1")
				                     .build();

		QuicConnection client =
				QuicClient.create()
				          .bindAddress(() -> new InetSocketAddress(0))
				          .remoteAddress(() -> new InetSocketAddress("127.0.0.1", 8080))
				          .secure(clientCtx)
				          .wiretap(true)
				          .idleTimeout(Duration.ofSeconds(5))
				          .initialSettings(spec ->
				              spec.maxData(10000000)
				                  .maxStreamDataBidirectionalLocal(1000000))
				          .connectNow();

		CountDownLatch latch = new CountDownLatch(1);
		client.createStream((in, out) -> out.sendString(Mono.just("Hello World!"))
		                                    .then(in.receive()
		                                            .asString()
		                                            .doOnNext(s -> {
		                                                System.out.println("CLIENT RECEIVED: " + s);
		                                                latch.countDown();
		                                            })
		                                            .then()))
		      .subscribe();

		latch.await();
	}
}
```
