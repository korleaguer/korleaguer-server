package com.korleaguer.api;

import com.korleaguer.config.KorleaguerProperties;
import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.ingest.RosterProperties;
import com.korleaguer.news.NewsProperties;
import com.korleaguer.photo.PhotoSources;
import com.korleaguer.posting.PostingCase;
import com.korleaguer.posting.PostingProperties;
import com.korleaguer.posting.Postings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 홈의 포스팅 묶음: 상태(협상 전·중·마감·결과), 한국 날짜 기준 남은 날, 지난 비율, 지난 기록의 '마감 며칠 전', 관련 기사.
 * 노시환: 협상 미국 11/2 08:00(한국 11/2 22:00) ~ 12/1 17:00(한국 12/2 07:00)
 */
class PostingFinderTest {

    private static final String OPENS = "2026-11-02T08:00:00-05:00";
    private static final String DEADLINE = "2026-12-01T17:00:00-05:00";
    /** 지금 수집하는 언론사(뉴시스는 이용약관으로 뺐다) */
    private static final NewsProperties NEWS = new NewsProperties(false, null, null, null, List.of(
            new NewsProperties.Source("MLB Trade Rumors", "https://example.com/mlbtr", null, null, null, null),
            new NewsProperties.Source("마이데일리", "https://example.com/mydaily", null, null, null, null)));

    private final NewsArticleRepository news = mock(NewsArticleRepository.class);

    @BeforeEach
    void setUp() {
        when(news.findLatest(anyString(), any())).thenReturn(List.of());
    }

    @Test
    void negotiatingCountsKoreanDaysLeftAndElapsedShare() {
        // 한국 11/11 10:00. 마감(한국 12/2)까지 21일, 705시간 중 204시간 지남
        PostingSummary.Player roh = finder(roh(null)).find(Instant.parse("2026-11-11T01:00:00Z")).players().getFirst();

        assertThat(roh.status()).isEqualTo("NEGOTIATING");
        assertThat(roh.daysLeft()).isEqualTo(21);
        assertThat(roh.elapsedRatio()).isEqualTo(0.289);
        assertThat(roh.windowOpensAt()).isEqualTo(Instant.parse("2026-11-02T13:00:00Z"));
        assertThat(roh.deadlineAt()).isEqualTo(Instant.parse("2026-12-01T22:00:00Z"));
        assertThat(roh.deal()).isNull();
    }

    @Test
    void deadlineDayInKoreaIsZeroDaysLeft() {
        // 한국 12/2 05:00, 마감 두 시간 전
        PostingSummary.Player roh = finder(roh(null)).find(Instant.parse("2026-12-01T20:00:00Z")).players().getFirst();

        assertThat(roh.status()).isEqualTo("NEGOTIATING");
        assertThat(roh.daysLeft()).isZero();
    }

    @Test
    void beforeTheWindowOpensIsUpcoming() {
        PostingSummary.Player roh = finder(roh(null)).find(Instant.parse("2026-11-01T20:00:00Z")).players().getFirst();

        assertThat(roh.status()).isEqualTo("UPCOMING");
        assertThat(roh.daysLeft()).isEqualTo(30);
        assertThat(roh.elapsedRatio()).isZero();
    }

    @Test
    void pastDeadlineWithoutResultIsClosed() {
        PostingSummary.Player roh = finder(roh(null)).find(Instant.parse("2026-12-01T23:00:00Z")).players().getFirst();

        assertThat(roh.status()).isEqualTo("CLOSED");
        assertThat(roh.daysLeft()).isNull();
        assertThat(roh.elapsedRatio()).isEqualTo(1.0);
    }

    @Test
    void timeKeyChangesWithStatusVisibilityAndEachPercentOfTheBar() {
        PostingCase roh = new Postings(new PostingProperties(List.of(roh(null)), List.of())).current().getFirst();

        // 협상 시작(한국 11/2 22:00) 전후로 상태가 바뀐다
        assertThat(PostingFinder.timeKey(roh, Instant.parse("2026-11-02T12:59:00Z"))).isEqualTo("UPCOMING:true:0");
        assertThat(PostingFinder.timeKey(roh, Instant.parse("2026-11-02T13:00:00Z"))).isEqualTo("NEGOTIATING:true:0");
        // 진행 막대는 1% 단위(705시간의 1%는 약 7시간)
        assertThat(PostingFinder.timeKey(roh, Instant.parse("2026-11-11T01:00:00Z"))).isEqualTo("NEGOTIATING:true:29");
        // 마감(한국 12/2 07:00)이 지나면 마감, 60일 뒤에는 홈에서 빠진다
        Instant deadline = Instant.parse("2026-12-01T22:00:00Z");
        assertThat(PostingFinder.timeKey(roh, deadline)).isEqualTo("CLOSED:true:100");
        assertThat(PostingFinder.timeKey(roh, deadline.plus(Duration.ofDays(60)))).isEqualTo("CLOSED:false:100");
    }

