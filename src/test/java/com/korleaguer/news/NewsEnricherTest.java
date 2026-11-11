package com.korleaguer.news;

import com.korleaguer.config.DataMode;
import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.korleaguer.news.FakeHttp.ok;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 요약 붙이기: 요약을 허락한 출처의 기사만, 키가 있을 때만, 페이지는 robots.txt를 지켜 한 번. (기사 내용은 지어낸 값) */
class NewsEnricherTest {

    private static final Instant NOV_11 = Instant.parse("2026-11-11T01:00:00Z");
    private static final String MLB = "https://mlb.example.com";
    private static final String BODY = "Roh has now officially been posted. ".repeat(10);

    private final MutableClock clock = new MutableClock(NOV_11);
    private final FakeHttp http = new FakeHttp(clock);
    private final NewsArticleRepository articles = mock(NewsArticleRepository.class);
    private final List<String> asked = new ArrayList<>();
    private final PoliteFetcher fetcher = new PoliteFetcher(http, List.of("korleaguer", "ClaudeBot"), clock, clock::advance);
    private final List<NewsArticle> queue = new ArrayList<>();
    private NewsArticle article;

    @BeforeEach
    void setUp() {
        article = new NewsArticle("노시환", MLB + "/2026/11/roh.html", "h", "KBO's Hanwha Eagles Post Si Hwan Roh",
                "MLB Trade Rumors", Instant.parse("2026-11-01T20:40:00Z"), NOV_11);
        when(articles.findToEnrich(anyCollection(), anyInt(), any(), any())).thenAnswer(inv -> {
            List<NewsArticle> all = queue.isEmpty() ? List.of(article) : queue;
            return all.stream()
                    .filter(a -> a.getSummary() == null && a.getEnrichAttempts() < (int) inv.getArgument(1))
                    .toList();
        });
        http.on(MLB + "/robots.txt", ok("User-agent: *\nDisallow: /wp-admin/\nCrawl-delay: 1\n"))
                .on(MLB + "/2026/11/roh.html", ok("<meta property=\"og:image\" content=\"https://cdn.example.com/roh.jpg\">"
                        + "<div class=\"entry-content\"><p>" + BODY + "</p></div><div class=\"entry-footer\"></div>"));
    }

    @Test
    void summarizesWithTheArticleBodyAndKeepsTheImageAddress() {
        int done = enricher(summarizer("노시환이 MLB 30개 구단과 협상을 시작했다.")).enrich();

        assertThat(done).isEqualTo(1);
        assertThat(article.getSummary()).isEqualTo("노시환이 MLB 30개 구단과 협상을 시작했다.");
        assertThat(article.getImageUrl()).isEqualTo("https://cdn.example.com/roh.jpg");
        assertThat(article.getTitleKo()).isEqualTo("한화, 노시환 포스팅");
        assertThat(article.getSummaryModel()).isEqualTo("test-model");
        assertThat(asked).containsExactly("노시환|KBO's Hanwha Eagles Post Si Hwan Roh|" + BODY.trim());
        assertThat(http.urls()).containsExactly(MLB + "/robots.txt", MLB + "/2026/11/roh.html");
    }

    @Test
    void aFailedSummaryIsRetriedWithAFreshPage() {
        http.on(MLB + "/2026/11/etag.html", FakeHttp.reply(200, "<div class=\"entry-content\"><p>" + BODY + "</p></div>",
                "ETag", "\"v1\""));
        article = new NewsArticle("노시환", MLB + "/2026/11/etag.html", "t", "Roh Notes", "MLB Trade Rumors",
                Instant.parse("2026-11-03T20:40:00Z"), NOV_11);
        int[] calls = {0};
        Summarizer flaky = new Summarizer() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public Optional<Summarizer.Summary> summarize(String player, String title, String body) {
                if (calls[0]++ == 0) {
                    throw new Summarizer.Failure("529 overloaded", false, null);
                }
                return Optional.of(new Summarizer.Summary(null, "두 번째에 요약했다."));
            }

            @Override
            public String model() {
                return "test-model";
            }
        };
        NewsEnricher enricher = enricher(flaky);

        enricher.enrich();
        clock.advance(java.time.Duration.ofMinutes(30));
        enricher.enrich();

