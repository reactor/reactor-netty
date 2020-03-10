# Reactor Netty

[![Join the chat at https://gitter.im/reactor/reactor-netty](https://img.shields.io/gitter/room/nwjs/nw.js.svg)](https://gitter.im/reactor/reactor-netty)

[![Reactor Netty](https://img.shields.io/maven-central/v/io.projectreactor.netty/reactor-netty.svg?colorB=brightgreen)](https://mvnrepository.com/artifact/io.projectreactor.netty/reactor-netty) [ ![Download](https://api.bintray.com/packages/spring/jars/io.projectreactor.netty/images/download.svg) ](https://bintray.com/spring/jars/io.projectreactor.netty/_latestVersion)

`Reactor Netty` offers non-blocking and backpressure-ready `TCP`/`HTTP`/`UDP`
clients & servers based on `Netty` framework.

## Getting it
`Reactor Netty` requires Java 8 or + to run.

With `Gradle` from [repo.spring.io](https://repo.spring.io) or `Maven Central` repositories (stable releases only):

```groovy
    repositories {
      //maven { url 'https://repo.spring.io/snapshot' }
      maven { url 'https://repo.spring.io/release' }
      mavenCentral()
    }

    dependencies {
      //compile "io.projectreactor.netty:reactor-netty:1.0.0.BUILD-SNAPSHOT"
      compile "io.projectreactor.netty:reactor-netty:0.9.5.RELEASE"
    }
```

See the [Reference documentation](https://projectreactor.io/docs/netty/release/reference/index.html#getting)
for more information on getting it (eg. using `Maven`, or on how to get milestones and snapshots).


## Getting Started
New to `Reactor Netty`? Check this [Reactor Netty Workshop](https://violetagg.github.io/reactor-netty-workshop/)
and the [Reference documentation](https://projectreactor.io/docs/netty/release/reference/index.html)

Here is a very simple `HTTP` server and the corresponding `HTTP` client example

```java
HttpServer.create()   // Prepares an HTTP server ready for configuration
          .port(0)    // Configures the port number as zero, this will let the system pick up
                      // an ephemeral port when binding the server
          .route(routes ->
                      // The server will respond only on POST requests
                      // where the path starts with /test and then there is path parameter
                  routes.post("/test/{param}", (request, response) ->
                          response.sendString(request.receive()
                                                     .asString()
                                                     .map(s -> s + ' ' + request.param("param") + '!')
                                                     .log("http-server"))))
          .bindNow(); // Starts the server in a blocking fashion, and waits for it to finish its initialization
```

```java
HttpClient.create()             // Prepares an HTTP client ready for configuration
          .port(server.port())  // Obtains the server's port and provides it as a port to which this
                                // client should connect
          .post()               // Specifies that POST method will be used
          .uri("/test/World")   // Specifies the path
          .send(ByteBufFlux.fromString(Flux.just("Hello")))  // Sends the request body
          .responseContent()    // Receives the response body
          .aggregate()
          .asString()
          .log("http-client")
          .block();

```

## Getting help
Having trouble with `Reactor Netty`? We'd like to help!
* If you are upgrading, read the [release notes](https://github.com/reactor/reactor-netty/releases)
  for upgrade instructions and *new and noteworthy* features.
* Ask a question - we monitor [stackoverflow.com](https://stackoverflow.com) for questions
  tagged with [`reactor-netty`](https://stackoverflow.com/questions/tagged/reactor-netty). You can also chat
  with the community on [Gitter](https://gitter.im/reactor/reactor-netty).
* Report bugs with `Reactor Netty` at [github.com/reactor/reactor-netty/issues](https://github.com/reactor/reactor-netty/issues).

## Reporting Issues
`Reactor Netty` uses `GitHub’s` integrated issue tracking system to record bugs and feature requests.
If you want to raise an issue, please follow the recommendations below:
* Before you log a bug, please [search the issue tracker](https://github.com/reactor/reactor-netty/search?type=Issues)
  to see if someone has already reported the problem.
* If the issue doesn't already exist, [create a new issue](https://github.com/reactor/reactor-netty/issues/new/choose).
* Please provide as much information as possible with the issue report, we like to know
  the version of `Reactor Netty` that you are using, as well as your `Operating System` and
  `JVM` version.
* If you want to raise a security vulnerability, please review our [Security Policy](https://github.com/reactor/reactor-netty/security/policy) for more details.

## Building from Source
You don't need to build from source to use `Reactor Netty` (binaries in
[repo.spring.io](https://repo.spring.io)), but if you want to try out the latest and
greatest, `Reactor Netty` can be easily built with the
[gradle wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html). You also need JDK 1.8.

```shell
$ git clone https://github.com/reactor/reactor-netty.git
$ cd reactor-netty
$ ./gradlew build
```

If you want to publish the artifacts to your local `Maven` repository use:

```shell
$ ./gradlew publishToMavenLocal
```

## Javadoc
https://projectreactor.io/docs/netty/release/api/

## Guides

* https://projectreactor.io/docs/netty/release/reference/index.html
* https://violetagg.github.io/reactor-netty-workshop/

## License
Reactor Netty is Open Source Software released under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
