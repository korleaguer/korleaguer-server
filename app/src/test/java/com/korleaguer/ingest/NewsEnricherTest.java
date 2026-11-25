package com.korleaguer.ingest;

import com.korleaguer.config.DataMode;
import com.korleaguer.config.NewsProperties;
import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.domain.NewsBlockedHost;
import com.korleaguer.domain.NewsBlockedHostRepository;
import com.korleaguer.domain.NewsDestinationRepository;
import com.korleaguer.news.PoliteFetcher;
import com.korleaguer.news.Summarizer;
import com.korleaguer.support.CommittedDbTest;
import com.korleaguer.support.FakeHttp;
import com.korleaguer.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.korleaguer.support.FakeHttp.ok;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** 요약 붙이기: 요약을 허락한 출처의 기사만, 키가 있을 때만, 페이지는 robots.txt를 지켜 한 번. (기사 내용은 지어낸 값) */
@CommittedDbTest
class NewsEnricherTest {

    private static final Instant NOV_11 = Instant.parse("2026-11-11T01:00:00Z");
    private static final String MLB = "https://mlb.example.com";
    private static final String BODY = "Roh has now officially been posted. ".repeat(10);

    @Autowired
    private NewsArticleRepository articles;
    @Autowired
    private NewsDestinationRepository destinations;
    @Autowired
    private NewsBlockedHostRepository blockedHosts;

    private final MutableClock clock = new MutableClock(NOV_11);
    private final FakeHttp http = new FakeHttp(clock);
    private final List<String> asked = new ArrayList<>();
    private final PoliteFetcher fetcher = new PoliteFetcher(http, List.of("korleaguer", "ClaudeBot"), clock, clock::advance);

    @BeforeEach
    void setUp() {
        destinations.deleteAllInBatch();
        articles.deleteAllInBatch();
        blockedHosts.deleteAllInBatch();
        http.on(MLB + "/robots.txt", ok("User-agent: *\nDisallow: /wp-admin/\nCrawl-delay: 1\n"))
                .on(MLB + "/2026/11/roh.html", ok("<meta property=\"og:image\" content=\"https://cdn.example.com/roh.jpg\">"
                        + "<div class=\"entry-content\"><p>" + BODY + "</p></div><div class=\"entry-footer\"></div>"));
    }

    @Test
    void summarizesWithTheArticleBodyAndKeepsTheImageAddress() {
        NewsArticle article = roh();

        int done = enricher(summarizer("노시환이 MLB 30개 구단과 협상을 시작했다.")).enrich();

        assertThat(done).isEqualTo(1);
        assertThat(stored(article).getSummary()).isEqualTo("노시환이 MLB 30개 구단과 협상을 시작했다.");
        assertThat(stored(article).getImageUrl()).isEqualTo("https://cdn.example.com/roh.jpg");
        assertThat(stored(article).getTitleKo()).isEqualTo("한화, 노시환 포스팅");
        assertThat(stored(article).getSummaryModel()).isEqualTo("test-model");
        // 기사 시각 2026-11-01T20:40Z는 한국 날짜로 11월 2일이다('last February' 같은 말을 한국 날짜 기준으로 옮기게)
        assertThat(asked).containsExactly("노시환|KBO's Hanwha Eagles Post Si Hwan Roh|2026-11-02|" + BODY.trim());
        assertThat(http.urls()).containsExactly(MLB + "/robots.txt", MLB + "/2026/11/roh.html");
    }

