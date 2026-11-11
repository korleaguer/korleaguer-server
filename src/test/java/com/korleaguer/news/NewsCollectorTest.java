package com.korleaguer.news;

import com.korleaguer.config.DataMode;
import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.posting.PostingProperties;
import com.korleaguer.posting.Postings;
import com.korleaguer.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.korleaguer.news.FakeHttp.ok;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 관련 기사 모으기: 협상 기간에만, 이름이 든 제목만, 같은 기사는 한 번만, 큰 피드는 간격을 두고. (기사 내용은 지어낸 값) */
class NewsCollectorTest {

    // 한국 2026-11-11 10:00. 노시환 협상 기간(미국 11/2 08:00 ~ 12/1 17:00) 안이다.
    private static final Instant NOV_11 = Instant.parse("2026-11-11T01:00:00Z");
    private static final String NEWS = "https://news.example.kr";
    private static final String MLB = "https://mlb.example.com";

    private final MutableClock clock = new MutableClock(NOV_11);
    private final FakeHttp http = new FakeHttp(clock);
    private final NewsArticleRepository articles = mock(NewsArticleRepository.class);
    private final List<NewsArticle> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(articles.save(any(NewsArticle.class))).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(articles.existsBySubjectAndUrlHash(anyString(), anyString())).thenAnswer(inv -> saved.stream()
                .anyMatch(a -> a.getSubject().equals(inv.getArgument(0)) && a.getUrlHash().equals(inv.getArgument(1))));
        http.on(NEWS + "/robots.txt", ok("User-agent: *\nDisallow: /search\n"))
                .on(MLB + "/robots.txt", ok("User-agent: *\nDisallow: /search\nCrawl-delay: 1\n"));
    }

    @Test
    void nothingIsRequestedOutsideThePostingWindow() {
        clock.set(Instant.parse("2026-10-01T00:00:00Z"));

        assertThat(collector(source("뉴시스", NEWS + "/rss.xml")).collect()).isZero();
        assertThat(http.requests).isEmpty();
    }

    @Test
    void sampleModeNeverTouchesTheNetwork() {
        NewsCollector collector = new NewsCollector(properties(source("뉴시스", NEWS + "/rss.xml")), postings(),
                fetcher(), articles, DataMode.SAMPLE, clock);

        assertThat(collector.collect()).isZero();
        assertThat(http.requests).isEmpty();
    }

    @Test
    void keepsOnlyHeadlinesThatNameThePlayer() {
        http.on(NEWS + "/rss.xml", ok(rss(
                item("노시환, 30개 구단과 협상 시작", NEWS + "/view/1?utm_source=rss#top", "Wed, 11 Nov 2026 09:00:00 +0900"),
                item("다른 선수 FA 소식", NEWS + "/view/2", "Wed, 11 Nov 2026 09:30:00 +0900"),
                item("Padres Interested In Si Hwan Roh", NEWS + "/view/3", "Tue, 10 Nov 2026 20:00:00 +0000"))));

        int count = collector(source("뉴시스", NEWS + "/rss.xml")).collect();

        assertThat(count).isEqualTo(2);
        assertThat(saved).extracting(NewsArticle::getSubject, NewsArticle::getTitle, NewsArticle::getUrl,
                        NewsArticle::getPublisher, NewsArticle::getPublishedAt)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("노시환", "노시환, 30개 구단과 협상 시작", NEWS + "/view/1",
                                "뉴시스", Instant.parse("2026-11-11T00:00:00Z")),
                        org.assertj.core.groups.Tuple.tuple("노시환", "Padres Interested In Si Hwan Roh",
                                NEWS + "/view/3", "뉴시스", Instant.parse("2026-11-10T20:00:00Z")));
    }

    @Test
    void playerFeedCountsEveryItemForThatPlayer() {
        http.on(MLB + "/players/si-hwan-roh/feed", ok(rss(
                item("NL West Notes: Padres, Giants", MLB + "/2026/11/nl-west-notes.html",
                        "Tue, 10 Nov 2026 18:00:00 +0000"))));
        NewsProperties.Source playerFeed = new NewsProperties.Source("MLB Trade Rumors",
                MLB + "/players/si-hwan-roh/feed", "UTC", "노시환", null);

        assertThat(collector(playerFeed).collect()).isEqualTo(1);
        assertThat(saved).extracting(NewsArticle::getTitle).containsExactly("NL West Notes: Padres, Giants");
    }

    @Test
    void sameArticleIsSavedOnce() {
        http.on(NEWS + "/rss.xml", ok(rss(item("노시환 관련 소식", NEWS + "/view/1", "Wed, 11 Nov 2026 09:00:00 +0900"))));
        NewsCollector collector = collector(source("뉴시스", NEWS + "/rss.xml"));

        assertThat(collector.collect()).isEqualTo(1);
        clock.advance(Duration.ofMinutes(30));
        assertThat(collector.collect()).isZero();
        assertThat(saved).hasSize(1);
    }

    @Test
    void bigFeedWaitsForItsInterval() {
        http.on(NEWS + "/big.xml", ok(rss(item("노시환 소식", NEWS + "/view/9", "Wed, 11 Nov 2026 09:00:00 +0900"))));
        NewsCollector collector = collector(new NewsProperties.Source("마이데일리", NEWS + "/big.xml", null, null,
                Duration.ofHours(2)));

        collector.collect();
        clock.advance(Duration.ofMinutes(30));
        collector.collect();
        clock.advance(Duration.ofMinutes(90));
        collector.collect();

        assertThat(http.urls().stream().filter(u -> u.endsWith("/big.xml"))).hasSize(2);
    }

    @Test
    void oldOrFutureDatesAreHandled() {
        http.on(NEWS + "/rss.xml", ok(rss(
                item("노시환 지난 시즌 활약", NEWS + "/view/old", "Tue, 01 Sep 2026 09:00:00 +0900"),
                item("노시환 시간대가 틀린 기사", NEWS + "/view/future", "Fri, 20 Nov 2026 09:00:00 +0900"))));

        collector(source("뉴시스", NEWS + "/rss.xml")).collect();

        assertThat(saved).extracting(NewsArticle::getUrl, NewsArticle::getPublishedAt)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(NEWS + "/view/future", NOV_11));
    }

    @Test
    void robotsBlockedFeedSavesNothing() {
        http.on(NEWS + "/search/rss.xml", ok(rss(item("노시환", NEWS + "/view/1", "Wed, 11 Nov 2026 09:00:00 +0900"))));

        assertThat(collector(source("뉴시스", NEWS + "/search/rss.xml")).collect()).isZero();
        assertThat(http.urls()).containsExactly(NEWS + "/robots.txt");
    }

    @Test
    void mentionsIgnoresCaseAndHyphens() {
        List<String> names = List.of("노시환", "Si-Hwan Roh");

        assertThat(NewsCollector.mentions("SI HWAN ROH drawing interest", names)).isTrue();
        assertThat(NewsCollector.mentions("한화 노시환이 미국으로", names)).isTrue();
        assertThat(NewsCollector.mentions("Roh family news", names)).isFalse();
    }

    private NewsCollector collector(NewsProperties.Source... sources) {
        return new NewsCollector(properties(sources), postings(), fetcher(), articles, DataMode.MLB, clock);
    }

    private PoliteFetcher fetcher() {
        return new PoliteFetcher(http, List.of("korleaguer", "ClaudeBot"), clock, clock::advance);
    }

    private static NewsProperties properties(NewsProperties.Source... sources) {
        return new NewsProperties(true, "0 */30 * * * *", "korleaguer/test", List.of("korleaguer", "ClaudeBot"),
                List.of(sources));
    }

    private static NewsProperties.Source source(String publisher, String url) {
        return new NewsProperties.Source(publisher, url, null, null, null);
    }

    private static Postings postings() {
        return new Postings(new PostingProperties(List.of(new PostingProperties.Current("노시환", "한화 이글스", "3루수",
                2000, List.of("Si-Hwan Roh", "Si Hwan Roh"), "2026-11-02T08:00:00-05:00", "2026-12-01T17:00:00-05:00",
                null, null)), List.of()));
    }

    private static String rss(String... items) {
        return "<rss version=\"2.0\"><channel><title>t</title>" + String.join("", items) + "</channel></rss>";
    }

    private static String item(String title, String link, String date) {
        return "<item><title><![CDATA[" + title + "]]></title><link>" + link.replace("&", "&amp;")
                + "</link><pubDate>" + date + "</pubDate></item>";
    }
}
