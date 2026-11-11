package com.korleaguer.news;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 남의 사이트에서 피드·사진을 예의 있게 받는다.
 * <ul>
 *   <li>사이트(scheme+host)마다 robots.txt를 하루 한 번 받아 지킨다. 확인할 이름(우리 이름·Claude 계열) 중
 *       하나라도 막혀 있으면 받지 않는다. robots.txt가 없으면(404 등) 다 허용, 서버 오류·연결 실패면 다 막힘으로 보고
 *       나중에 다시 묻는다(RFC 9309). robots.txt가 401·403이면 기본은 그 사이트를 하루 쉰다
 *       (pauseOnRobotsForbidden=false면 표준대로 '없음'으로 보고 받는다. 사람이 고른 사진 주소 하나를 받을 때).
 *       robots.txt가 429면 쉰다.</li>
 *   <li>같은 사이트 요청 사이를 1초 이상(crawl-delay가 더 길면 그만큼) 띄운다.</li>
 *   <li>피드(fetch): 지난번 응답의 ETag·Last-Modified로 조건부 요청을 보낸다(바뀐 게 없으면 304로 끝).
 *       사진(get): 조건부 헤더를 부르는 쪽이 넣는다(저장해 둔 ETag 등).</li>
 *   <li>리다이렉트는 3번까지 따라가되, 갈 때마다 그 주소의 robots.txt를 다시 본다.</li>
 *   <li>실패하면 그 사이트를 쉰다: 429·503은 Retry-After(없으면 아래처럼), 401·403·404·410·451은 하루,
 *       그 밖의 실패는 30분부터 실패할 때마다 두 배(최대 하루).</li>
 * </ul>
 * 상태(robots.txt·쉬는 시각·피드 ETag)는 메모리에만 둔다. 서버가 다시 뜨면 처음부터 다시 묻는다.
 */
public class PoliteFetcher {

    private static final Logger log = LoggerFactory.getLogger(PoliteFetcher.class);

    static final Duration MIN_INTERVAL = Duration.ofSeconds(1);
    static final Duration ROBOTS_TTL = Duration.ofHours(24);
    static final Duration FIRST_BACKOFF = Duration.ofMinutes(30);
    static final Duration MAX_PAUSE = Duration.ofHours(24);
    static final int MAX_REDIRECTS = 3;

