package com.korleaguer.news;

import com.korleaguer.config.DataMode;
import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.ingest.RosterProperties;
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
    private final PlayerRepository players = mock(PlayerRepository.class);
    /** 명단 선수(기본은 없음). 명단 선수 테스트에서 채운다 */
    private RosterProperties roster = new RosterProperties(List.of());
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
                roster, players, fetcher(), fetcher(), articles, DataMode.SAMPLE, clock);

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
                MLB + "/players/si-hwan-roh/feed", "UTC", "노시환", null, null);

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
                Duration.ofHours(2), null));

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
    void leadIsKeptOnlyWhereTheSourceAllowsIt() {
        String body = "<rss version=\"2.0\" xmlns:content=\"http://purl.org/rss/1.0/modules/content/\"><channel><title>t</title>"
                + "<item><title>노시환 소식</title><link>%s</link><pubDate>Wed, 11 Nov 2026 09:00:00 +0900</pubDate>"
                + "<content:encoded><![CDATA[<p>노시환이 협상을 시작했다.</p>]]></content:encoded></item></channel></rss>";
        http.on(NEWS + "/lead.xml", ok(body.formatted(NEWS + "/view/1")))
                .on(NEWS + "/plain.xml", ok(body.formatted(NEWS + "/view/2")));

        collector(new NewsProperties.Source("마이데일리", NEWS + "/lead.xml", null, null, null, "lead"),
                source("다른 곳", NEWS + "/plain.xml")).collect();

        assertThat(saved).extracting(NewsArticle::getUrl, NewsArticle::getLeadText).containsExactly(
                org.assertj.core.groups.Tuple.tuple(NEWS + "/view/1", "노시환이 협상을 시작했다."),
                org.assertj.core.groups.Tuple.tuple(NEWS + "/view/2", null));
    }

    @Test
    void anArticleSavedBeforeLeadsGetsItsLeadFromTheFeed() {
        NewsArticle old = new NewsArticle("노시환", NEWS + "/view/5", NewsUrls.sha256(NEWS + "/view/5"), "노시환 소식",
                "마이데일리", Instant.parse("2026-11-10T00:00:00Z"), Instant.parse("2026-11-10T00:10:00Z"));
        saved.add(old);
        when(articles.findBySubjectAndUrlHash(anyString(), anyString())).thenAnswer(inv -> saved.stream()
                .filter(a -> a.getSubject().equals(inv.getArgument(0)) && a.getUrlHash().equals(inv.getArgument(1)))
                .findFirst());
        http.on(NEWS + "/lead.xml", ok("<rss version=\"2.0\"><channel><title>t</title><item><title>노시환 소식</title>"
                + "<link>" + NEWS + "/view/5</link><description>노시환이 협상을 시작했다.</description></item></channel></rss>"));

        int count = collector(new NewsProperties.Source("마이데일리", NEWS + "/lead.xml", null, null, null, "lead")).collect();

        assertThat(count).isZero();
        assertThat(old.getLeadText()).isEqualTo("노시환이 협상을 시작했다.");
    }

    @Test
    void feedPhotosAreSavedAndFilledIntoOlderArticles() {
        NewsArticle old = new NewsArticle("노시환", NEWS + "/view/8", NewsUrls.sha256(NEWS + "/view/8"), "노시환 옛 소식",
                "연합뉴스", Instant.parse("2026-11-10T00:00:00Z"), Instant.parse("2026-11-10T00:10:00Z"));
        saved.add(old);
        when(articles.findBySubjectAndUrlHash(anyString(), anyString())).thenAnswer(inv -> saved.stream()
                .filter(a -> a.getSubject().equals(inv.getArgument(0)) && a.getUrlHash().equals(inv.getArgument(1)))
                .findFirst());
        http.on(NEWS + "/photo.xml", ok("<rss version=\"2.0\"><channel><title>t</title>"
                + "<item><title>노시환 새 소식</title><link>" + NEWS + "/view/9</link>"
                + "<enclosure url=\"http://img.example.kr/9.jpg\" type=\"image/jpeg\"/></item>"
                + "<item><title>노시환 옛 소식</title><link>" + NEWS + "/view/8</link>"
                + "<description><![CDATA[<img src=\"https://img.example.kr/8.jpg\"> 본문]]></description></item>"
                + "</channel></rss>"));

        collector(new NewsProperties.Source("연합뉴스", NEWS + "/photo.xml", null, null, null, "lead")).collect();

        assertThat(old.getImageUrl()).isEqualTo("https://img.example.kr/8.jpg");
        assertThat(saved).filteredOn(a -> a.getUrl().endsWith("/view/9")).extracting(NewsArticle::getImageUrl)
                .containsExactly("https://img.example.kr/9.jpg");
    }

    @Test
    void aSiteThatBlocksAiCrawlersStillGivesLeadsButNotAiSources() {
        String site = "https://noai.example.kr";
        http.on(site + "/robots.txt", ok("User-agent: ClaudeBot\nDisallow: /\n\nUser-agent: *\nAllow: /\n"))
                .on(site + "/lead.xml", ok(rss(item("노시환 소식", site + "/view/1", "Wed, 11 Nov 2026 09:00:00 +0900"))))
                .on(site + "/ai.xml", ok(rss(item("노시환 다른 소식", site + "/view/2", "Wed, 11 Nov 2026 09:00:00 +0900"))));
        PoliteFetcher plain = new PoliteFetcher(http, List.of("korleaguer"), clock, clock::advance);
        PoliteFetcher ai = new PoliteFetcher(http, List.of("korleaguer", "ClaudeBot"), clock, clock::advance);
        NewsCollector collector = new NewsCollector(properties(
                new NewsProperties.Source("앞부분", site + "/lead.xml", null, null, null, "lead"),
                new NewsProperties.Source("요약", site + "/ai.xml", null, null, null, "ai")),
                postings(), roster, players, plain, ai, articles, DataMode.MLB, clock);

        assertThat(collector.collect()).isEqualTo(1);
        assertThat(saved).extracting(NewsArticle::getPublisher).containsExactly("앞부분");
        assertThat(http.urls()).doesNotContain(site + "/ai.xml");
    }

    @Test
    void rosterPlayersAreWatchedByKoreanAndMlbNamesEvenOutsideThePostingWindow() {
        clock.set(Instant.parse("2026-10-20T01:00:00Z")); // 노시환 협상 기간 밖
        roster = new RosterProperties(List.of(
                new RosterProperties.Entry("김혜성", 808975L, null, null, null, List.of()),
                new RosterProperties.Entry("고우석", 808970L, null, "LG 트윈스", null, List.of())));
        Player kim = mock(Player.class);
        when(kim.getNameKo()).thenReturn("김혜성");
        when(kim.getNameEn()).thenReturn("Hyeseong Kim");
        when(players.findByMlbIdIsNotNull()).thenReturn(List.of(kim));
        http.on(NEWS + "/rss.xml", ok(rss(
                item("김혜성, 다저스 신임 코치와 반등 준비", NEWS + "/view/1", "Tue, 20 Oct 2026 09:00:00 +0900"),
                item("Dodgers Notes: Hyeseong Kim", NEWS + "/view/2", "Mon, 19 Oct 2026 20:00:00 +0000"),
                item("고우석, LG 마무리 복귀", NEWS + "/view/3", "Tue, 20 Oct 2026 09:00:00 +0900"),
                item("김혜성 지난달 소식", NEWS + "/view/4", "Thu, 10 Sep 2026 09:00:00 +0900"))));

        int count = collector(source("뉴시스", NEWS + "/rss.xml")).collect();

        // 국내 리그로 돌아간 고우석은 모으지 않고, 30일보다 오래된 기사는 버린다
        assertThat(count).isEqualTo(2);
        assertThat(saved).extracting(NewsArticle::getSubject, NewsArticle::getUrl).containsExactly(
                org.assertj.core.groups.Tuple.tuple("김혜성", NEWS + "/view/1"),
                org.assertj.core.groups.Tuple.tuple("김혜성", NEWS + "/view/2"));
    }

    @Test
    void aPostingPlayerKeepsThePostingWindowWhenAlsoOnTheRoster() {
        roster = new RosterProperties(List.of(new RosterProperties.Entry("노시환", null, null, null, null, List.of())));

        List<NewsCollector.Subject> subjects = collector(source("뉴시스", NEWS + "/rss.xml")).subjects(NOV_11);

        assertThat(subjects).hasSize(1);
        assertThat(subjects.getFirst().oldest()).isEqualTo(Instant.parse("2026-11-02T13:00:00Z").minus(Duration.ofDays(30)));
    }

    @Test
    void mentionsIgnoresCaseAndHyphens() {
        List<String> names = List.of("노시환", "Si-Hwan Roh");

        assertThat(NewsCollector.mentions("SI HWAN ROH drawing interest", names)).isTrue();
        assertThat(NewsCollector.mentions("한화 노시환이 미국으로", names)).isTrue();
        assertThat(NewsCollector.mentions("Roh family news", names)).isFalse();
    }

    private NewsCollector collector(NewsProperties.Source... sources) {
        return new NewsCollector(properties(sources), postings(), roster, players, fetcher(), fetcher(), articles,
                DataMode.MLB, clock);
    }

    private PoliteFetcher fetcher() {
        return new PoliteFetcher(http, List.of("korleaguer", "ClaudeBot"), clock, clock::advance);
    }

    private static NewsProperties properties(NewsProperties.Source... sources) {
        return new NewsProperties(true, "0 */30 * * * *", "korleaguer/test", List.of("korleaguer", "ClaudeBot"),
                List.of(sources));
    }

    private static NewsProperties.Source source(String publisher, String url) {
        return new NewsProperties.Source(publisher, url, null, null, null, null);
    }

    private static Postings postings() {
        return new Postings(new PostingProperties(List.of(new PostingProperties.Current("노시환", null, "한화 이글스", "3루수",
                2000, List.of("Si-Hwan Roh", "Si Hwan Roh"), "2026-11-02T08:00:00-05:00", "2026-12-01T17:00:00-05:00",
                null, null, null)), List.of()));
    }

    private static String rss(String... items) {
        return "<rss version=\"2.0\"><channel><title>t</title>" + String.join("", items) + "</channel></rss>";
    }

    private static String item(String title, String link, String date) {
        return "<item><title><![CDATA[" + title + "]]></title><link>" + link.replace("&", "&amp;")
                + "</link><pubDate>" + date + "</pubDate></item>";
    }
}
