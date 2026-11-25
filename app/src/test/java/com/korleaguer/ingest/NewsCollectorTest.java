package com.korleaguer.ingest;

import com.korleaguer.config.DataMode;
import com.korleaguer.config.NewsProperties;
import com.korleaguer.config.RosterProperties;
import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.domain.NewsDestinationRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.news.PoliteFetcher;
import com.korleaguer.posting.PostingProperties;
import com.korleaguer.posting.Postings;
import com.korleaguer.support.CommittedDbTest;
import com.korleaguer.support.FakeHttp;
import com.korleaguer.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.korleaguer.support.FakeHttp.ok;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 관련 기사 모으기: 협상 기간에만, 이름이 든 제목만, 같은 기사는 한 번만, 큰 피드는 간격을 두고. (기사 내용은 지어낸 값) */
@CommittedDbTest
class NewsCollectorTest {

    // 한국 2026-11-11 10:00. 노시환 협상 기간(미국 11/2 08:00 ~ 12/1 17:00) 안이다.
    private static final Instant NOV_11 = Instant.parse("2026-11-11T01:00:00Z");
    private static final String NEWS = "https://news.example.kr";
    private static final String MLB = "https://mlb.example.com";

    private final MutableClock clock = new MutableClock(NOV_11);
    private final FakeHttp http = new FakeHttp(clock);
    @Autowired
    private NewsArticleRepository articles;
    @Autowired
    private NewsDestinationRepository destinations;
    private final PlayerRepository players = mock(PlayerRepository.class);
    /** 명단 선수(기본은 없음). 명단 선수 테스트에서 채운다 */
    private RosterProperties roster = new RosterProperties(List.of());

    @BeforeEach
    void setUp() {
        destinations.deleteAllInBatch();
        articles.deleteAllInBatch();
        http.on(NEWS + "/robots.txt", ok("User-agent: *\nDisallow: /search\n"))
                .on(MLB + "/robots.txt", ok("User-agent: *\nDisallow: /search\nCrawl-delay: 1\n"));
    }

    @Test
    @DisplayName("협상 기간이 아니면 기사 피드에 요청하지 않는다")
    void nothingIsRequestedOutsideThePostingWindow() {
        // given
        clock.set(Instant.parse("2026-10-01T00:00:00Z"));

        // when // then
        assertThat(collector(source("뉴시스", NEWS + "/rss.xml")).collect()).isZero();
        assertThat(http.requests).isEmpty();
    }

    @Test
    @DisplayName("샘플 모드에서는 기사 사이트에 요청하지 않는다")
    void sampleModeNeverTouchesTheNetwork() {
        // given
        NewsCollector collector = new NewsCollector(properties(source("뉴시스", NEWS + "/rss.xml")), postings(),
                roster, players, fetcher(), fetcher(), articles, DataMode.SAMPLE, clock);

        // when // then
        assertThat(collector.collect()).isZero();
        assertThat(http.requests).isEmpty();
    }