    @Test
    void signedPlayerShowsTheDeal() {
        PostingProperties.Result signed = new PostingProperties.Result("SIGNED", "SD", "2026-11-28", 4, 30_000_000L);

        PostingSummary.Player roh = finder(roh(signed)).find(Instant.parse("2026-11-29T01:00:00Z")).players().getFirst();

        assertThat(roh.status()).isEqualTo("SIGNED");
        assertThat(roh.daysLeft()).isNull();
        assertThat(roh.deal()).isEqualTo(new PostingSummary.Deal(135L, "샌디에이고", LocalDate.parse("2026-11-28"), 4,
                30_000_000L));
    }

    @Test
    void playerLeavesSixtyDaysAfterTheDeadlineButHistoryStays() {
        PostingSummary summary = finder(roh(null)).find(Instant.parse("2027-02-01T00:00:00Z"));

        assertThat(summary.players()).isEmpty();
        assertThat(summary.history()).hasSize(3);
    }

    @Test
    void historyCountsDaysBeforeTheDeadline() {
        List<PostingSummary.Past> history = finder(roh(null)).find(Instant.parse("2026-11-11T01:00:00Z")).history();

        assertThat(history).containsExactly(
                new PostingSummary.Past("이정후", 2023, LocalDate.parse("2024-01-03"), 22,
                        new PostingSummary.Deal(137L, "샌프란시스코", LocalDate.parse("2023-12-12"), 6, 113_000_000L),
                        "/photos/kbo-67341-2023"),
                new PostingSummary.Past("나성범", 2020, LocalDate.parse("2021-01-09"), null, null, null),
                new PostingSummary.Past("김하성", 2020, LocalDate.parse("2021-01-01"), 4,
                        new PostingSummary.Deal(135L, "샌디에이고", LocalDate.parse("2020-12-28"), 4, 28_000_000L),
                        "/photos/mlb-673490-2024"));
    }

    @Test
    void historyPhotoIsTheKboPhotoOfThePostingSeasonAndFollowsTheSwitch() {
        Instant now = Instant.parse("2026-11-11T01:00:00Z");

        // KBO 선수 번호가 있는 선배만, 포스팅된 겨울(season) 시즌 사진. 공식 사진을 끄면 모두 null
        // 계약 구단 MLB 사진이 있으면 그것(김하성 2024 샌디에이고), 없으면 포스팅 시즌 KBO 사진(이정후 2023). 끄면 모두 null
        assertThat(finder(roh(null)).find(now).history()).extracting(PostingSummary.Past::photoUrl)
                .containsExactly("/photos/kbo-67341-2023", null, "/photos/mlb-673490-2024");
        assertThat(finder(roh(null), false).find(now).history()).extracting(PostingSummary.Past::photoUrl)
                .containsExactly(null, null, null);
    }

    @Test
    void historyPhotoIsAHostedSourceFromTheKboPhotoServer() {
        Postings postings = postings(roh(null));

        assertThat(photos(postings, true).source("kbo-67341-2023"))
                .contains("https://kbo.example/2023/67341.jpg");
        assertThat(photos(postings, true).source("mlb-673490-2024"))
                .contains("https://archive.example/2024/673490.jpg");
        assertThat(photos(postings, true).source("kbo-64300-2020")).isEmpty(); // MLB 사진이 있으면 KBO 사진은 받지 않는다
    }

