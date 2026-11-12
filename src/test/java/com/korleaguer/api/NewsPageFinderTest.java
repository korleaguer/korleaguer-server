package com.korleaguer.api;

import com.korleaguer.config.KorleaguerProperties;
import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.ingest.RosterProperties;
import com.korleaguer.news.NewsProperties;
import com.korleaguer.photo.PhotoSources;
import com.korleaguer.posting.PostingProperties;
import com.korleaguer.posting.Postings;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 우리 기사 페이지 한 건: 기사 값, 선수 사진, 같은 선수의 다른 기사(이 기사·뺀 언론사 빼고 늦은 순 5건) */
class NewsPageFinderTest {

    /** 지금 수집하는 언론사(뉴시스는 뺐다고 본다) */
    private static final NewsProperties NEWS = new NewsProperties(false, null, null, null, List.of(
            new NewsProperties.Source("MLB Trade Rumors", "https://example.com/mlbtr", null, null, null, "ai"),
            new NewsProperties.Source("마이데일리", "https://example.com/mydaily", null, null, null, "lead")));

    private final NewsArticleRepository news = mock(NewsArticleRepository.class);
    private final Postings postings = new Postings(new PostingProperties(List.of(
            new PostingProperties.Current("노시환", "si-hwan-roh", "한화 이글스", "3루수", 2000, List.of("Si-Hwan Roh"),
                    "2026-11-02T08:00:00-05:00", "2026-12-01T17:00:00-05:00", null, "https://example.kr/roh.png",
                    null)), List.of()));
    private final PhotoSources photos = new PhotoSources(new RosterProperties(List.of()), postings,
            new KorleaguerProperties(null, null, null, null,
                    new KorleaguerProperties.Images(true, null, "https://kbo.example/{season}/{kboId}.jpg"), null));
    private final NewsPageFinder finder = new NewsPageFinder(news, postings, photos, NEWS);

    @Test
    void articleComesWithItsSummaryPhotoAndThePlayerPhoto() {
        NewsArticle mlbtr = article(7L, "노시환", "Roh Posted", "MLB Trade Rumors", "2026-11-10T03:00:00Z");
        mlbtr.enriched("https://mlbtr.example/roh.jpg", "노시환 포스팅 공시", "한화가 노시환을 포스팅했다.\n마감은 12월 1일이다.",
                "claude-haiku-5-5", Instant.parse("2026-11-10T03:30:00Z"));
        when(news.findById(7L)).thenReturn(Optional.of(mlbtr));
        when(news.findLatest(eq("노시환"), any())).thenReturn(List.of(mlbtr));

        NewsPageResponse page = finder.find(7L);

        assertThat(page.id()).isEqualTo(7L);
        assertThat(page.player()).isEqualTo("노시환");
        assertThat(page.playerPhotoUrl()).isNotNull().isEqualTo(photos.postingPath("si-hwan-roh"));
        assertThat(page.title()).isEqualTo("Roh Posted");
        assertThat(page.titleKo()).isEqualTo("노시환 포스팅 공시");
        assertThat(page.url()).isEqualTo("https://news.example/7");
        assertThat(page.publisher()).isEqualTo("MLB Trade Rumors");
        assertThat(page.publishedAt()).isEqualTo(Instant.parse("2026-11-10T03:00:00Z"));
        assertThat(page.imageUrl()).isEqualTo("https://mlbtr.example/roh.jpg");
        assertThat(page.summary()).isEqualTo("한화가 노시환을 포스팅했다.\n마감은 12월 1일이다.");
        assertThat(page.more()).isEmpty(); // 이 기사는 '다른 기사'에 다시 넣지 않는다
    }

    @Test
    void moreIsTheLatestFiveOthersFromKeptPublishers() {
        List<NewsArticle> latest = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            // 1번은 이 기사, 2번은 뺀 언론사(뉴시스)
            String publisher = i == 2 ? "뉴시스" : "마이데일리";
            latest.add(article(i, "노시환", "노시환 소식 " + i, publisher, "2026-11-10T0" + (8 - i) + ":00:00Z"));
        }
        when(news.findById(1L)).thenReturn(Optional.of(latest.get(1)));
        when(news.findLatest(eq("노시환"), any())).thenReturn(latest);

