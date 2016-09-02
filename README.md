# reactor-ipc

[![Join the chat at https://gitter.im/reactor/reactor](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/reactor/reactor?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

[![Reactor Netty](https://maven-badges.herokuapp.com/maven-central/io.projectreactor/reactor-core/badge.svg?style=plastic)](http://mvnrepository.com/artifact/io.projectreactor.ipc/reactor-netty) [ ![Download](https://api.bintray.com/packages/spring/jars/io.projectreactor/images/download.svg) ](https://bintray.com/spring/jars/io.projectreactor.ipc/_latestVersion)

Backpressure-ready components to encode, decode, send (unicast, multicast or request/response) and serve connections :
- [reactor-aeron](#reactor-aeron) : Efficient Unicast/Multicast reactive-streams
transport for Aeron
- reactor-netty   : Client/Server interactions for UDP/TCP/HTTP
- reactor-codec : Reactive-Streams decoders/encoders (Codec) including compression,
serialization and such.

## reactor-aeron

An implementation of Reactive Streams over Aeron supporting both unicast and multicast modes of data sending.

### Getting it
- Snapshot : **0.6.0.BUILD-SNAPSHOT**  ( Java 8+ required )
- Milestone : **TBA**  ( Java 8+ required )

With Gradle from repo.spring.io or Maven Central repositories (stable releases only):
```groovy
    repositories {
      maven { url 'http://repo.spring.io/snapshot' }
      //maven { url 'http://repo.spring.io/milestone' }
      mavenCentral()
    }

    dependencies {
      compile "io.projectreactor.ipc:reactor-aeron:0.6.0.BUILD-SNAPSHOT"
    }
```

### AeronSubscriber + AeronFlux
A combination of AeronSubscriber playing a role of signals sender and AeronFlux playing a role of signals receiver allows transporting data from a sender to a receiver over Aeron in both unicast and multicast modes.

AeronSubscriber awaiting for connections from AeronFlux:
```java
AeronSubscriber subscriber = AeronSubscriber.create(Context.create()
    .senderChannel("udp://serverbox:12000"));
    
Flux.range(1, 10).map(i -> Buffer.wrap("" + i)).subscribe(subscriber); // sending 1, 2, ..., 10 via Aeron
```

AeronFlux connecting to AeronSubscruber above:
```java
Flux<Buffer> receiver = AeronFlux.listenOn(Context.create()
    .senderChannel("udp://serverbox:12000")     // sender channel specified for AeronSubscriber 
	.receiverChannel("udp://clientbox:12001"));

receiver.subscribe(System.out::println); // output: 1, 2, ..., 10
```

### AeronProcessor
A Reactive Streams Processor which plays roles of both signal sender and signal receiver locally and also allows remote instances of AeronFlux to connect to it via Aeron and receive signals.

A processor sending signals via Aeron:
```java
AeronProcessor processor = AeronProcessor.create(Context.create()
		.senderChannel("udp://serverbox:12000"));

Flux.range(1, 1000000).map(i -> Buffer.wrap("" + i)).subscribe(processor);

processor.subscribe(System.out::println);
```

A receiver connecting to the processor above and receiving signals:
```java
Flux<Buffer> receiver = AeronFlux.listenOn(Context.create()
		.senderChannel("udp://serverbox:12000")
		.receiverChannel("udp://clientbox:12001"));

receiver.subscribe(System.out::println);
```

## Reference
http://projectreactor.io/ipc/docs/reference/

## Javadoc
http://projectreactor.io/ipc/docs/api/

_Licensed under [Apache Software License 2.0](www.apache.org/licenses/LICENSE-2.0)_

_Sponsored by [Pivotal](http://pivotal.io)_
