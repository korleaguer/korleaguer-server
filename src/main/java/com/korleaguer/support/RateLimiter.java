package com.korleaguer.support;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * 분당 N회 요청 제한(슬라이딩 윈도우).
 * 최근 N번의 요청 시각을 기억하고, 가장 오래된 요청에서 61초(60초 + 여유 1초)가 지나야 다음 요청을 보낸다.
 * 그래서 어느 60초 구간을 잘라 봐도 요청은 N회 이하다.
 * 여러 스레드가 동시에 부르면 synchronized 때문에 한 줄로 서서 차례를 기다린다.
 */
public class RateLimiter {

    private static final long WINDOW_NANOS = Duration.ofSeconds(61).toNanos();

    private final int limit;
    private final LongSupplier nanoTime;
    private final LongConsumer sleeper;
    private final Deque<Long> recent = new ArrayDeque<>();

    public RateLimiter(int requestsPerMinute) {
        this(requestsPerMinute, System::nanoTime, RateLimiter::sleepNanos);
    }

    /** 시계와 잠자기를 바꿔 끼우는 생성자(테스트용) */
    public RateLimiter(int requestsPerMinute, LongSupplier nanoTime, LongConsumer sleeper) {
        if (requestsPerMinute <= 0) {
            throw new IllegalArgumentException("requestsPerMinute must be positive");
        }
        this.limit = requestsPerMinute;
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
    }

    public synchronized void acquire() {
        while (true) {
            long now = nanoTime.getAsLong();
            while (!recent.isEmpty() && now - recent.peekFirst() >= WINDOW_NANOS) {
                recent.pollFirst();
            }
            if (recent.size() < limit) {
                recent.addLast(now);
                return;
            }
            sleeper.accept(recent.peekFirst() + WINDOW_NANOS - now);
        }
    }

    /** 429를 받았을 때 남은 몫을 0으로 만든다. 다음 요청은 지금부터 한 구간(61초)을 기다린다. */
    public synchronized void drain() {
        long now = nanoTime.getAsLong();
        recent.clear();
        for (int i = 0; i < limit; i++) {
            recent.addLast(now);
        }
    }

    private static void sleepNanos(long nanos) {
        try {
            Thread.sleep(Duration.ofNanos(nanos));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for rate limit", e);
        }
    }
}
