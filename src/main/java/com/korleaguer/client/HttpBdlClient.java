package com.korleaguer.client;

import com.korleaguer.support.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 실제 balldontlie API를 부르는 클라이언트.
 * - 요청마다 RateLimiter에서 차례를 기다린다.
 * - 429: 남은 몫을 비우고 Retry-After(없으면 60초, 최대 120초)만큼 기다렸다가 다시 시도한다.
 * - 5xx·네트워크 오류: 5초, 10초, 20초 기다리며 다시 시도한다.
 * - 다시 시도는 최대 3회다.
 * - 응답의 meta.next_cursor를 따라 마지막 페이지까지 받는다.
 */
public class HttpBdlClient implements BdlClient {

    private static final Logger log = LoggerFactory.getLogger(HttpBdlClient.class);

    private static final int PER_PAGE = 100;
    private static final int MAX_RETRIES = 3;
    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(60);
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(120);
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(5);

    private static final ParameterizedTypeReference<BdlPage<BdlPlayer>> PLAYER_PAGE = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<BdlData<BdlPlayer>> PLAYER_DATA = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<BdlPage<BdlGame>> GAME_PAGE = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<BdlPage<BdlStats>> STATS_PAGE = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;
    private final RateLimiter rateLimiter;
    private final Consumer<Duration> sleeper;
    private final AtomicLong requestCount = new AtomicLong();

    public HttpBdlClient(RestClient restClient, RateLimiter rateLimiter) {
        this(restClient, rateLimiter, HttpBdlClient::sleep);
    }

    // 테스트에서 실제로 잠들지 않게 잠자기를 바꿔 끼우는 생성자
    HttpBdlClient(RestClient restClient, RateLimiter rateLimiter, Consumer<Duration> sleeper) {
        this.restClient = restClient;
        this.rateLimiter = rateLimiter;
        this.sleeper = sleeper;
    }

    @Override
    public List<BdlPlayer> searchPlayers(String search) {
        return fetchAllPages("/mlb/v1/players", uri -> uri.queryParam("search", search), PLAYER_PAGE);
    }

    @Override
    public BdlPlayer getPlayer(long id) {
        try {
            BdlData<BdlPlayer> response = withRetry("/mlb/v1/players/" + id, () -> restClient.get()
                    .uri("/mlb/v1/players/{id}", id)
                    .retrieve()
                    .body(PLAYER_DATA));
            return response == null ? null : response.data();
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    @Override
    public List<BdlGame> getGames(List<Long> teamIds, List<Integer> seasons, List<LocalDate> dates) {
        return fetchAllPages("/mlb/v1/games", uri -> {
            addList(uri, "team_ids[]", teamIds);
            addList(uri, "seasons[]", seasons);
            addList(uri, "dates[]", dates);
        }, GAME_PAGE);
    }

    @Override
    public List<BdlStats> getStats(List<Long> playerIds, List<Integer> seasons, List<Long> gameIds) {
        try {
            return fetchAllPages("/mlb/v1/stats", uri -> {
                addList(uri, "player_ids[]", playerIds);
                addList(uri, "seasons[]", seasons);
                addList(uri, "game_ids[]", gameIds);
            }, STATS_PAGE);
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            throw new StatsUnavailableException("stats request rejected: " + e.getStatusCode().value(), e);
        }
    }

    @Override
    public long requestCount() {
        return requestCount.get();
    }

    /** 다음 커서가 없거나, 지금 커서와 같거나, 데이터가 비면 멈춘다. */
    private <T> List<T> fetchAllPages(String path, Consumer<UriBuilder> filters,
                                      ParameterizedTypeReference<BdlPage<T>> type) {
        List<T> all = new ArrayList<>();
        Long cursor = null;
        while (true) {
            Long current = cursor;
            BdlPage<T> page = withRetry(path, () -> restClient.get()
                    .uri(uri -> {
                        uri.path(path).queryParam("per_page", PER_PAGE);
                        filters.accept(uri);
                        if (current != null) {
                            uri.queryParam("cursor", current);
                        }
                        return uri.build();
                    })
                    .retrieve()
                    .body(type));
            if (page == null || page.data() == null || page.data().isEmpty()) {
                break;
            }
            all.addAll(page.data());
            Long next = page.nextCursor();
            if (next == null || next.equals(current)) {
                break;
            }
            cursor = next;
        }
        return all;
    }

    private <T> T withRetry(String path, Supplier<T> call) {
        for (int retry = 0; ; retry++) {
            rateLimiter.acquire();
            requestCount.incrementAndGet();
            try {
                return call.get();
            } catch (HttpClientErrorException.TooManyRequests e) {
                rateLimiter.drain();
                giveUpIfLast(retry, path, e);
                Duration wait = retryAfter(e);
                log.warn("429 from balldontlie on {}, retrying in {}s ({}/{})", path, wait.toSeconds(), retry + 1, MAX_RETRIES);
                sleeper.accept(wait);
            } catch (HttpServerErrorException | ResourceAccessException e) {
                giveUpIfLast(retry, path, e);
                Duration wait = FIRST_BACKOFF.multipliedBy(1L << retry);
                log.warn("{} on {}, retrying in {}s ({}/{})", e.getMessage(), path, wait.toSeconds(), retry + 1, MAX_RETRIES);
                sleeper.accept(wait);
            }
        }
    }

    private static void giveUpIfLast(int retry, String path, RuntimeException e) {
        if (retry >= MAX_RETRIES) {
            throw new BdlApiException("request failed after " + MAX_RETRIES + " retries: " + path, e);
        }
    }

    private static void addList(UriBuilder uri, String name, List<?> values) {
        if (values != null && !values.isEmpty()) {
            uri.queryParam(name, values);
        }
    }

    /** Retry-After 헤더(초). 없거나 이상하면 60초, 120초를 넘으면 120초로 자른다. */
    private static Duration retryAfter(HttpClientErrorException e) {
        String header = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
        Duration wait = DEFAULT_RETRY_AFTER;
        if (header != null) {
            try {
                wait = Duration.ofSeconds(Math.max(0, Long.parseLong(header.trim())));
            } catch (NumberFormatException ignored) {
                // 날짜 형식 등은 기본값으로 기다린다.
            }
        }
        return wait.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : wait;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BdlApiException("interrupted while waiting to retry", e);
        }
    }
}