    @Test
    void headlinesComeLatestFirst() {
        when(news.findLatest(eq("노시환"), any())).thenReturn(List.of(
                new NewsArticle("노시환", "https://news.example.kr/2", "h2", "노시환 두 번째 소식", "MLB Trade Rumors",
                        Instant.parse("2026-11-10T03:00:00Z"), Instant.parse("2026-11-10T03:10:00Z")),
                new NewsArticle("노시환", "https://news.example.kr/1", "h1", "노시환 첫 소식", "마이데일리",
                        Instant.parse("2026-11-02T03:00:00Z"), Instant.parse("2026-11-02T03:10:00Z"))));

        PostingSummary.Player roh = finder(roh(null)).find(Instant.parse("2026-11-11T01:00:00Z")).players().getFirst();

        assertThat(roh.news()).containsExactly(
                new PostingSummary.Headline("노시환 두 번째 소식", "https://news.example.kr/2", "MLB Trade Rumors",
                        Instant.parse("2026-11-10T03:00:00Z")),
                new PostingSummary.Headline("노시환 첫 소식", "https://news.example.kr/1", "마이데일리",
                        Instant.parse("2026-11-02T03:00:00Z")));
    }

    @Test
    void headlineCarriesLeadSummaryAndImage() {
        NewsArticle mlbtr = new NewsArticle("노시환", "https://mlb.example.com/1", "h1", "Roh Posted", "MLB Trade Rumors",
                Instant.parse("2026-11-10T03:00:00Z"), Instant.parse("2026-11-10T03:10:00Z"));
        mlbtr.enriched("https://cdn.example.com/roh.jpg", "한화, 노시환 포스팅", "노시환이 포스팅됐다.", "claude-haiku-5-5",
                Instant.parse("2026-11-10T03:20:00Z"));
        NewsArticle mydaily = new NewsArticle("노시환", "https://news.example.kr/2", "h2", "노시환 소식", "마이데일리",
                Instant.parse("2026-11-10T02:00:00Z"), Instant.parse("2026-11-10T02:10:00Z"));
        mydaily.setLeadText("노시환이 협상을 시작했다.");
        when(news.findLatest(eq("노시환"), any())).thenReturn(List.of(mlbtr, mydaily));

        PostingSummary.Player roh = finder(roh(null)).find(Instant.parse("2026-11-11T01:00:00Z")).players().getFirst();

        assertThat(roh.news()).extracting(PostingSummary.Headline::titleKo, PostingSummary.Headline::summary,
                PostingSummary.Headline::imageUrl, PostingSummary.Headline::lead).containsExactly(
                org.assertj.core.groups.Tuple.tuple("한화, 노시환 포스팅", "노시환이 포스팅됐다.", "https://cdn.example.com/roh.jpg",
                        null),
                org.assertj.core.groups.Tuple.tuple(null, null, null, "노시환이 협상을 시작했다."));
    }

    @Test
    void theLatestSummarizedArticleTakesTheLastSlotWhenKoreanNewsFillsTheRest() {
        List<NewsArticle> korean = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            korean.add(new NewsArticle("노시환", "https://news.example.kr/" + i, "k" + i, "노시환 소식 " + i, "마이데일리",
                    Instant.parse("2026-11-10T10:00:00Z").minusSeconds(3600L * i), Instant.parse("2026-11-10T11:00:00Z")));
        }
        NewsArticle english = new NewsArticle("노시환", "https://mlb.example.com/roh", "e", "Roh Posted", "MLB Trade Rumors",
                Instant.parse("2026-11-01T20:40:00Z"), Instant.parse("2026-11-01T21:00:00Z"));
        english.enriched(null, "한화, 노시환 포스팅", "노시환이 포스팅됐다.", "claude-haiku-5-5", Instant.parse("2026-11-11T12:00:00Z"));
        when(news.findLatest(eq("노시환"), any())).thenReturn(korean);
        when(news.findLatestSummarized(eq("노시환"), any())).thenReturn(List.of(english));

        PostingSummary.Player roh = finder(roh(null)).find(Instant.parse("2026-11-11T13:00:00Z")).players().getFirst();

