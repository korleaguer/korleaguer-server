package com.korleaguer.news;

import com.korleaguer.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.korleaguer.news.FakeHttp.ok;
import static com.korleaguer.news.FakeHttp.reply;
import static org.assertj.core.api.Assertions.assertThat;

/** 예의 지키기: robots.txt(우리 이름·Claude 계열), 사이트별 간격, 조건부 요청, 실패하면 쉬기 */
class PoliteFetcherTest {

    private static final String SITE = "https://news.example.kr";
    private static final String ROBOTS = SITE + "/robots.txt";
    private static final String FEED = SITE + "/RSS/sports.xml";
    private static final List<String> AGENTS = List.of("korleaguer", "ClaudeBot", "Claude-User", "anthropic-ai");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-11T01:00:00Z"));
    private final FakeHttp http = new FakeHttp(clock);
    private final PoliteFetcher fetcher = new PoliteFetcher(http, AGENTS, clock, clock::advance);

    @Test
    void aGoneArticlePageDoesNotPauseTheSiteButAGoneFeedDoes() {
        String page = SITE + "/view/1";
        http.on(ROBOTS, ok("User-agent: *\nAllow: /\n")).on(page, reply(404, "")).on(FEED, reply(410, ""));

        assertThat(fetcher.getPage(URI.create(page))).isEqualTo(new PoliteFetcher.Page(404, null));
        assertThat(fetcher.paused(URI.create(FEED))).isFalse();

        assertThat(fetch(FEED)).isEmpty();
        assertThat(fetcher.paused(URI.create(FEED))).isTrue();
    }

    @Test
    void feedDisallowedByRobotsIsNeverRequested() {
        http.on(ROBOTS, ok("User-agent: *\nDisallow: /RSS/\n")).on(FEED, ok("<rss/>"));

        assertThat(fetch(FEED)).isEmpty();
        assertThat(http.urls()).containsExactly(ROBOTS);
    }

    @Test
    void claudeFamilyBlockCountsEvenWhenStarAllows() {
        http.on(ROBOTS, ok("User-agent: *\nAllow: /\n\nUser-agent: ClaudeBot\nDisallow: /\n")).on(FEED, ok("<rss/>"));

        assertThat(fetch(FEED)).isEmpty();
        assertThat(http.urls()).containsExactly(ROBOTS);
    }

    @Test
    void missingRobotsAllowsEverything() {
        http.on(ROBOTS, reply(404, "")).on(FEED, ok("<rss/>"));

        assertThat(fetch(FEED)).map(PoliteFetcherTest::text).contains("<rss/>");
        assertThat(http.urls()).containsExactly(ROBOTS, FEED);
    }

    @Test
    void robotsServerErrorMeansDoNotFetchAndPauseTheSite() {
        http.on(ROBOTS, reply(500, "")).on(FEED, ok("<rss/>"));

        assertThat(fetch(FEED)).isEmpty();
        assertThat(fetcher.paused(URI.create(FEED))).isTrue();
        clock.advance(Duration.ofMinutes(10));
        assertThat(fetch(FEED)).isEmpty();
        assertThat(http.urls()).containsExactly(ROBOTS);
    }

    @Test
    void robotsForbiddenPausesTheSiteForADay() {
        http.on(ROBOTS, reply(403, "")).on(FEED, ok("<rss/>"));

        assertThat(fetch(FEED)).isEmpty();
        clock.advance(Duration.ofHours(23));
        assertThat(fetcher.paused(URI.create(FEED))).isTrue();
        assertThat(http.urls()).containsExactly(ROBOTS);
    }

    @Test
    void robotsIsReusedForADay() {
        http.on(ROBOTS, ok("User-agent: *\nAllow: /\n")).on(FEED, ok("<rss/>"));

        fetch(FEED);
        clock.advance(Duration.ofMinutes(30));
        fetch(FEED);
        clock.advance(Duration.ofHours(25));
        fetch(FEED);

        assertThat(http.urls()).containsExactly(ROBOTS, FEED, FEED, ROBOTS, FEED);
    }

