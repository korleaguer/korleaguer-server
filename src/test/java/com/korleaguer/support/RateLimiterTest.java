package com.korleaguer.support;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private static final long SECOND = Duration.ofSeconds(1).toNanos();
    private static final long MINUTE = Duration.ofMinutes(1).toNanos();

    // 가짜 시계: 잠든 만큼만 시간이 흐른다.
    private final long[] now = {0};
    private final RateLimiter limiter = new RateLimiter(5, () -> now[0], nanos -> now[0] += nanos);

    @Test
    void neverMoreThanLimitInAnySixtySeconds() {
        List<Long> sentAt = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            limiter.acquire();
            sentAt.add(now[0]);
            now[0] += SECOND; // 요청 하나 처리하는 데 1초
        }

        for (long start : sentAt) {
            long inWindow = sentAt.stream().filter(t -> t >= start && t < start + MINUTE).count();
            assertThat(inWindow).as("requests in 60s from %d", start).isLessThanOrEqualTo(5);
        }
    }

    @Test
    void drainMakesNextRequestWaitAFullMinute() {
        limiter.acquire();
        long before = now[0];

        limiter.drain();
        limiter.acquire();

        assertThat(now[0] - before).isGreaterThanOrEqualTo(MINUTE);
    }

    @Test
    void oneRequestPerSecondWindowSpacesRequests() {
        RateLimiter perSecond = new RateLimiter(1, Duration.ofSeconds(1), () -> now[0], nanos -> now[0] += nanos);
        List<Long> sentAt = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            perSecond.acquire();
            sentAt.add(now[0]);
        }

        assertThat(sentAt).containsExactly(0L, SECOND, 2 * SECOND);
    }
}