        assertThat(roh.news()).extracting(PostingSummary.Headline::url).containsExactly(
                "https://news.example.kr/0", "https://news.example.kr/1", "https://news.example.kr/2",
                "https://news.example.kr/3", "https://mlb.example.com/roh");
    }

    @Test
    void noSlotIsTakenWhenASummarizedArticleIsAlreadyShown() {
        NewsArticle english = new NewsArticle("노시환", "https://mlb.example.com/roh", "e", "Roh Posted", "MLB Trade Rumors",
                Instant.parse("2026-11-10T20:40:00Z"), Instant.parse("2026-11-10T21:00:00Z"));
        english.enriched(null, "한화, 노시환 포스팅", "노시환이 포스팅됐다.", "claude-haiku-5-5", Instant.parse("2026-11-11T12:00:00Z"));
        List<NewsArticle> latest = new java.util.ArrayList<>(List.of(english));
        for (int i = 0; i < 5; i++) {
            latest.add(new NewsArticle("노시환", "https://news.example.kr/" + i, "k" + i, "노시환 소식 " + i, "마이데일리",
                    Instant.parse("2026-11-10T10:00:00Z").minusSeconds(3600L * i), Instant.parse("2026-11-10T11:00:00Z")));
        }
        when(news.findLatest(eq("노시환"), any())).thenReturn(latest);

        PostingSummary.Player roh = finder(roh(null)).find(Instant.parse("2026-11-11T13:00:00Z")).players().getFirst();

        assertThat(roh.news()).extracting(PostingSummary.Headline::url).containsExactly(
                "https://mlb.example.com/roh", "https://news.example.kr/0", "https://news.example.kr/1",
                "https://news.example.kr/2", "https://news.example.kr/3");
        org.mockito.Mockito.verify(news, org.mockito.Mockito.never()).findLatestSummarized(any(), any());
    }

    @Test
    void headlinesOfRemovedPublishersAreHidden() {
        // 뉴시스는 이용약관(자동 수집 금지)으로 sources.yml에서 뺐다. 이미 모은 기사도 응답에 나오지 않는다
        when(news.findLatest(eq("노시환"), any())).thenReturn(List.of(
                new NewsArticle("노시환", "https://news.example.kr/n", "hn", "노시환 뉴시스 소식", "뉴시스",
                        Instant.parse("2026-11-10T05:00:00Z"), Instant.parse("2026-11-10T05:10:00Z")),
                new NewsArticle("노시환", "https://news.example.kr/m", "hm", "노시환 마이데일리 소식", "마이데일리",
                        Instant.parse("2026-11-10T04:00:00Z"), Instant.parse("2026-11-10T04:10:00Z"))));

        PostingSummary.Player roh = finder(roh(null)).find(Instant.parse("2026-11-11T01:00:00Z")).players().getFirst();

        assertThat(roh.news()).extracting(PostingSummary.Headline::publisher).containsExactly("마이데일리");
    }

    @Test
    void photoIsOurHostedPathAndFollowsTheOfficialPhotosSwitch() {
        Instant now = Instant.parse("2026-11-11T01:00:00Z");

        assertThat(finder(roh(null)).find(now).players().getFirst().photoUrl()).isEqualTo("/photos/kbo-si-hwan-roh");
        assertThat(finder(roh(null), false).find(now).players().getFirst().photoUrl()).isNull();
    }

    @Test
    void nothingToShowIsNull() {
        PostingFinder finder = new PostingFinder(new Postings(new PostingProperties(List.of(), List.of())), news,
                photos(new Postings(new PostingProperties(List.of(), List.of())), true), NEWS);

        assertThat(finder.find(Instant.parse("2026-11-11T01:00:00Z"))).isNull();
    }

    private PostingFinder finder(PostingProperties.Current current) {
        return finder(current, true);
    }

    private PostingFinder finder(PostingProperties.Current current, boolean officialPhotos) {
        Postings postings = postings(current);
        return new PostingFinder(postings, news, photos(postings, officialPhotos), NEWS);
    }

    private static Postings postings(PostingProperties.Current current) {
        List<PostingProperties.Past> history = List.of(
                new PostingProperties.Past("이정후", 2023, "SF", "2024-01-03", "2023-12-12", 6, 113_000_000L, null,
                        67341L, null, null, null),
                new PostingProperties.Past("나성범", 2020, null, "2021-01-09", null, null, null, null, null,
                        null, null, null),
                new PostingProperties.Past("김하성", 2020, "SD", "2021-01-01", "2020-12-28", 4, 28_000_000L, null,
                        64300L, 673490L, 2024, "https://archive.example/2024/673490.jpg"));
        return new Postings(new PostingProperties(List.of(current), history));
    }

    private static PhotoSources photos(Postings postings, boolean on) {
        return new PhotoSources(new RosterProperties(List.of()), postings,
                new KorleaguerProperties(null, null, null, null,
                        new KorleaguerProperties.Images(on, null, "https://kbo.example/{season}/{kboId}.jpg"), null));
    }

    private static PostingProperties.Current roh(PostingProperties.Result result) {
        return new PostingProperties.Current("노시환", "si-hwan-roh", "한화 이글스", "3루수", 2000, List.of("Si-Hwan Roh"), OPENS,
                DEADLINE, null, "https://example.kr/roh.png", result);
    }
}
