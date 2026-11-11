package com.korleaguer.news;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 주소별로 정해 둔 응답을 차례로 돌려주는 가짜 HTTP. 받은 요청(주소·헤더·시각)을 기억한다. 정해 두지 않은 주소는 404 */
class FakeHttp implements HttpGetter {

    record Request(URI uri, Map<String, String> headers, Instant at) {
    }

    private final Clock clock;
    private final Map<String, Deque<Object>> replies = new HashMap<>();
    final List<Request> requests = new ArrayList<>();

    FakeHttp(Clock clock) {
        this.clock = clock;
    }

    /** 이 주소에 차례로 돌려줄 응답(마지막 것은 계속 돌려준다) */
    FakeHttp on(String url, Reply... answers) {
        replies.computeIfAbsent(url, k -> new ArrayDeque<>()).addAll(List.of(answers));
        return this;
    }

    /** 이 주소는 연결에 실패한다 */
    FakeHttp failing(String url) {
        replies.computeIfAbsent(url, k -> new ArrayDeque<>()).add(new IOException("connection refused"));
        return this;
    }

    static Reply ok(String body) {
        return reply(200, body);
    }

    static Reply reply(int status, String body, String... headerPairs) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 0; i + 1 < headerPairs.length; i += 2) {
            headers.put(headerPairs[i].toLowerCase(), headerPairs[i + 1]);
        }
        return new Reply(status, headers, body.getBytes(StandardCharsets.UTF_8));
    }

    List<String> urls() {
        return requests.stream().map(r -> r.uri().toString()).toList();
    }

    @Override
    public Reply get(URI uri, Map<String, String> headers) throws IOException {
        requests.add(new Request(uri, Map.copyOf(headers), clock.instant()));
        Deque<Object> queue = replies.get(uri.toString());
        if (queue == null || queue.isEmpty()) {
            return reply(404, "");
        }
        Object next = queue.size() > 1 ? queue.poll() : queue.peek();
        if (next instanceof IOException e) {
            throw e;
        }
        return (Reply) next;
    }
}
