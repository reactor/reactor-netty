package reactor.netty;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;

public class UrlExceptionTest {

    @Test
    public void urlExceptionTest() {
        try {
            HttpClient.create()
                    .doOnError((httpClientRequest, throwable) -> {

                    }, (httpClientResponse, throwable) -> {

                    })
                    .request(HttpMethod.GET)
                    .uri(Mono.error(new IllegalArgumentException("URL cannot be resolved")))
                    .responseSingle((httpClientResponse, byteBufMono) -> {
                        HttpResponseStatus status = httpClientResponse.status();
                        if (status.code() != 200) {
                            return Mono.error(new IllegalStateException(status.toString()));
                        }
                        return byteBufMono.asString();
                    })
                    .block();

        }catch (IllegalArgumentException e){
            assertThat(e.getMessage()).isEqualTo("URL cannot be resolved");
        }
    }
}