    @Test
    void onlyRecentArticlesOfTheConfiguredPublishersAreSummarized() {
        Instant oldest = NOV_11.minus(NewsEnricher.OLDEST);
        NewsArticle inTime = articles.save(new NewsArticle("노시환", MLB + "/2026/11/roh.html", "in", "Roh On Time",
                "MLB Trade Rumors", oldest, NOV_11));
        NewsArticle tooOld = articles.save(new NewsArticle("노시환", MLB + "/2026/11/too-old.html", "old", "Roh Too Old",
                "MLB Trade Rumors", oldest.minusSeconds(1), NOV_11));
        NewsArticle otherPublisher = articles.save(new NewsArticle("노시환", MLB + "/2026/11/other.html", "other",
                "Roh Elsewhere", "뺀 언론사", NOV_11.minusSeconds(60), NOV_11));

        assertThat(enricher(summarizer("요약이다.")).enrich()).isEqualTo(1);

        assertThat(stored(inTime).getSummary()).isEqualTo("요약이다.");
        assertThat(stored(tooOld).getSummary()).isNull();
        assertThat(stored(otherPublisher).getSummary()).isNull();
        assertThat(asked).hasSize(1);
        assertThat(http.urls()).containsExactly(MLB + "/robots.txt", MLB + "/2026/11/roh.html");
    }

