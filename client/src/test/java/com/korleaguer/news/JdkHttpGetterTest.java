package com.korleaguer.news;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 HTTP로 받는다(이 컴퓨터 안에 띄운 JDK 내장 서버). gzip으로 온 본문만 풀고, 그냥 온 본문은 그대로 둔다 */
class JdkHttpGetterTest {

    private static final String FEED = "<rss><channel><title>뉴스</title></channel></rss>";

    private HttpServer server;
    private final JdkHttpGetter getter = new JdkHttpGetter("korleaguer-test", Duration.ofSeconds(2),
            Duration.ofSeconds(2), 1_000_000);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/plain", exchange -> {
            byte[] body = FEED.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/gzip", exchange -> {
            byte[] body = gzip(FEED.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/not-modified", exchange -> {
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("압축하지 않고 보낸 피드는 풀지 않고 그대로 읽는다")
    void plainBodyIsReadAsIs() throws IOException {
        // when
        HttpGetter.Reply reply = getter.get(uri("/plain"), Map.of());

        // then
        assertThat(reply.status()).isEqualTo(200);
        assertThat(new String(reply.body(), StandardCharsets.UTF_8)).isEqualTo(FEED);
    }

    @Test
    @DisplayName("gzip으로 보낸 피드는 풀어서 읽는다")
    void gzipBodyIsUnzipped() throws IOException {
        // when
        HttpGetter.Reply reply = getter.get(uri("/gzip"), Map.of());

        // then
        assertThat(reply.status()).isEqualTo(200);
        assertThat(new String(reply.body(), StandardCharsets.UTF_8)).isEqualTo(FEED);
    }

    @Test
    @DisplayName("본문 없는 304 응답은 gzip 표시가 붙어 와도 빈 본문으로 읽는다")
    void emptyNotModifiedReplyWithGzipHeaderIsEmpty() throws IOException {
        // when
        HttpGetter.Reply reply = getter.get(uri("/not-modified"), Map.of());

        // then
        assertThat(reply.status()).isEqualTo(304);
        assertThat(reply.body()).isEmpty();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private static byte[] gzip(byte[] plain) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(plain);
        }
        return bytes.toByteArray();
    }
}