    @Test
    @DisplayName("제목에 선수의 한글·영어 이름이 든 기사만 저장한다")
    void keepsOnlyHeadlinesThatNameThePlayer() {
        // given
        http.on(NEWS + "/rss.xml", ok(rss(
                item("노시환, 30개 구단과 협상 시작", NEWS + "/view/1?utm_source=rss#top", "Wed, 11 Nov 2026 09:00:00 +0900"),
                item("다른 선수 FA 소식", NEWS + "/view/2", "Wed, 11 Nov 2026 09:30:00 +0900"),
                item("Padres Interested In Si Hwan Roh", NEWS + "/view/3", "Tue, 10 Nov 2026 20:00:00 +0000"))));

        // when
        int count = collector(source("뉴시스", NEWS + "/rss.xml")).collect();

        // then
        assertThat(count).isEqualTo(2);
        assertThat(saved()).extracting(NewsArticle::getSubject, NewsArticle::getTitle, NewsArticle::getUrl,
                        NewsArticle::getPublisher, NewsArticle::getPublishedAt)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("노시환", "노시환, 30개 구단과 협상 시작", NEWS + "/view/1",
                                "뉴시스", Instant.parse("2026-11-11T00:00:00Z")),
                        org.assertj.core.groups.Tuple.tuple("노시환", "Padres Interested In Si Hwan Roh",
                                NEWS + "/view/3", "뉴시스", Instant.parse("2026-11-10T20:00:00Z")));
    }

    @Test
    @DisplayName("선수 전용 피드의 기사는 제목에 이름이 없어도 저장한다")
    void playerFeedCountsEveryItemForThatPlayer() {
        // given
        http.on(MLB + "/players/si-hwan-roh/feed", ok(rss(
                item("NL West Notes: Padres, Giants", MLB + "/2026/11/nl-west-notes.html",
                        "Tue, 10 Nov 2026 18:00:00 +0000"))));
        NewsProperties.Source playerFeed = new NewsProperties.Source("MLB Trade Rumors",
                MLB + "/players/si-hwan-roh/feed", "UTC", "노시환", null, null);

        // when // then
        assertThat(collector(playerFeed).collect()).isEqualTo(1);
        assertThat(saved()).extracting(NewsArticle::getTitle).containsExactly("NL West Notes: Padres, Giants");
    }

    @Test
    @DisplayName("같은 기사를 다시 받아도 한 번만 저장한다")
    void sameArticleIsSavedOnce() {
        // given
        http.on(NEWS + "/rss.xml", ok(rss(item("노시환 MLB 관련 소식", NEWS + "/view/1", "Wed, 11 Nov 2026 09:00:00 +0900"))));
        NewsCollector collector = collector(source("뉴시스", NEWS + "/rss.xml"));

        // when // then
        assertThat(collector.collect()).isEqualTo(1);

        // given
        clock.advance(Duration.ofMinutes(30));

        // when // then
        assertThat(collector.collect()).isZero();
        assertThat(saved()).hasSize(1);
    }

    @Test
    @DisplayName("간격이 두 시간인 큰 피드는 두 시간이 지나야 다시 요청한다")
    void bigFeedWaitsForItsInterval() {
        // given
        http.on(NEWS + "/big.xml", ok(rss(item("노시환 MLB 소식", NEWS + "/view/9", "Wed, 11 Nov 2026 09:00:00 +0900"))));
        NewsCollector collector = collector(new NewsProperties.Source("마이데일리", NEWS + "/big.xml", null, null,
                Duration.ofHours(2), null));

        // when
        collector.collect();
        clock.advance(Duration.ofMinutes(30));
        collector.collect();
        clock.advance(Duration.ofMinutes(90));
        collector.collect();

        // then
        assertThat(http.urls().stream().filter(u -> u.endsWith("/big.xml"))).hasSize(2);
    }

    @Test
    @DisplayName("오래된 기사는 버리고 날짜가 미래인 기사는 지금 시각으로 저장한다")
    void oldOrFutureDatesAreHandled() {
        // given
        http.on(NEWS + "/rss.xml", ok(rss(
                item("노시환 지난 시즌 활약, 美 평가", NEWS + "/view/old", "Tue, 01 Sep 2026 09:00:00 +0900"),
                item("노시환 MLB 시간대가 틀린 기사", NEWS + "/view/future", "Fri, 20 Nov 2026 09:00:00 +0900"))));

        // when
        collector(source("뉴시스", NEWS + "/rss.xml")).collect();

        // then
        assertThat(saved()).extracting(NewsArticle::getUrl, NewsArticle::getPublishedAt)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(NEWS + "/view/future", NOV_11));
    }

    @Test
    @DisplayName("robots.txt가 막은 주소의 피드는 받지 않는다")
    void robotsBlockedFeedSavesNothing() {
        // given
        http.on(NEWS + "/search/rss.xml", ok(rss(item("노시환", NEWS + "/view/1", "Wed, 11 Nov 2026 09:00:00 +0900"))));

        // when // then
        assertThat(collector(source("뉴시스", NEWS + "/search/rss.xml")).collect()).isZero();
        assertThat(http.urls()).containsExactly(NEWS + "/robots.txt");
    }

    @Test
    @DisplayName("앞부분을 허락한 출처의 기사만 앞부분을 저장한다")
    void leadIsKeptOnlyWhereTheSourceAllowsIt() {
        // given
        String body = "<rss version=\"2.0\" xmlns:content=\"http://purl.org/rss/1.0/modules/content/\"><channel><title>t</title>"
                + "<item><title>노시환 MLB 소식</title><link>%s</link><pubDate>Wed, 11 Nov 2026 09:00:00 +0900</pubDate>"
                + "<content:encoded><![CDATA[<p>노시환이 협상을 시작했다.</p>]]></content:encoded></item></channel></rss>";
        http.on(NEWS + "/lead.xml", ok(body.formatted(NEWS + "/view/1")))
                .on(NEWS + "/plain.xml", ok(body.formatted(NEWS + "/view/2")));

        // when
        collector(new NewsProperties.Source("마이데일리", NEWS + "/lead.xml", null, null, null, "lead"),
                source("다른 곳", NEWS + "/plain.xml")).collect();

        // then
        assertThat(saved()).extracting(NewsArticle::getUrl, NewsArticle::getLeadText).containsExactly(
                org.assertj.core.groups.Tuple.tuple(NEWS + "/view/1", "노시환이 협상을 시작했다."),
                org.assertj.core.groups.Tuple.tuple(NEWS + "/view/2", null));
    }

    @Test
    @DisplayName("앞부분 없이 저장된 기사는 피드에서 앞부분만 채우고 새 기사로 세지 않는다")
    void anArticleSavedBeforeLeadsGetsItsLeadFromTheFeed() {
        // given
        NewsArticle old = new NewsArticle("노시환", NEWS + "/view/5", NewsUrls.sha256(NEWS + "/view/5"), "노시환 MLB 소식",
                "마이데일리", Instant.parse("2026-11-10T00:00:00Z"), Instant.parse("2026-11-10T00:10:00Z"));
        articles.save(old);
        http.on(NEWS + "/lead.xml", ok("<rss version=\"2.0\"><channel><title>t</title><item><title>노시환 MLB 소식</title>"
                + "<link>" + NEWS + "/view/5</link><description>노시환이 협상을 시작했다.</description></item></channel></rss>"));

        // when
        int count = collector(new NewsProperties.Source("마이데일리", NEWS + "/lead.xml", null, null, null, "lead")).collect();

        // then
        assertThat(count).isZero();
        assertThat(stored(old).getLeadText()).isEqualTo("노시환이 협상을 시작했다.");
    }

    @Test
    @DisplayName("피드의 사진을 새 기사에 저장하고 사진이 없던 옛 기사에도 채운다")
    void feedPhotosAreSavedAndFilledIntoOlderArticles() {
        // given
        NewsArticle old = new NewsArticle("노시환", NEWS + "/view/8", NewsUrls.sha256(NEWS + "/view/8"), "노시환 옛 MLB 소식",
                "연합뉴스", Instant.parse("2026-11-10T00:00:00Z"), Instant.parse("2026-11-10T00:10:00Z"));
        articles.save(old);
        http.on(NEWS + "/photo.xml", ok("<rss version=\"2.0\"><channel><title>t</title>"
                + "<item><title>노시환 새 MLB 소식</title><link>" + NEWS + "/view/9</link>"
                + "<enclosure url=\"http://img.example.kr/9.jpg\" type=\"image/jpeg\"/></item>"
                + "<item><title>노시환 옛 MLB 소식</title><link>" + NEWS + "/view/8</link>"
                + "<description><![CDATA[<img src=\"https://img.example.kr/8.jpg\"> 본문]]></description></item>"
                + "</channel></rss>"));

        // when
        collector(new NewsProperties.Source("연합뉴스", NEWS + "/photo.xml", null, null, null, "lead")).collect();

        // then
        assertThat(stored(old).getImageUrl()).isEqualTo("https://img.example.kr/8.jpg");
        assertThat(saved()).filteredOn(a -> a.getUrl().endsWith("/view/9")).extracting(NewsArticle::getImageUrl)
                .containsExactly("https://img.example.kr/9.jpg");
    }

    @Test
    @DisplayName("AI 수집을 막은 사이트는 앞부분 출처만 받고 AI 요약 출처는 요청하지 않는다")
    void aSiteThatBlocksAiCrawlersStillGivesLeadsButNotAiSources() {
        // given
        String site = "https://noai.example.kr";
        http.on(site + "/robots.txt", ok("User-agent: ClaudeBot\nDisallow: /\n\nUser-agent: *\nAllow: /\n"))
                .on(site + "/lead.xml", ok(rss(item("노시환 MLB 소식", site + "/view/1", "Wed, 11 Nov 2026 09:00:00 +0900"))))
                .on(site + "/ai.xml", ok(rss(item("노시환 MLB 다른 소식", site + "/view/2", "Wed, 11 Nov 2026 09:00:00 +0900"))));
        PoliteFetcher plain = new PoliteFetcher(http, List.of("korleaguer"), clock, clock::advance);
        PoliteFetcher ai = new PoliteFetcher(http, List.of("korleaguer", "ClaudeBot"), clock, clock::advance);
        NewsCollector collector = new NewsCollector(properties(
                new NewsProperties.Source("앞부분", site + "/lead.xml", null, null, null, "lead"),
                new NewsProperties.Source("요약", site + "/ai.xml", null, null, null, "ai")),
                postings(), roster, players, plain, ai, articles, DataMode.MLB, clock);

        // when // then
        assertThat(collector.collect()).isEqualTo(1);
        assertThat(saved()).extracting(NewsArticle::getPublisher).containsExactly("앞부분");
        assertThat(http.urls()).doesNotContain(site + "/ai.xml");
    }

    @Test
    @DisplayName("명단 선수 기사는 협상 기간 밖에도 한글·영어 이름으로 찾아 저장한다")
    void rosterPlayersAreWatchedByKoreanAndMlbNamesEvenOutsideThePostingWindow() {
        // given
        clock.set(Instant.parse("2026-10-20T01:00:00Z")); // 노시환 협상 기간 밖
        roster = new RosterProperties(List.of(
                new RosterProperties.Entry("김혜성", 808975L, null, null, null, List.of(), null),
                new RosterProperties.Entry("고우석", 808970L, null, "LG 트윈스", null, List.of(), null)));
        Player kim = mock(Player.class);
        when(kim.getNameKo()).thenReturn("김혜성");
        when(kim.getNameEn()).thenReturn("Hyeseong Kim");
        when(players.findByMlbIdIsNotNull()).thenReturn(List.of(kim));
        http.on(NEWS + "/rss.xml", ok(rss(
                item("김혜성, 다저스 신임 코치와 반등 준비", NEWS + "/view/1", "Tue, 20 Oct 2026 09:00:00 +0900"),
                item("Dodgers Notes: Hyeseong Kim", NEWS + "/view/2", "Mon, 19 Oct 2026 20:00:00 +0000"),
                item("고우석, LG 마무리 복귀", NEWS + "/view/3", "Tue, 20 Oct 2026 09:00:00 +0900"),
                item("김혜성 지난달 소식", NEWS + "/view/4", "Thu, 10 Sep 2026 09:00:00 +0900"))));

        // when
        int count = collector(source("뉴시스", NEWS + "/rss.xml")).collect();

        // then
        // 국내 리그로 돌아간 고우석은 모으지 않고, 30일보다 오래된 기사는 버린다
        assertThat(count).isEqualTo(2);
        assertThat(saved()).extracting(NewsArticle::getSubject, NewsArticle::getUrl).containsExactly(
                org.assertj.core.groups.Tuple.tuple("김혜성", NEWS + "/view/1"),
                org.assertj.core.groups.Tuple.tuple("김혜성", NEWS + "/view/2"));
    }

    @Test
    @DisplayName("명단에도 있는 포스팅 선수는 한 번만 세고 협상 시작 30일 전부터 모은다")
    void aPostingPlayerKeepsThePostingWindowWhenAlsoOnTheRoster() {
        // given
        roster = new RosterProperties(List.of(new RosterProperties.Entry("노시환", null, null, null, null, List.of(), null)));

        // when
        List<NewsCollector.Subject> subjects = collector(source("뉴시스", NEWS + "/rss.xml")).subjects(NOV_11);

        // then
        assertThat(subjects).hasSize(1);
        assertThat(subjects.getFirst().oldest()).isEqualTo(Instant.parse("2026-11-02T13:00:00Z").minus(Duration.ofDays(30)));
    }

    @Test
    @DisplayName("포스팅 선수는 국내 FA 이야기를 빼고 명단 선수는 이름이 나오면 저장한다")
    void aPostingPlayerKboArticleIsLeftOutButARosterPlayersIsNot() {
        // given
        roster = new RosterProperties(List.of(new RosterProperties.Entry("김하성", 673490L, null, null, null, List.of(), null)));
        when(players.findByMlbIdIsNotNull()).thenReturn(List.of());
        http.on(NEWS + "/rss.xml", ok(rss(
                item("KIA가 필요한 FA는 원태인…노시환급 초대형계약?", NEWS + "/view/1", "Wed, 11 Nov 2026 09:00:00 +0900"),
                item("노시환, 美 매체 호평", NEWS + "/view/2", "Wed, 11 Nov 2026 09:30:00 +0900"),
                item("김하성, 퀄리파잉 오퍼 못 받았다", NEWS + "/view/3", "Wed, 11 Nov 2026 09:30:00 +0900"))));

        // when
        collector(source("뉴시스", NEWS + "/rss.xml")).collect();

        // then
        assertThat(saved()).extracting(NewsArticle::getUrl).containsExactly(NEWS + "/view/2", NEWS + "/view/3");
    }

    @Test
    @DisplayName("이름은 대소문자와 붙임표를 따지지 않고 찾고 성만 나오면 찾지 않는다")
    void mentionsIgnoresCaseAndHyphens() {
        // given
        List<String> names = List.of("노시환", "Si-Hwan Roh");

        // when // then
        assertThat(NewsCollector.mentions("SI HWAN ROH drawing interest", names)).isTrue();
        assertThat(NewsCollector.mentions("한화 노시환이 미국으로", names)).isTrue();
        assertThat(NewsCollector.mentions("Roh family news", names)).isFalse();
    }

    /** DB에 저장된 기사 전부(저장한 순) */
    private List<NewsArticle> saved() {
        return articles.findAll(Sort.by("id"));
    }

    private NewsArticle stored(NewsArticle article) {
        return articles.findById(article.getId()).orElseThrow();
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
                null, null, null, null)), List.of()));
    }

    private static String rss(String... items) {
        return "<rss version=\"2.0\"><channel><title>t</title>" + String.join("", items) + "</channel></rss>";
    }

    private static String item(String title, String link, String date) {
        return "<item><title><![CDATA[" + title + "]]></title><link>" + link.replace("&", "&amp;")
                + "</link><pubDate>" + date + "</pubDate></item>";
    }
}