        NewsPageResponse page = finder.find(1L);

        assertThat(page.more()).extracting(PostingSummary.Headline::id).containsExactly(0L, 3L, 4L, 5L, 6L);
        assertThat(page.more().getFirst().title()).isEqualTo("노시환 소식 0");
    }

    @Test
    void moreLeavesOutCopiesOfThisArticleAndFoldsCopiesIntoOneLine() {
        // 1번(이 기사)과 0번은 언론사만 다른 같은 글, 2·3번도 같은 글이다(이슈 #113)
        NewsArticle original = article(0L, "노시환", "노시환이 유격수?", "MLB Trade Rumors", "2026-11-12T00:50:00Z");
        NewsArticle current = article(1L, "노시환", "노시환이 유격수?", "마이데일리", "2026-11-12T02:00:00Z");
        NewsArticle a = article(2L, "노시환", "노시환 수비 호평", "마이데일리", "2026-11-12T03:00:00Z");
        NewsArticle aCopy = article(3L, "노시환", "노시환 수비 호평", "MLB Trade Rumors", "2026-11-12T03:30:00Z");
        NewsArticle b = article(4L, "노시환", "노시환 타격 분석", "마이데일리", "2026-11-11T23:00:00Z");
        when(news.findById(1L)).thenReturn(Optional.of(current));
        when(news.findLatest(eq("노시환"), any())).thenReturn(List.of(aCopy, a, current, original, b));

        NewsPageResponse page = finder.find(1L);

        assertThat(page.more()).extracting(PostingSummary.Headline::id).containsExactly(2L, 4L);
        assertThat(page.more().getFirst().alsoBy()).containsExactly("MLB Trade Rumors");
        assertThat(page.more().get(1).alsoBy()).isEmpty();
    }

    @Test
    void anOldArticleStillHidesItsCopiesFromMore() {
        // 이 기사가 최근 목록(MORE * 4건) 밖이어도 같은 글을 실은 최근 기사는 '다른 기사'에 넣지 않는다
        NewsArticle old = article(9L, "노시환", "노시환이 유격수?", "마이데일리", "2026-11-01T00:00:00Z");
        NewsArticle copy = article(10L, "노시환", "노시환이 유격수?", "MLB Trade Rumors", "2026-11-12T00:00:00Z");
        when(news.findById(9L)).thenReturn(Optional.of(old));
        when(news.findLatest(eq("노시환"), any())).thenReturn(List.of(copy));

        assertThat(finder.find(9L).more()).isEmpty();
    }

    @Test
    void unknownIdOrDroppedPublisherIsNotFound() {
        when(news.findById(1L)).thenReturn(Optional.empty());
        when(news.findById(2L)).thenReturn(Optional.of(article(2L, "노시환", "노시환 소식", "뉴시스", "2026-11-10T03:00:00Z")));

        assertThatThrownBy(() -> finder.find(1L)).isInstanceOf(NewsNotFoundException.class);
        assertThatThrownBy(() -> finder.find(2L)).isInstanceOf(NewsNotFoundException.class);
    }

    @Test
    void playerOutsideThisWintersPostingsHasNoPhoto() {
        NewsArticle old = article(3L, "김혜성", "김혜성 소식", "마이데일리", "2026-11-10T03:00:00Z");
        old.setLeadText("김혜성이 다저스와 계약했다.");
        when(news.findById(3L)).thenReturn(Optional.of(old));
        when(news.findLatest(eq("김혜성"), any())).thenReturn(List.of(old));

        NewsPageResponse page = finder.find(3L);

        assertThat(page.playerPhotoUrl()).isNull();
        assertThat(page.summary()).isNull();
        assertThat(page.lead()).isEqualTo("김혜성이 다저스와 계약했다.");
    }

    private static NewsArticle article(long id, String subject, String title, String publisher, String publishedAt) {
        NewsArticle article = new NewsArticle(subject, "https://news.example/" + id, "h" + id, title, publisher,
                Instant.parse(publishedAt), Instant.parse(publishedAt).plusSeconds(600));
        ReflectionTestUtils.setField(article, "id", id);
        return article;
    }
}
