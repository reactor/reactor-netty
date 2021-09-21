package reactor.netty;

import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Flux;
import reactor.netty.tcp.TcpServer;

public class TcpEmissionTest {

  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  public void testBackpressure() throws InterruptedException, IOException {
    byte[] array = new byte[32];
    Random random = new Random();
    final int emissionCount = 130;

    DisposableServer server = TcpServer.create()
        .handle((inbound, outbound) -> {
          return outbound.send(Flux.fromStream(
              IntStream.range(0, emissionCount)
                  .mapToObj(i -> {
                    random.nextBytes(array);
                    return Unpooled.copiedBuffer(array);
                  }
                  )
          ) // uncomment the line below to make it work. WHY does this work?
              //.doOnError(th -> th.printStackTrace())
              ,
               b -> true);
        })
        .host("localhost")
        .port(8080)
        .bindNow();

    // a slow reader
    AtomicLong cnt = new AtomicLong();
    try (Socket socket = new Socket("localhost", 8080);
        InputStream is = socket.getInputStream()) {
      byte[] buffer = new byte[32];
      while (true) {
        System.out.println("slow reading... " + cnt);
        is.readNBytes(buffer, 0, 4);
        //Thread.sleep(100);
        if (cnt.incrementAndGet() >= emissionCount * 8) {
          break;
        }
      }
      System.out.println("cnt = " + cnt.get());
    } catch (Exception e) {
      e.printStackTrace();
    }

    server.dispose();
    System.out.println("SERVER DISPOSE CALLED");
    server.onDispose().block();
  }

}