    @Test
    void aFailedSummaryIsRetriedWithAFreshPage() {
        http.on(MLB + "/2026/11/etag.html", FakeHttp.reply(200, "<div class=\"entry-content\"><p>" + BODY + "</p></div>",
                "ETag", "\"v1\""));
        NewsArticle article = articles.save(new NewsArticle("노시환", MLB + "/2026/11/etag.html", "t", "Roh Notes",
                "MLB Trade Rumors", Instant.parse("2026-11-03T20:40:00Z"), NOV_11));
        int[] calls = {0};
        Summarizer flaky = new Summarizer() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public Optional<Summarizer.Summary> summarize(String player, String title, java.time.LocalDate published, String body) {
                if (calls[0]++ == 0) {
                    throw new Summarizer.Failure("529 overloaded", null);
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

        assertThat(stored(article).getSummary()).isEqualTo("두 번째에 요약했다.");
        assertThat(stored(article).getEnrichAttempts()).isZero(); // 시도 횟수는 '요약하지 못한 횟수'라 요약하면 0
        assertThat(http.requests).filteredOn(r -> r.uri().getPath().endsWith("etag.html"))
                .allSatisfy(r -> assertThat(r.headers()).doesNotContainKey("If-None-Match"));
    }

    @Test
    void aGoneArticleCountsButDoesNotStopTheFeeds() {
        NewsArticle article = articles.save(new NewsArticle("노시환", MLB + "/2026/11/gone.html", "g", "Roh Notes",
                "MLB Trade Rumors", Instant.parse("2026-11-04T20:40:00Z"), NOV_11));
        http.on(MLB + "/2026/11/gone.html", FakeHttp.reply(404, ""));

        enricher(summarizer("요약")).enrich();

        assertThat(stored(article).getEnrichAttempts()).isEqualTo(1);
        assertThat(fetcher.paused(java.net.URI.create(MLB + "/feed"))).isFalse();
    }

    @Test
    void aRateLimitedSiteUsesNoAttemptsAndStopsTheRun() {
        NewsArticle second = articles.save(new NewsArticle("노시환", MLB + "/2026/11/second.html", "s", "Roh Second",
                "MLB Trade Rumors", Instant.parse("2026-11-01T10:00:00Z"), NOV_11));
        NewsArticle article = articles.save(new NewsArticle("노시환", MLB + "/2026/11/limited.html", "l", "Roh Notes",
                "MLB Trade Rumors", Instant.parse("2026-11-05T20:40:00Z"), NOV_11));
        http.on(MLB + "/2026/11/limited.html", FakeHttp.reply(429, "", "Retry-After", "3600"));

        enricher(summarizer("요약")).enrich();

        assertThat(stored(article).getEnrichAttempts()).isZero();
        assertThat(stored(second).getEnrichAttempts()).isZero();
        assertThat(http.urls()).doesNotContain(MLB + "/2026/11/second.html");
    }

    @Test
    void anArticleThatKeepsFailingOnItsOwnIsCountedAndStopsTheRun() {
        NewsArticle second = articles.save(new NewsArticle("노시환", MLB + "/2026/11/second.html", "s", "Roh Second",
                "MLB Trade Rumors", Instant.parse("2026-11-01T10:00:00Z"), NOV_11));
        NewsArticle article = articles.save(new NewsArticle("노시환", MLB + "/2026/11/broken.html", "b", "Roh Notes",
                "MLB Trade Rumors", Instant.parse("2026-11-05T20:40:00Z"), NOV_11));
        http.on(MLB + "/2026/11/broken.html", FakeHttp.reply(500, ""));

        enricher(summarizer("요약")).enrich();

        assertThat(stored(article).getEnrichAttempts()).isEqualTo(1);
        assertThat(http.urls()).doesNotContain(MLB + "/2026/11/second.html");
    }

    @Test
    void summaryServiceTroubleUsesNoAttempts() {
        NewsArticle article = roh();
        Summarizer down = new Summarizer() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public Optional<Summarizer.Summary> summarize(String player, String title, java.time.LocalDate published, String body) {
                throw new Summarizer.Failure("UnauthorizedException: invalid x-api-key", null);
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

        assertThat(stored(article).getEnrichAttempts()).isZero();
        assertThat(stored(article).getSummary()).isNull();
    }

    @Test
    void leadSourcesGetTheirLeadAndPhotoFromTheArticlePage() {
        NewsArticle sitemapArticle = articles.save(new NewsArticle("노시환", MLB + "/2026/11/lead.html", "p", "노시환 소식",
                "MLB Trade Rumors", Instant.parse("2026-11-10T20:40:00Z"), NOV_11));
        http.on(MLB + "/2026/11/lead.html", ok("<meta property=\"og:description\" content=\"[예시 = 기자] 노시환이 협상을 시작했다.\">"
                + "<meta property=\"og:image\" content=\"http://img.example.kr/roh.jpg\">"));
        NewsEnricher enricher = new NewsEnricher(properties("lead"), fetcher(), fetcher(), articles, blockedHosts, Summarizer.OFF,
                DataMode.MLB, clock);

        assertThat(enricher.enrich()).isEqualTo(1);
        assertThat(stored(sitemapArticle).getLeadText()).isEqualTo("노시환이 협상을 시작했다.");
        assertThat(stored(sitemapArticle).getImageUrl()).isEqualTo("https://img.example.kr/roh.jpg");
        assertThat(stored(sitemapArticle).getPageReadAt()).isEqualTo(NOV_11);
        assertThat(enricher.enrich()).isZero();
        assertThat(http.urls()).filteredOn(u -> u.endsWith("lead.html")).hasSize(1);
    }

    @Test
    void aSiteThatRefusesArticlePagesIsNotAskedAgainForAMonth() {
        String other = "https://other.example.kr";
        NewsArticle refused = articles.save(new NewsArticle("노시환", MLB + "/2026/11/refused.html", "r", "노시환 소식",
                "MLB Trade Rumors", Instant.parse("2026-11-10T21:00:00Z"), NOV_11));
        NewsArticle fine = articles.save(new NewsArticle("노시환", other + "/view/1", "f", "노시환 다른 소식", "MLB Trade Rumors",
                Instant.parse("2026-11-10T20:00:00Z"), NOV_11));
        http.on(MLB + "/2026/11/refused.html", FakeHttp.reply(403, ""))
                .on(other + "/robots.txt", ok("User-agent: *\nAllow: /\n"))
                .on(other + "/view/1", ok("<meta property=\"og:description\" content=\"노시환이 협상을 시작했다.\">"));
        NewsEnricher enricher = new NewsEnricher(properties("lead"), fetcher(), fetcher(), articles, blockedHosts, Summarizer.OFF,
                DataMode.MLB, clock);

        enricher.enrich();
        clock.advance(java.time.Duration.ofDays(2));
        enricher.enrich();

        assertThat(stored(refused).getEnrichAttempts()).isZero();
        assertThat(stored(fine).getLeadText()).isEqualTo("노시환이 협상을 시작했다.");
        assertThat(http.urls()).filteredOn(u -> u.endsWith("refused.html")).hasSize(1);
    }

    @Test
    void aRefusingSiteIsRememberedInTheDatabaseAcrossRestarts() {
        String blocked = "https://blocked.example.kr";
        articles.save(new NewsArticle("노시환", blocked + "/article/9", "b", "노시환 소식", "MLB Trade Rumors",
                Instant.parse("2026-11-10T21:00:00Z"), NOV_11));
        // 다시 뜬 서버: DB에 이미 막힌 사이트로 적혀 있다
        blockedHosts.save(new NewsBlockedHost("blocked.example.kr", NOV_11.plus(java.time.Duration.ofDays(20))));
        NewsEnricher enricher = new NewsEnricher(properties("lead"), fetcher(), fetcher(), articles, blockedHosts,
                Summarizer.OFF, DataMode.MLB, clock);

        enricher.enrich();

        assertThat(http.urls()).noneMatch(u -> u.startsWith(blocked));
    }

    @Test
    void aRefusedArticlePageIsSavedAsABlockedHostAndTheFeedIsNotPaused() {
        articles.save(new NewsArticle("노시환", MLB + "/2026/11/refused2.html", "r2", "노시환 소식", "MLB Trade Rumors",
                Instant.parse("2026-11-10T21:00:00Z"), NOV_11));
        http.on(MLB + "/2026/11/refused2.html", FakeHttp.reply(403, ""));
        NewsEnricher enricher = new NewsEnricher(properties("lead"), fetcher(), fetcher(), articles, blockedHosts,
                Summarizer.OFF, DataMode.MLB, clock);

        enricher.enrich();

        assertThat(blockedHosts.findAll()).extracting(NewsBlockedHost::getHost, NewsBlockedHost::getBlockedUntil)
                .containsExactly(tuple("mlb.example.com", NOV_11.plus(NewsEnricher.PAGE_BLOCK)));
        assertThat(fetcher.paused(java.net.URI.create(MLB + "/feed"))).isFalse();
    }

    @Test
    void withoutAnApiKeyNoPageIsRequested() {
        roh();

        assertThat(enricher(Summarizer.OFF).enrich()).isZero();
        assertThat(http.requests).isEmpty();
    }

    @Test
    void koreanLeadSourcesAreSummarizedTooAndKeepTheirOwnTitleAndFeedPhoto() {
        String kr = "https://kr.example.kr";
        NewsArticle article = articles.save(new NewsArticle("노시환", kr + "/article/1", "k", "노시환, 메츠 관심…\"3루 보강 후보\"",
                "MLB Trade Rumors", Instant.parse("2026-11-10T20:40:00Z"), NOV_11));
        article.setImageUrl("https://img.example.kr/feed.jpg");
        articles.save(article);
        http.on(kr + "/robots.txt", ok("User-agent: *\nAllow: /\n"))
                .on(kr + "/article/1", ok("<meta property=\"og:image\" content=\"https://img.example.kr/og.jpg\">"
                        + "<meta property=\"og:description\" content=\"(예시 홍길동 기자) 노시환이 메츠의 관심을 받는다.\">"
                        + "<div id=\"body\">" + "노시환이 뉴욕 메츠의 3루 보강 후보로 다시 거론되고 있다는 미국 매체 보도가 나왔다.<br>".repeat(6)
                        + "</div>"));
        NewsEnricher enricher = new NewsEnricher(properties("lead"), fetcher(), fetcher(), articles, blockedHosts,
                summarizer("노시환이 메츠의 3루 보강 후보로 거론됐다."), DataMode.MLB, clock);

        assertThat(enricher.enrich()).isEqualTo(1);
        assertThat(stored(article).getSummary()).isEqualTo("노시환이 메츠의 3루 보강 후보로 거론됐다.");
        assertThat(stored(article).getTitleKo()).isNull(); // 국내 기사는 언론사 제목 그대로
        assertThat(stored(article).getImageUrl()).isEqualTo("https://img.example.kr/feed.jpg"); // 피드 사진을 지킨다
        assertThat(stored(article).getLeadText()).isEqualTo("(예시 홍길동 기자) 노시환이 메츠의 관심을 받는다.");
        assertThat(asked).singleElement().asString().contains("노시환이 뉴욕 메츠의 3루 보강 후보");
        assertThat(http.urls()).filteredOn(u -> u.endsWith("/article/1")).hasSize(1); // 페이지는 한 번만
    }

    @Test
    void summariesFromAnOlderPromptAreRedoneAfterTheArticlesWithoutOne() {
        roh();
        NewsArticle old = oldSummary("/2026/11/old.html");
        http.on(MLB + "/2026/11/old.html", ok("<div class=\"entry-content\"><p>" + BODY + "</p></div>"));

        int done = enricher(versioned("새 요약이다.", "새 제목")).enrich();

        assertThat(done).isEqualTo(2);
        assertThat(asked).hasSize(2);
        assertThat(asked.getFirst()).startsWith("노시환|KBO's Hanwha Eagles Post Si Hwan Roh|"); // 요약이 없던 기사가 먼저
        assertThat(stored(old).getSummary()).isEqualTo("새 요약이다.");
        assertThat(stored(old).getTitleKo()).isEqualTo("새 제목");
        // 모델 이름(test-model)이 아니라 판(test-model/v2)을 적고, 같은 판으로 고른다
        assertThat(stored(old).getSummaryModel()).isEqualTo("test-model/v2");
        assertThat(enricher(versioned("또 요약", null)).enrich()).isZero(); // 이제 같은 판이라 다시 하지 않는다
        assertThat(asked).hasSize(2);
    }

    @Test
    void aRedoneSummaryWithoutATitleLineKeepsTheKoreanTitle() {
        NewsArticle old = oldSummary("/2026/11/old-title.html");
        http.on(MLB + "/2026/11/old-title.html", ok("<div class=\"entry-content\"><p>" + BODY + "</p></div>"));

        enricher(versioned("새 요약이다.", null)).enrich();

        assertThat(stored(old).getSummary()).isEqualTo("새 요약이다.");
        assertThat(stored(old).getTitleKo()).isEqualTo("옛 제목");
    }

    @Test
    void summaryServiceTroubleWhileRedoingKeepsTheOldSummaryAndUsesNoAttempts() {
        NewsArticle old = oldSummary("/2026/11/old-down.html");
        http.on(MLB + "/2026/11/old-down.html", ok("<div class=\"entry-content\"><p>" + BODY + "</p></div>"));
        Summarizer down = new Summarizer() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public Optional<Summarizer.Summary> summarize(String player, String title, java.time.LocalDate published, String body) {
                throw new Summarizer.Failure("529 overloaded", null);
            }

            @Override
            public String model() {
                return "test-model";
            }

            @Override
            public String version() {
                return "test-model/v2";
            }
        };

        enricher(down).enrich();

        assertThat(stored(old).getSummary()).isEqualTo("옛 요약이다.");
        assertThat(stored(old).getSummaryModel()).isEqualTo("test-model/v1");
        assertThat(stored(old).getEnrichAttempts()).isZero();
    }

    @Test
    void newArticlesFillTheRunBeforeAnyOldSummaryIsRedone() {
        for (int i = 0; i < NewsEnricher.PER_RUN; i++) {
            String path = "/2026/11/new" + i + ".html";
            articles.save(new NewsArticle("노시환", MLB + path, "n" + i, "Roh News " + i, "MLB Trade Rumors",
                    Instant.parse("2026-11-10T20:40:00Z").minusSeconds(i), NOV_11));
            http.on(MLB + path, ok("<div class=\"entry-content\"><p>" + BODY + "</p></div>"));
        }
        NewsArticle old = oldSummary("/2026/11/old-later.html");

        int done = enricher(versioned("새 요약이다.", null)).enrich();

        assertThat(done).isEqualTo(NewsEnricher.PER_RUN);
        assertThat(stored(old).getSummary()).isEqualTo("옛 요약이다."); // 이번 차례에는 손대지 않는다
        assertThat(http.urls()).doesNotContain(MLB + "/2026/11/old-later.html");
    }

    /** 옛 판(test-model/v1)으로 요약해 둔 영어 기사 */
    private NewsArticle oldSummary(String path) {
        NewsArticle old = new NewsArticle("노시환", MLB + path, "o" + path.hashCode(), "Roh Old Notes", "MLB Trade Rumors",
                Instant.parse("2026-11-09T20:40:00Z"), NOV_11);
        old.enriched(null, "옛 제목", "옛 요약이다.", "test-model/v1", NOV_11.minus(java.time.Duration.ofDays(1)));
        return articles.save(old);
    }

    /** 판(version)을 모델 이름과 다르게 주는 요약기(운영의 claude-haiku-5-5 / claude-haiku-5-5/v2 처럼) */
    private Summarizer versioned(String answer, String titleKo) {
        return new Summarizer() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public Optional<Summarizer.Summary> summarize(String player, String title, java.time.LocalDate published, String body) {
                asked.add(player + "|" + title + "|" + published + "|" + body);
                return Optional.of(new Summarizer.Summary(titleKo, answer));
            }

            @Override
            public String model() {
                return "test-model";
            }

            @Override
            public String version() {
                return "test-model/v2";
            }
        };
    }

    @Test
    void anOldSummaryStaysWhenItCannotBeRedone() {
        NewsArticle old = new NewsArticle("노시환", MLB + "/2026/11/old-gone.html", "og", "Roh Old Notes", "MLB Trade Rumors",
                Instant.parse("2026-11-09T20:40:00Z"), NOV_11);
        old.enriched(null, "옛 제목", "옛 요약이다.", "test-model/v1", NOV_11.minus(java.time.Duration.ofDays(1)));
        articles.save(old);
        http.on(MLB + "/2026/11/old-gone.html", FakeHttp.reply(404, ""));

        enricher(summarizer("새 요약이다.")).enrich();

        assertThat(stored(old).getSummary()).isEqualTo("옛 요약이다.");
        assertThat(stored(old).getSummaryModel()).isEqualTo("test-model/v1");
        assertThat(stored(old).getEnrichAttempts()).isEqualTo(1);
    }

    @Test
    void givesUpAfterThreeFailures() {
        NewsArticle article = articles.save(new NewsArticle("노시환", MLB + "/2026/11/empty.html", "e", "Roh Notes",
                "MLB Trade Rumors", Instant.parse("2026-11-02T20:40:00Z"), NOV_11));
        http.on(MLB + "/2026/11/empty.html", ok("<html>no article here</html>"));
        NewsEnricher enricher = enricher(summarizer("요약"));

        for (int i = 0; i < 5; i++) {
            enricher.enrich();
            clock.advance(java.time.Duration.ofMinutes(30));
        }

        assertThat(stored(article).getSummary()).isNull();
        assertThat(stored(article).getEnrichAttempts()).isEqualTo(NewsEnricher.MAX_ATTEMPTS);
        assertThat(asked).isEmpty();
    }

    /** 아직 요약이 없는 기사 하나(DB에 저장) */
    private NewsArticle roh() {
        return articles.save(new NewsArticle("노시환", MLB + "/2026/11/roh.html", "h",
                "KBO's Hanwha Eagles Post Si Hwan Roh", "MLB Trade Rumors", Instant.parse("2026-11-01T20:40:00Z"), NOV_11));
    }

    /** DB에 저장된 그 기사. 수집이 저장을 빠뜨리면 바뀌기 전 값이 나온다 */
    private NewsArticle stored(NewsArticle article) {
        return articles.findById(article.getId()).orElseThrow();
    }

    private NewsEnricher enricher(Summarizer summarizer) {
        return new NewsEnricher(properties("ai"), fetcher(), fetcher(), articles, blockedHosts, summarizer, DataMode.MLB, clock);
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
            public Optional<Summarizer.Summary> summarize(String player, String title, java.time.LocalDate published, String body) {
                asked.add(player + "|" + title + "|" + published + "|" + body);
                return Optional.of(new Summarizer.Summary("한화, 노시환 포스팅", answer));
            }

            @Override
            public String model() {
                return "test-model";
            }
        };
    }
}