        assertThat(article.getSummary()).isEqualTo("두 번째에 요약했다.");
        assertThat(article.getEnrichAttempts()).isEqualTo(1);
        assertThat(http.requests).filteredOn(r -> r.uri().getPath().endsWith("etag.html"))
                .allSatisfy(r -> assertThat(r.headers()).doesNotContainKey("If-None-Match"));
    }

    @Test
    void aGoneArticleCountsButDoesNotStopTheFeeds() {
        article = new NewsArticle("노시환", MLB + "/2026/11/gone.html", "g", "Roh Notes", "MLB Trade Rumors",
                Instant.parse("2026-11-04T20:40:00Z"), NOV_11);
        http.on(MLB + "/2026/11/gone.html", FakeHttp.reply(404, ""));

        enricher(summarizer("요약")).enrich();

        assertThat(article.getEnrichAttempts()).isEqualTo(1);
        assertThat(fetcher.paused(java.net.URI.create(MLB + "/feed"))).isFalse();
    }

    @Test
    void aRateLimitedSiteUsesNoAttemptsAndStopsTheRun() {
        NewsArticle second = new NewsArticle("노시환", MLB + "/2026/11/second.html", "s", "Roh Second", "MLB Trade Rumors",
                Instant.parse("2026-11-01T10:00:00Z"), NOV_11);
        article = new NewsArticle("노시환", MLB + "/2026/11/limited.html", "l", "Roh Notes", "MLB Trade Rumors",
                Instant.parse("2026-11-05T20:40:00Z"), NOV_11);
        queue.addAll(List.of(article, second));
        http.on(MLB + "/2026/11/limited.html", FakeHttp.reply(429, "", "Retry-After", "3600"));

        enricher(summarizer("요약")).enrich();

        assertThat(article.getEnrichAttempts()).isZero();
        assertThat(second.getEnrichAttempts()).isZero();
        assertThat(http.urls()).doesNotContain(MLB + "/2026/11/second.html");
    }

    @Test
    void summaryServiceTroubleUsesNoAttempts() {
        Summarizer down = new Summarizer() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public Optional<Summarizer.Summary> summarize(String player, String title, String body) {
                throw new Summarizer.Failure("UnauthorizedException: invalid x-api-key", false, null);
            }

            @Override
            public String model() {
                return "test-model";
            }
        };

        for (int i = 0; i < 4; i++) {
            enricher(down).enrich();
            clock.advance(java.time.Duration.ofMinutes(30));
        }

        assertThat(article.getEnrichAttempts()).isZero();
        assertThat(article.getSummary()).isNull();
    }

    @Test
    void withoutAnApiKeyNoPageIsRequested() {
        assertThat(enricher(Summarizer.OFF).enrich()).isZero();
        assertThat(http.requests).isEmpty();
    }

    @Test
    void sourcesWithoutAiPreviewAreNotSummarized() {
        NewsEnricher enricher = new NewsEnricher(properties("lead"), fetcher(), articles, summarizer("요약"),
                DataMode.MLB, clock);

        assertThat(enricher.enrich()).isZero();
        assertThat(http.requests).isEmpty();
    }

    @Test
    void givesUpAfterThreeFailures() {
        article = new NewsArticle("노시환", MLB + "/2026/11/empty.html", "e", "Roh Notes", "MLB Trade Rumors",
                Instant.parse("2026-11-02T20:40:00Z"), NOV_11);
        http.on(MLB + "/2026/11/empty.html", ok("<html>no article here</html>"));
        NewsEnricher enricher = enricher(summarizer("요약"));

        for (int i = 0; i < 5; i++) {
            enricher.enrich();
            clock.advance(java.time.Duration.ofMinutes(30));
        }

        assertThat(article.getSummary()).isNull();
        assertThat(article.getEnrichAttempts()).isEqualTo(NewsEnricher.MAX_ATTEMPTS);
        assertThat(asked).isEmpty();
    }

    private NewsEnricher enricher(Summarizer summarizer) {
        return new NewsEnricher(properties("ai"), fetcher(), articles, summarizer, DataMode.MLB, clock);
    }

    private PoliteFetcher fetcher() {
        return fetcher;
    }

    private static NewsProperties properties(String preview) {
        return new NewsProperties(true, "0 */30 * * * *", "korleaguer/test", List.of("korleaguer", "ClaudeBot"),
                List.of(new NewsProperties.Source("MLB Trade Rumors", MLB + "/feed", null, null, null, preview)));
    }

    private Summarizer summarizer(String answer) {
        return new Summarizer() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public Optional<Summarizer.Summary> summarize(String player, String title, String body) {
                asked.add(player + "|" + title + "|" + body);
                return Optional.of(new Summarizer.Summary("한화, 노시환 포스팅", answer));
            }

            @Override
            public String model() {
                return "test-model";
            }
        };
    }
}
