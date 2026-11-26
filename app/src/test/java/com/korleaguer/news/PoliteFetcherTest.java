package com.korleaguer.news;

import com.korleaguer.support.FakeHttp;
import com.korleaguer.support.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.korleaguer.support.FakeHttp.ok;
import static com.korleaguer.support.FakeHttp.reply;
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
    @DisplayName("기사가 없거나 막혀도 요청을 쉬지 않고, 피드가 사라지면 쉰다")
    void aGoneOrRefusedArticlePageDoesNotPauseTheSiteButAGoneFeedDoes() {
        // given
        String page = SITE + "/view/1";
        String refused = SITE + "/view/2";
        http.on(ROBOTS, ok("User-agent: *\nAllow: /\n")).on(page, reply(404, "")).on(refused, reply(403, ""))
                .on(FEED, reply(410, ""));

        // when // then
        assertThat(fetcher.getPage(URI.create(page))).isEqualTo(new PoliteFetcher.Page(404, null));
        assertThat(fetcher.getPage(URI.create(refused))).isEqualTo(new PoliteFetcher.Page(403, null));
        assertThat(fetcher.paused(URI.create(FEED))).isFalse();

        // when // then
        assertThat(fetch(FEED)).isEmpty();
        assertThat(fetcher.paused(URI.create(FEED))).isTrue();
    }

    @Test
    @DisplayName("robots.txt가 막은 피드는 요청하지 않는다")
    void feedDisallowedByRobotsIsNeverRequested() {
        // given
        http.on(ROBOTS, ok("User-agent: *\nDisallow: /RSS/\n")).on(FEED, ok("<rss/>"));

        // when // then
        assertThat(fetch(FEED)).isEmpty();
        assertThat(http.urls()).containsExactly(ROBOTS);
    }

    @Test
    @DisplayName("모두에게 열려 있어도 ClaudeBot을 막은 사이트의 피드는 요청하지 않는다")
    void claudeFamilyBlockCountsEvenWhenStarAllows() {
        // given
        http.on(ROBOTS, ok("User-agent: *\nAllow: /\n\nUser-agent: ClaudeBot\nDisallow: /\n")).on(FEED, ok("<rss/>"));

        // when // then
        assertThat(fetch(FEED)).isEmpty();
        assertThat(http.urls()).containsExactly(ROBOTS);
    }

    @Test
    @DisplayName("robots.txt가 없으면 피드를 받는다")
    void missingRobotsAllowsEverything() {
        // given
        http.on(ROBOTS, reply(404, "")).on(FEED, ok("<rss/>"));

        // when // then
        assertThat(fetch(FEED)).map(PoliteFetcherTest::text).contains("<rss/>");
        assertThat(http.urls()).containsExactly(ROBOTS, FEED);
    }

    @Test
    @DisplayName("robots.txt가 다른 주소로 넘겨지면 따라가서 그 규칙대로 피드를 받는다")
    void robotsRedirectIsFollowed() {
        // given
        String moved = SITE + "/static/robots.txt";
        http.on(ROBOTS, reply(301, "", "Location", moved))
                .on(moved, ok("User-agent: *\nAllow: /\n"))
                .on(FEED, ok("<rss/>"));

        // when // then
        assertThat(fetch(FEED)).map(PoliteFetcherTest::text).contains("<rss/>");
        assertThat(http.urls()).containsExactly(ROBOTS, moved, FEED);
    }

    @Test
    @DisplayName("넘김이 아닌 robots.txt 응답에 붙은 Location은 따라가지 않는다")
    void locationOnANonRedirectRobotsReplyIsIgnored() {
        // given
        // 404(규칙 없음 = 모두 허용)에 Location이 붙어 와도 그 주소의 규칙(모두 막음)을 읽지 않는다
        String elsewhere = SITE + "/blocked-robots.txt";
        http.on(ROBOTS, reply(404, "", "Location", elsewhere))
                .on(elsewhere, ok("User-agent: *\nDisallow: /\n"))
                .on(FEED, ok("<rss/>"));

        // when // then
        assertThat(fetch(FEED)).map(PoliteFetcherTest::text).contains("<rss/>");
        assertThat(http.urls()).containsExactly(ROBOTS, FEED);
    }

    @Test
    @DisplayName("robots.txt에서 서버 오류가 나면 피드를 요청하지 않고 그 사이트 요청을 쉰다")
    void robotsServerErrorMeansDoNotFetchAndPauseTheSite() {
        // given
        http.on(ROBOTS, reply(500, "")).on(FEED, ok("<rss/>"));

        // when // then
        assertThat(fetch(FEED)).isEmpty();
        assertThat(fetcher.paused(URI.create(FEED))).isTrue();

        // given
        clock.advance(Duration.ofMinutes(10));

        // when // then
        assertThat(fetch(FEED)).isEmpty();
        assertThat(http.urls()).containsExactly(ROBOTS);
    }

    @Test
    @DisplayName("robots.txt가 403이면 23시간 뒤에도 그 사이트 요청을 쉰다")
    void robotsForbiddenPausesTheSiteForADay() {
        // given
        http.on(ROBOTS, reply(403, "")).on(FEED, ok("<rss/>"));

        // when // then
        assertThat(fetch(FEED)).isEmpty();

        // given
        clock.advance(Duration.ofHours(23));

        // when // then
        assertThat(fetcher.paused(URI.create(FEED))).isTrue();
        assertThat(http.urls()).containsExactly(ROBOTS);
    }

    @Test
    @DisplayName("robots.txt는 하루 동안 다시 쓰고 하루가 지나면 새로 받는다")
    void robotsIsReusedForADay() {
        // given
        http.on(ROBOTS, ok("User-agent: *\nAllow: /\n")).on(FEED, ok("<rss/>"));

        // when
        fetch(FEED);
        clock.advance(Duration.ofMinutes(30));
        fetch(FEED);
        clock.advance(Duration.ofHours(25));
        fetch(FEED);

        // then
        assertThat(http.urls()).containsExactly(ROBOTS, FEED, FEED, ROBOTS, FEED);
    }

    @Test
    @DisplayName("같은 사이트에는 1초 이상 띄워 요청한다")
    void waitsAtLeastOneSecondBetweenRequestsToTheSameSite() {
        // given
        http.on(ROBOTS, ok("")).on(FEED, ok("<rss/>"));

        // when
        fetch(FEED);

        // then
        assertThat(Duration.between(http.requests.get(0).at(), http.requests.get(1).at()))
                .isGreaterThanOrEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("사이트가 정한 간격이 5초면 5초 이상 띄워 요청한다")
    void longerCrawlDelayIsRespected() {
        // given
        http.on(ROBOTS, ok("User-agent: *\nCrawl-delay: 5\n")).on(FEED, ok("<rss/>"));

        // when
        fetch(FEED);

        // then
        assertThat(Duration.between(http.requests.get(0).at(), http.requests.get(1).at()))
                .isGreaterThanOrEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("받은 피드를 조건부로 다시 물어 304가 오면 새것이 없다고 본다")
    void sendsConditionalHeadersAndNotModifiedMeansNothingNew() {
        // given
        http.on(ROBOTS, ok(""))
                .on(FEED, reply(200, "<rss/>", "ETag", "\"v1\"", "Last-Modified", "Wed, 11 Nov 2026 01:00:00 GMT"),
                        reply(304, ""));

        // when // then
        assertThat(fetch(FEED)).isPresent();

        // given
        clock.advance(Duration.ofMinutes(30));

        // when // then
        assertThat(fetch(FEED)).isEmpty();

        assertThat(http.requests.get(2).headers())
                .containsEntry("If-None-Match", "\"v1\"")
                .containsEntry("If-Modified-Since", "Wed, 11 Nov 2026 01:00:00 GMT");
        assertThat(fetcher.paused(URI.create(FEED))).isFalse();
    }

    @Test
    @DisplayName("429를 받으면 사이트가 말한 시간만큼 쉬었다가 다시 요청한다")
    void tooManyRequestsPausesForRetryAfter() {
        // given
        http.on(ROBOTS, ok("")).on(FEED, reply(429, "", "Retry-After", "120"), ok("<rss/>"));

        // when // then
        assertThat(fetch(FEED)).isEmpty();

        // given
        clock.advance(Duration.ofSeconds(90));

        // when // then
        assertThat(fetch(FEED)).isEmpty();

        // given
        clock.advance(Duration.ofSeconds(60));

        // when // then
        assertThat(fetch(FEED)).isPresent();

        assertThat(http.urls()).containsExactly(ROBOTS, FEED, FEED);
    }

    @Test
    @DisplayName("피드가 403이면 12시간 뒤에도 그 사이트에 요청하지 않는다")
    void forbiddenFeedPausesTheSiteForADay() {
        // given
        http.on(ROBOTS, ok("")).on(FEED, reply(403, "blocked"));

        // when
        fetch(FEED);
        clock.advance(Duration.ofHours(12));
        fetch(FEED);

        // then
        assertThat(http.urls()).containsExactly(ROBOTS, FEED);
    }

    @Test
    @DisplayName("피드를 받지 못할 때마다 쉬는 시간이 길어진다")
    void failuresBackOffLongerEachTime() {
        // given
        http.on(ROBOTS, ok("")).failing(FEED);

        // when
        fetch(FEED);
        clock.advance(Duration.ofMinutes(31));
        fetch(FEED);
        clock.advance(Duration.ofMinutes(31));
        fetch(FEED); // 두 번째 실패 뒤에는 60분을 쉰다

        // then
        assertThat(http.urls()).containsExactly(ROBOTS, FEED, FEED);

        // given
        clock.advance(Duration.ofMinutes(30));

        // when
        fetch(FEED);

        // then
        assertThat(http.urls()).containsExactly(ROBOTS, FEED, FEED, FEED);
    }

    @Test
    @DisplayName("옮겨 간 사이트의 robots.txt가 막으면 그 피드를 요청하지 않는다")
    void redirectChecksRobotsOfTheNewSite() {
        // given
        String other = "https://cdn.example.org";
        http.on(ROBOTS, ok(""))
                .on(FEED, reply(301, "", "Location", other + "/feed.xml"))
                .on(other + "/robots.txt", ok("User-agent: *\nDisallow: /feed\n"))
                .on(other + "/feed.xml", ok("<rss/>"));

        // when // then
        assertThat(fetch(FEED)).isEmpty();
        assertThat(http.urls()).containsExactly(ROBOTS, FEED, other + "/robots.txt");
    }

    @Test
    @DisplayName("사진을 받을 때 robots.txt가 403이면 막지 않은 것으로 보고 받는다")
    void photoFetcherTreatsForbiddenRobotsAsNoRobotsLikeTheStandard() {
        // given
        PoliteFetcher photos = new PoliteFetcher(http, AGENTS, clock, clock::advance, false);
        http.on(ROBOTS, reply(403, "")).on(FEED, reply(200, "img", "Content-Type", "image/webp", "ETag", "\"p1\""));

        // when
        Optional<PoliteFetcher.Fetched> got = photos.get(URI.create(FEED), java.util.Map.of("Accept", "image/webp"));

        // then
        assertThat(got).hasValueSatisfying(f -> {
            assertThat(f.modified()).isTrue();
            assertThat(f.contentType()).isEqualTo("image/webp");
            assertThat(f.etag()).isEqualTo("\"p1\"");
        });
        assertThat(http.requests.get(1).headers()).containsEntry("Accept", "image/webp");
    }

    @Test
    @DisplayName("304가 오면 바뀌지 않았다고 알려 준다")
    void getReportsNotModified() {
        // given
        http.on(ROBOTS, ok("")).on(FEED, reply(304, "", "ETag", "\"p1\""));

        // when // then
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
