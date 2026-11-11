package com.korleaguer.news;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * JDK HttpClient로 GET. 정직한 User-Agent를 붙이고, 리다이렉트는 따라가지 않으며(robots.txt를 다시 보려고),
 * gzip으로 받아(주고받는 양을 줄이려고) 풀고, 푼 본문 크기를 묶는다.
 */
public class JdkHttpGetter implements HttpGetter {

    private final HttpClient client;
    private final String userAgent;
    private final Duration readTimeout;
    private final int maxBytes;

    public JdkHttpGetter(String userAgent, Duration connectTimeout, Duration readTimeout, int maxBytes) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.userAgent = userAgent;
        this.readTimeout = readTimeout;
        this.maxBytes = maxBytes;
    }

    @Override
    public Reply get(URI uri, Map<String, String> headers) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(readTimeout)
                .header("User-Agent", userAgent)
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, "
                        + "text/plain;q=0.5, */*;q=0.1")
                .header("Accept-Encoding", "gzip");
        headers.forEach(request::header);
        try {
            HttpResponse<InputStream> response = client.send(request.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            boolean gzip = response.headers().firstValue("Content-Encoding")
                    .map(v -> v.trim().equalsIgnoreCase("gzip")).orElse(false);
            byte[] raw;
            try (InputStream in = response.body()) {
                raw = in.readNBytes(maxBytes);
            }
            // 304처럼 본문이 없는 응답에도 Content-Encoding이 붙어 올 수 있어 빈 본문은 풀지 않는다.
            byte[] body = gzip && raw.length > 0 ? gunzip(raw) : raw;
            Map<String, String> replyHeaders = new LinkedHashMap<>();
            response.headers().map().forEach((name, values) -> {
                if (!values.isEmpty()) {
                    replyHeaders.put(name.toLowerCase(Locale.ROOT), values.getFirst());
                }
            });
            return new Reply(response.statusCode(), replyHeaders, body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private byte[] gunzip(byte[] compressed) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return in.readNBytes(maxBytes);
        }
    }
}