    @Test
    void waitsAtLeastOneSecondBetweenRequestsToTheSameSite() {
        http.on(ROBOTS, ok("")).on(FEED, ok("<rss/>"));

        fetch(FEED);

        assertThat(Duration.between(http.requests.get(0).at(), http.requests.get(1).at()))
                .isGreaterThanOrEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void longerCrawlDelayIsRespected() {
        http.on(ROBOTS, ok("User-agent: *\nCrawl-delay: 5\n")).on(FEED, ok("<rss/>"));

        fetch(FEED);

        assertThat(Duration.between(http.requests.get(0).at(), http.requests.get(1).at()))
                .isGreaterThanOrEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void sendsConditionalHeadersAndNotModifiedMeansNothingNew() {
        http.on(ROBOTS, ok(""))
                .on(FEED, reply(200, "<rss/>", "ETag", "\"v1\"", "Last-Modified", "Wed, 11 Nov 2026 01:00:00 GMT"),
                        reply(304, ""));

        assertThat(fetch(FEED)).isPresent();
        clock.advance(Duration.ofMinutes(30));
        assertThat(fetch(FEED)).isEmpty();

        assertThat(http.requests.get(2).headers())
                .containsEntry("If-None-Match", "\"v1\"")
                .containsEntry("If-Modified-Since", "Wed, 11 Nov 2026 01:00:00 GMT");
        assertThat(fetcher.paused(URI.create(FEED))).isFalse();
    }

    @Test
    void tooManyRequestsPausesForRetryAfter() {
        http.on(ROBOTS, ok("")).on(FEED, reply(429, "", "Retry-After", "120"), ok("<rss/>"));

        assertThat(fetch(FEED)).isEmpty();
        clock.advance(Duration.ofSeconds(90));
        assertThat(fetch(FEED)).isEmpty();
        clock.advance(Duration.ofSeconds(60));
        assertThat(fetch(FEED)).isPresent();

        assertThat(http.urls()).containsExactly(ROBOTS, FEED, FEED);
    }

    @Test
    void forbiddenFeedPausesTheSiteForADay() {
        http.on(ROBOTS, ok("")).on(FEED, reply(403, "blocked"));

        fetch(FEED);
        clock.advance(Duration.ofHours(12));
        fetch(FEED);

        assertThat(http.urls()).containsExactly(ROBOTS, FEED);
    }

    @Test
    void failuresBackOffLongerEachTime() {
        http.on(ROBOTS, ok("")).failing(FEED);

        fetch(FEED);
        clock.advance(Duration.ofMinutes(31));
        fetch(FEED);
        clock.advance(Duration.ofMinutes(31));
        fetch(FEED); // 두 번째 실패 뒤에는 60분을 쉰다
        assertThat(http.urls()).containsExactly(ROBOTS, FEED, FEED);

        clock.advance(Duration.ofMinutes(30));
        fetch(FEED);
        assertThat(http.urls()).containsExactly(ROBOTS, FEED, FEED, FEED);
    }

    @Test
    void redirectChecksRobotsOfTheNewSite() {
        String other = "https://cdn.example.org";
        http.on(ROBOTS, ok(""))
                .on(FEED, reply(301, "", "Location", other + "/feed.xml"))
                .on(other + "/robots.txt", ok("User-agent: *\nDisallow: /feed\n"))
                .on(other + "/feed.xml", ok("<rss/>"));

        assertThat(fetch(FEED)).isEmpty();
        assertThat(http.urls()).containsExactly(ROBOTS, FEED, other + "/robots.txt");
    }

    @Test
    void photoFetcherTreatsForbiddenRobotsAsNoRobotsLikeTheStandard() {
        PoliteFetcher photos = new PoliteFetcher(http, AGENTS, clock, clock::advance, false);
        http.on(ROBOTS, reply(403, "")).on(FEED, reply(200, "img", "Content-Type", "image/webp", "ETag", "\"p1\""));

        Optional<PoliteFetcher.Fetched> got = photos.get(URI.create(FEED), java.util.Map.of("Accept", "image/webp"));

        assertThat(got).hasValueSatisfying(f -> {
            assertThat(f.modified()).isTrue();
            assertThat(f.contentType()).isEqualTo("image/webp");
            assertThat(f.etag()).isEqualTo("\"p1\"");
        });
        assertThat(http.requests.get(1).headers()).containsEntry("Accept", "image/webp");
    }

    @Test
    void getReportsNotModified() {
        http.on(ROBOTS, ok("")).on(FEED, reply(304, "", "ETag", "\"p1\""));

        assertThat(fetcher.get(URI.create(FEED), java.util.Map.of("If-None-Match", "\"p1\"")))
                .hasValueSatisfying(f -> assertThat(f.modified()).isFalse());
    }

    private Optional<byte[]> fetch(String url) {
        return fetcher.fetch(URI.create(url));
    }

    private static String text(byte[] body) {
        return new String(body, StandardCharsets.UTF_8);
    }
}