    /** 기다리기(테스트에서는 시계를 앞으로 돌린다) */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration);
    }

    /**
     * 받은 결과. modified가 false면 304(바뀐 것 없음)라 body가 비어 있다.
     *
     * @param contentType Content-Type 헤더(없으면 null)
     * @param etag        ETag 헤더(없으면 null)
     * @param lastModified Last-Modified 헤더(없으면 null)
     */
    public record Fetched(boolean modified, byte[] body, String contentType, String etag, String lastModified) {
    }

    private final HttpGetter http;
    private final List<String> agents;
    private final Clock clock;
    private final Sleeper sleeper;
    private final boolean pauseOnRobotsForbidden;
    private final Map<String, Site> sites = new HashMap<>();
    private final Map<String, Validators> validators = new HashMap<>();

    /**
     * @param agents robots.txt에서 확인할 이름. 맨 앞이 우리 이름이다(비면 안 된다)
     */
    // getPage가 읽는 마지막 응답 코드(메서드가 모두 synchronized라 한 호출 안에서만 쓴다)
    private int lastStatus;

    public PoliteFetcher(HttpGetter http, List<String> agents, Clock clock, Sleeper sleeper) {
        this(http, agents, clock, sleeper, true);
    }

    /**
     * @param pauseOnRobotsForbidden robots.txt가 401·403이면 그 사이트를 쉴지(true). false면 표준대로 'robots.txt 없음'으로 본다
     */
    public PoliteFetcher(HttpGetter http, List<String> agents, Clock clock, Sleeper sleeper,
                         boolean pauseOnRobotsForbidden) {
        if (agents.isEmpty()) {
            throw new IllegalArgumentException("robots agents must not be empty");
        }
        this.http = http;
        this.agents = List.copyOf(agents);
        this.clock = clock;
        this.sleeper = sleeper;
        this.pauseOnRobotsForbidden = pauseOnRobotsForbidden;
    }

    /** 피드용: 새 내용이면 본문. 바뀐 게 없거나(304) robots.txt가 막았거나 실패했거나 쉬는 중이면 빈 값 */
    public synchronized Optional<byte[]> fetch(URI uri) {
        Optional<Fetched> fetched = get(uri, conditionalHeaders(uri), 0, true);
        if (fetched.isEmpty() || !fetched.get().modified()) {
            return Optional.empty();
        }
        remember(uri, fetched.get());
        return Optional.of(fetched.get().body());
    }

    /**
     * 한 번 받는다. headers(Accept, If-None-Match 등)를 그대로 붙인다.
     * 200이면 modified=true와 본문, 304면 modified=false. robots.txt가 막았거나 실패했거나 쉬는 중이면 빈 값
     */
    public synchronized Optional<Fetched> get(URI uri, Map<String, String> headers) {
        return get(uri, headers, 0, true);
    }

    /**
     * 기사 페이지용: 조건 없이 한 번 받는다(304로 본문을 놓치지 않게). 404·410은 그 기사만 없어진 것이라 사이트를 쉬게
     * 하지 않는다(피드 주소가 404면 하루 쉬는 것과 다르다). robots.txt·간격·429·403 등 나머지 규칙은 같다.
     */
    public synchronized Page getPage(URI uri) {
        lastStatus = 0;
        Optional<Fetched> fetched = get(uri, Map.of(), 0, false);
        if (fetched.isPresent() && fetched.get().modified()) {
            return new Page(200, fetched.get().body());
        }
        return new Page(lastStatus, null);
    }

    /**
     * 기사 페이지 한 번 받은 결과.
     *
     * @param status 마지막 응답 코드. 요청하지 않았으면(robots.txt가 막음, 쉬는 중, 연결 실패) 0
     * @param body   200이면 본문, 아니면 null
     */
    public record Page(int status, byte[] body) {

        public boolean ok() {
            return body != null;
        }
    }

    /** 이 주소의 사이트가 지금 쉬는 중인지(테스트·로그용) */
    public synchronized boolean paused(URI uri) {
        Site site = sites.get(siteKey(uri));
        return site != null && site.pausedUntil != null && clock.instant().isBefore(site.pausedUntil);
    }

    private Optional<Fetched> get(URI uri, Map<String, String> headers, int hops, boolean missingPausesSite) {
        if (!isHttp(uri)) {
            log.warn("fetch: not an http(s) url, skipped: {}", uri);
            return Optional.empty();
        }
        Site site = site(uri);
        if (site.pausedUntil != null && clock.instant().isBefore(site.pausedUntil)) {
            log.debug("fetch: {} is paused until {}", site.key, site.pausedUntil);
            return Optional.empty();
        }
        RobotsTxt robots = robots(site);
        if (robots == null) {
            return Optional.empty();
        }
        String path = pathOf(uri);
        for (String agent : agents) {
            if (!robots.allows(agent, path)) {
                log.info("fetch: robots.txt of {} disallows {} for {}", site.key, path, agent);
                return Optional.empty();
            }
        }
        HttpGetter.Reply reply = request(site, uri, headers);
        if (reply == null) {
            return Optional.empty();
        }
        int status = reply.status();
        lastStatus = status;
        if (status == 200) {
            site.failures = 0;
            return Optional.of(new Fetched(true, reply.body(), reply.header("content-type"), reply.header("etag"),
                    reply.header("last-modified")));
        }
        if (status == 304) {
            site.failures = 0;
            return Optional.of(new Fetched(false, new byte[0], null, reply.header("etag"),
                    reply.header("last-modified")));
        }
        if (isRedirect(status)) {
            String location = reply.header("location");
            if (location == null || location.isBlank() || hops >= MAX_REDIRECTS) {
                log.warn("fetch: {} answered {} without a usable redirect", uri, status);
                return Optional.empty();
            }
            URI next = uri.resolve(location.trim());
            log.info("fetch: {} redirects to {} (update the url if this stays)", uri, next);
            return get(next, headers, hops + 1, missingPausesSite);
        }
        if (status == 429 || status == 503) {
            pause(site, retryAfter(reply), "answered " + status);
            return Optional.empty();
        }
        if ((status == 404 || status == 410) && !missingPausesSite) {
            log.info("fetch: {} answered {} (page gone)", uri, status);
            return Optional.empty();
        }
        if (status == 401 || status == 403 || status == 404 || status == 410 || status == 451) {
            pause(site, MAX_PAUSE, "answered " + status + " for " + path);
            return Optional.empty();
        }
        backoff(site, "answered " + status);
        return Optional.empty();
    }

    /** 하루 안에 받은 robots.txt가 있으면 그것, 없으면 받는다. 받을 수 없어 판단을 미뤄야 하면 null(그 사이트는 쉰다) */
    private RobotsTxt robots(Site site) {
        Instant now = clock.instant();
        if (site.robots != null && now.isBefore(site.robotsFetchedAt.plus(ROBOTS_TTL))) {
            return site.robots;
        }
        RobotsTxt robots = fetchRobots(site, URI.create(site.key + "/robots.txt"), 0);
        if (robots == null) {
            return null;
        }
        site.robots = robots;
        site.robotsFetchedAt = now;
        site.crawlDelay = agents.stream()
                .map(robots::crawlDelay)
                .filter(d -> d != null)
                .max(Duration::compareTo)
                .orElse(null);
        return robots;
    }

    private RobotsTxt fetchRobots(Site site, URI robotsUri, int hops) {
        HttpGetter.Reply reply = request(site, robotsUri, Map.of("Accept", "text/plain, */*;q=0.1"));
        if (reply == null) {
            return null;
        }
        int status = reply.status();
        if (status >= 200 && status < 300) {
            return RobotsTxt.parse(new String(reply.body(), StandardCharsets.UTF_8));
        }
        if (isRedirect(status) && reply.header("location") != null && hops < 5) {
            return fetchRobots(site, robotsUri.resolve(reply.header("location").trim()), hops + 1);
        }
        if ((status == 401 || status == 403) && pauseOnRobotsForbidden) {
            // 표준은 4xx를 '없음(다 허용)'으로 보지만, 우리를 막은 것일 수 있어 하루 쉰다.
            pause(site, MAX_PAUSE, "robots.txt answered " + status);
            return null;
        }
        if (status == 429) {
            pause(site, retryAfter(reply), "robots.txt answered 429");
            return null;
        }
        if (status >= 400 && status < 500) {
            return RobotsTxt.ALLOW_ALL;
        }
        backoff(site, "robots.txt answered " + status);
        return null;
    }

    private HttpGetter.Reply request(Site site, URI uri, Map<String, String> headers) {
        pace(site);
        try {
            return http.get(uri, headers);
        } catch (IOException e) {
            backoff(site, "request failed: " + e.getMessage());
            return null;
        } finally {
            site.lastRequest = clock.instant();
        }
    }

    private void pace(Site site) {
        if (site.lastRequest == null) {
            return;
        }
        Duration interval = site.crawlDelay != null && site.crawlDelay.compareTo(MIN_INTERVAL) > 0
                ? site.crawlDelay : MIN_INTERVAL;
        Duration wait = Duration.between(clock.instant(), site.lastRequest.plus(interval));
        if (wait.isPositive()) {
            sleeper.sleep(wait);
        }
    }

    private void backoff(Site site, String reason) {
        site.failures++;
        long factor = 1L << Math.min(site.failures - 1, 10);
        Duration pause = FIRST_BACKOFF.multipliedBy(factor);
        pause(site, pause.compareTo(MAX_PAUSE) > 0 ? MAX_PAUSE : pause, reason);
    }

    private void pause(Site site, Duration duration, String reason) {
        site.pausedUntil = clock.instant().plus(duration);
        log.warn("fetch: pausing {} until {} ({})", site.key, site.pausedUntil, reason);
    }

    /** Retry-After(초 또는 HTTP 날짜). 없거나 읽을 수 없으면 실패 횟수에 따른 쉬는 시간. 1분~하루로 묶는다. */
    private Duration retryAfter(HttpGetter.Reply reply) {
        String value = reply.header("retry-after");
        Duration wait = null;
        if (value != null) {
            try {
                wait = Duration.ofSeconds(Long.parseLong(value.trim()));
            } catch (NumberFormatException e) {
                try {
                    wait = Duration.between(clock.instant(),
                            ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                } catch (DateTimeParseException ignored) {
                    // 아래 기본값
                }
            }
        }
        if (wait == null) {
            return FIRST_BACKOFF;
        }
        if (wait.compareTo(Duration.ofMinutes(1)) < 0) {
            return Duration.ofMinutes(1);
        }
        return wait.compareTo(MAX_PAUSE) > 0 ? MAX_PAUSE : wait;
    }

    private Map<String, String> conditionalHeaders(URI uri) {
        Validators v = validators.get(uri.toString());
        Map<String, String> headers = new LinkedHashMap<>();
        if (v != null && v.etag != null) {
            headers.put("If-None-Match", v.etag);
        }
        if (v != null && v.lastModified != null) {
            headers.put("If-Modified-Since", v.lastModified);
        }
        return headers;
    }

    private void remember(URI uri, Fetched fetched) {
        if (fetched.etag() == null && fetched.lastModified() == null) {
            validators.remove(uri.toString());
        } else {
            validators.put(uri.toString(), new Validators(fetched.etag(), fetched.lastModified()));
        }
    }

    private Site site(URI uri) {
        return sites.computeIfAbsent(siteKey(uri), Site::new);
    }

    private static String siteKey(URI uri) {
        return (uri.getScheme() + "://" + uri.getRawAuthority()).toLowerCase(Locale.ROOT);
    }

    private static String pathOf(URI uri) {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    private static boolean isHttp(URI uri) {
        String scheme = uri.getScheme();
        return uri.getRawAuthority() != null
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private record Validators(String etag, String lastModified) {
    }

    private static final class Site {
        final String key;
        RobotsTxt robots;
        Instant robotsFetchedAt;
        Duration crawlDelay;
        Instant lastRequest;
        Instant pausedUntil;
        int failures;

        Site(String key) {
            this.key = key;
        }
    }
}
