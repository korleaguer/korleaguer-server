package com.korleaguer.api.article;

import com.korleaguer.config.KorleaguerProperties;
import com.korleaguer.config.NewsProperties;
import com.korleaguer.config.RosterProperties;
import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.photo.PhotoSources;
import com.korleaguer.posting.PostingProperties;
import com.korleaguer.posting.Postings;
import com.korleaguer.support.PhotoFiles;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 우리 기사 페이지 한 건: 기사 값, 선수 사진, 같은 글을 실은 다른 언론사(alsoBy, 뉴스 탭 목록 한 줄과 같은 규칙),
 * 같은 선수의 다른 기사(이 기사와 그 사본·뺀 언론사 빼고 늦은 순 5건)
 */
class NewsPageFinderTest {

    /** 지금 수집하는 언론사(뉴시스는 뺐다고 본다) */
    private static final NewsProperties NEWS = new NewsProperties(false, null, null, null, List.of(
            new NewsProperties.Source("MLB Trade Rumors", "https://example.com/mlbtr", null, null, null, "ai"),
            new NewsProperties.Source("마이데일리", "https://example.com/mydaily", null, null, null, "lead"),
            new NewsProperties.Source("스포츠조선", "https://example.com/sportschosun", null, null, null, "lead"),
            new NewsProperties.Source("조선일보", "https://example.com/chosun", null, null, null, "lead"),
            new NewsProperties.Source("스포츠동아", "https://example.com/sportsdonga", null, null, null, "lead")));

    private final NewsArticleRepository news = mock(NewsArticleRepository.class);
    private final Postings postings = new Postings(new PostingProperties(List.of(
            new PostingProperties.Current("노시환", "si-hwan-roh", "한화 이글스", "3루수", 2000, List.of("Si-Hwan Roh"),
                    "2026-11-02T08:00:00-05:00", "2026-12-01T17:00:00-05:00", null, 2026, "https://example.kr/roh.png",
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
        stored(mlbtr);

        NewsPageResponse page = finder.find(7L);

        assertThat(page.id()).isEqualTo(7L);
        assertThat(page.player()).isEqualTo("노시환");
        // 홈 포스팅 카드와 같은 사진(현 소속 KBO 구단 공식 사진의 흰 바탕 편집본)
        assertThat(page.playerPhotoUrl()).isEqualTo("/photos/kbo-si-hwan-roh-2026?v=" + PhotoFiles.version("kbo-si-hwan-roh-2026.jpg"))
                .isEqualTo(photos.postingPath(postings.current().getFirst()));
        assertThat(page.title()).isEqualTo("Roh Posted");
        assertThat(page.titleKo()).isEqualTo("노시환 포스팅 공시");
        assertThat(page.url()).isEqualTo("https://news.example/7");
        assertThat(page.publisher()).isEqualTo("MLB Trade Rumors");
        assertThat(page.publishedAt()).isEqualTo(Instant.parse("2026-11-10T03:00:00Z"));
        assertThat(page.imageUrl()).isEqualTo("https://mlbtr.example/roh.jpg");
        assertThat(page.summary()).isEqualTo("한화가 노시환을 포스팅했다.\n마감은 12월 1일이다.");
        assertThat(page.more()).isEmpty(); // 이 기사는 '다른 기사'에 다시 넣지 않는다
        assertThat(page.alsoBy()).isEmpty(); // 같은 글을 실은 다른 언론사가 없다
    }

    @Test
    void alsoByListsTheOtherOutletsThatRanThisArticleInPublishingOrder() {
        // 이 기사(1번)를 스포츠조선·조선일보가 실었다. 뺀 언론사(뉴시스)·주인공이 아닌 사본은 목록처럼 넣지 않는다(이슈 #180)
        String title = "'307억 포기' 노시환이 MLB 유격수?";
        NewsArticle current = article(1L, "노시환", title, "마이데일리", "2026-11-12T00:50:00Z");
        NewsArticle dropped = article(2L, "노시환", title, "뉴시스", "2026-11-12T01:00:00Z");
        NewsArticle sports = article(3L, "노시환", title, "스포츠조선", "2026-11-12T01:10:00Z");
        NewsArticle offTopic = article(4L, "노시환", title, "스포츠동아", "2026-11-12T01:30:00Z");
        offTopic.destinationsTagged("claude-haiku-5-5/v1", Instant.parse("2026-11-12T02:00:00Z"), true);
        NewsArticle chosun = article(5L, "노시환", title, "조선일보", "2026-11-12T02:00:00Z");
        NewsArticle sportsAgain = article(6L, "노시환", title, "스포츠조선", "2026-11-12T03:00:00Z");
        when(news.findById(1L)).thenReturn(Optional.of(current));
        when(news.findLatest(eq("노시환"), any())).thenReturn(List.of(sportsAgain, chosun, offTopic, sports, dropped, current));
        stored(current, dropped, sports, offTopic, chosun, sportsAgain);

        NewsPageResponse page = finder.find(1L);

        assertThat(page.alsoBy()).containsExactly("스포츠조선", "조선일보"); // 낸 순, 두 번 실은 스포츠조선은 한 번
        assertThat(page.more()).isEmpty(); // 사본은 '다른 기사'에도 넣지 않는다
    }

    @Test
    void anOldArticleReadsAroundItselfNotTheLatestArticles() {
        // 뉴스 탭을 넘겨 연 오래된 기사: 최근 기사에는 사본이 없다. 이 기사 앞뒤 NEAR에서 찾는다(이슈 #180)
        NewsArticle old = article(1L, "노시환", "노시환 MLB 포스팅 공시", "마이데일리", "2026-11-02T00:00:00Z");
        NewsArticle copy = article(2L, "노시환", "노시환 MLB 포스팅 공시", "스포츠조선", "2026-11-02T01:00:00Z");
        NewsArticle latest = article(3L, "노시환", "노시환 美 구단 관심", "마이데일리", "2026-11-14T00:00:00Z");
        when(news.findById(1L)).thenReturn(Optional.of(old));
        when(news.findLatest(eq("노시환"), any())).thenReturn(List.of(latest));
        stored(old, copy, latest);

        NewsPageResponse page = finder.find(1L);

        assertThat(page.alsoBy()).containsExactly("스포츠조선");
        assertThat(page.more()).extracting(PostingSummary.Headline::id).containsExactly(3L);
        Instant at = old.getPublishedAt();
        verify(news).findPublishedBetween("노시환", at.minus(SameArticles.NEAR), at.plus(SameArticles.NEAR));
    }

    @Test
    void aCopyLinkedThroughAnotherCopyCountsLikeTheListAndStaysOutOfMore() {
        // 목록은 이어 주는 기사(B)로 A와 NEAR 넘게 떨어진 C까지 한 줄로 묶는다. 기사 페이지도 넓혀 읽어 같게 센다.
        // 최근 기사에는 C만 있어 C가 따로 묶여도, A의 묶음에 든 C는 '다른 기사'에 넣지 않는다
        String title = "노시환 MLB 포스팅 공시";
        NewsArticle a = article(1L, "노시환", title, "마이데일리", "2026-11-08T00:00:00Z");
        NewsArticle b = article(2L, "노시환", title, "스포츠조선", "2026-11-09T12:00:00Z"); // A에서 36시간
        NewsArticle c = article(3L, "노시환", title, "조선일보", "2026-11-11T00:00:00Z"); // A에서 72시간, B에서 36시간
        NewsArticle other = article(4L, "노시환", "노시환 美 구단 관심", "마이데일리", "2026-11-12T04:00:00Z");
        when(news.findById(1L)).thenReturn(Optional.of(a));
        when(news.findLatest(eq("노시환"), any())).thenReturn(List.of(other, c));
        stored(a, b, c, other);

        NewsPageResponse page = finder.find(1L);

        assertThat(SameArticles.group(List.of(a, b, c)).getFirst().alsoBy()).containsExactly("스포츠조선", "조선일보");
        assertThat(page.alsoBy()).containsExactly("스포츠조선", "조선일보"); // 목록 한 줄과 같다
        assertThat(page.more()).extracting(PostingSummary.Headline::id).containsExactly(4L);
    }

    @Test
    void readingAroundStopsAtTheLimitForAnEndlessChain() {
        // 같은 제목이 36시간마다 이어진다(연재 등). 읽을 때마다 하나씩 더 닿지만 MAX_SAME_READS번에서 멈춘다
        List<NewsArticle> chain = new ArrayList<>();
        Instant start = Instant.parse("2026-10-01T00:00:00Z");
        for (int i = 0; i <= NewsPageFinder.MAX_SAME_READS + 1; i++) {
            // 마지막에 닿는 기사는 스포츠조선, 닿지 않는 그다음 기사는 조선일보, 나머지는 이 기사와 같은 마이데일리
            String publisher = i == NewsPageFinder.MAX_SAME_READS ? "스포츠조선"
                    : i == NewsPageFinder.MAX_SAME_READS + 1 ? "조선일보" : "마이데일리";
            chain.add(article(i + 1, "김혜성", "김혜성 다저스 소식", publisher,
                    start.plus(Duration.ofHours(36L * i)).toString()));
        }
        when(news.findById(1L)).thenReturn(Optional.of(chain.getFirst()));
        when(news.findLatest(eq("김혜성"), any())).thenReturn(chain.reversed());
        stored(chain.toArray(NewsArticle[]::new));

        NewsPageResponse page = finder.find(1L);

        assertThat(page.alsoBy()).containsExactly("스포츠조선");
        verify(news, times(NewsPageFinder.MAX_SAME_READS)).findPublishedBetween(eq("김혜성"), any(), any());
    }

    @Test
    void aCopyThatIsNotTheLeadCountsTheLeadOutletAndLeavesOutItsOwn() {
        // 목록에는 요약이 붙은 스포츠조선 기사가 대표로 보이지만 옛 링크로 마이데일리 사본을 열 수도 있다
        String title = "노시환 MLB 포스팅 공시";
        NewsArticle first = article(1L, "노시환", title, "마이데일리", "2026-11-12T00:50:00Z");
        NewsArticle lead = article(2L, "노시환", title, "스포츠조선", "2026-11-12T01:10:00Z");
        lead.enriched(null, null, "한화가 노시환을 포스팅했다.", "claude-haiku-5-5/v4", Instant.parse("2026-11-12T01:20:00Z"));
        NewsArticle late = article(3L, "노시환", title, "조선일보", "2026-11-12T02:00:00Z");
        when(news.findById(1L)).thenReturn(Optional.of(first));
        when(news.findById(2L)).thenReturn(Optional.of(lead));
        when(news.findLatest(eq("노시환"), any())).thenReturn(List.of(late, lead, first));
        stored(first, lead, late);

        assertThat(finder.find(2L).alsoBy()).containsExactly("마이데일리", "조선일보"); // 대표: 목록 한 줄과 같다
        assertThat(finder.find(1L).alsoBy()).containsExactly("스포츠조선", "조선일보");
    }

    @Test
    void moreIsTheLatestFiveOthersFromKeptPublishers() {
        List<NewsArticle> latest = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            // 1번은 이 기사, 2번은 뺀 언론사(뉴시스)
            String publisher = i == 2 ? "뉴시스" : "마이데일리";
            latest.add(article(i, "노시환", "노시환 MLB 소식 " + i, publisher, "2026-11-10T0" + (8 - i) + ":00:00Z"));
        }
        when(news.findById(1L)).thenReturn(Optional.of(latest.get(1)));
        when(news.findLatest(eq("노시환"), any())).thenReturn(latest);
        stored(latest.toArray(NewsArticle[]::new));

        NewsPageResponse page = finder.find(1L);

        assertThat(page.more()).extracting(PostingSummary.Headline::id).containsExactly(0L, 3L, 4L, 5L, 6L);
        assertThat(page.more().getFirst().title()).isEqualTo("노시환 MLB 소식 0");
    }

    @Test
    void moreLeavesOutCopiesOfThisArticleAndFoldsCopiesIntoOneLine() {
        // 1번(이 기사)과 0번은 언론사만 다른 같은 글, 2·3번도 같은 글이다(이슈 #113)
        NewsArticle original = article(0L, "노시환", "노시환이 유격수? 美 소문", "MLB Trade Rumors", "2026-11-12T00:50:00Z");
        NewsArticle current = article(1L, "노시환", "노시환이 유격수? 美 소문", "마이데일리", "2026-11-12T02:00:00Z");
        NewsArticle a = article(2L, "노시환", "노시환 수비 美 호평", "마이데일리", "2026-11-12T03:00:00Z");
        NewsArticle aCopy = article(3L, "노시환", "노시환 수비 美 호평", "MLB Trade Rumors", "2026-11-12T03:30:00Z");
        NewsArticle b = article(4L, "노시환", "노시환 타격 MLB 분석", "마이데일리", "2026-11-11T23:00:00Z");
        when(news.findById(1L)).thenReturn(Optional.of(current));
        when(news.findLatest(eq("노시환"), any())).thenReturn(List.of(aCopy, a, current, original, b));
        stored(aCopy, a, current, original, b);

        NewsPageResponse page = finder.find(1L);

        assertThat(page.alsoBy()).containsExactly("MLB Trade Rumors");
        assertThat(page.more()).extracting(PostingSummary.Headline::id).containsExactly(2L, 4L);
        assertThat(page.more().getFirst().alsoBy()).containsExactly("MLB Trade Rumors");
        assertThat(page.more().get(1).alsoBy()).isEmpty();
    }

    @Test
    void anOldArticleStillHidesItsCopiesFromMore() {
        // 이 기사가 최근 목록(MORE * 4건) 밖이어도(기사가 몰린 날) 하루 뒤 같은 글을 실은 최근 기사는 '다른 기사'에 넣지 않는다
        NewsArticle old = article(9L, "노시환", "노시환이 유격수? 美 소문", "마이데일리", "2026-11-11T00:00:00Z");
        NewsArticle copy = article(10L, "노시환", "노시환이 유격수? 美 소문", "MLB Trade Rumors", "2026-11-12T00:00:00Z");
        when(news.findById(9L)).thenReturn(Optional.of(old));
        when(news.findLatest(eq("노시환"), any())).thenReturn(List.of(copy));
        stored(old, copy);

        NewsPageResponse page = finder.find(9L);

        assertThat(page.more()).isEmpty();
        assertThat(page.alsoBy()).containsExactly("MLB Trade Rumors");
    }

    @Test
    void unknownIdOrDroppedPublisherIsNotFound() {
        when(news.findById(1L)).thenReturn(Optional.empty());
        when(news.findById(2L)).thenReturn(Optional.of(article(2L, "노시환", "노시환 MLB 소식", "뉴시스", "2026-11-10T03:00:00Z")));

        assertThatThrownBy(() -> finder.find(1L)).isInstanceOf(NewsNotFoundException.class);
        assertThatThrownBy(() -> finder.find(2L)).isInstanceOf(NewsNotFoundException.class);
    }

    @Test
    void existsReadsOnlyTheArticleItself() {
        // HEAD /api/news/{id}: find와 같은 기준(없는 번호·뺀 언론사면 없음)으로 기사 한 줄만 읽는다. 웹이 기사 화면을 열 때마다
        // 부르므로 같은 기사 묶음(findPublishedBetween)·다른 기사(findLatest)는 찾지 않는다
        when(news.findById(1L)).thenReturn(Optional.of(article(1L, "노시환", "노시환 MLB 소식", "마이데일리", "2026-11-10T03:00:00Z")));
        when(news.findById(2L)).thenReturn(Optional.of(article(2L, "노시환", "노시환 MLB 소식", "뉴시스", "2026-11-10T03:00:00Z")));
        when(news.findById(3L)).thenReturn(Optional.empty());

        assertThat(finder.exists(1L)).isTrue();
        assertThat(finder.exists(2L)).isFalse(); // 뺀 언론사
        assertThat(finder.exists(3L)).isFalse(); // 없는 번호

        verify(news).findById(1L);
        verify(news).findById(2L);
        verify(news).findById(3L);
        verifyNoMoreInteractions(news);
    }

    @Test
    void playerOutsideThisWintersPostingsHasNoPhoto() {
        NewsArticle old = article(3L, "김혜성", "김혜성 소식", "마이데일리", "2026-11-10T03:00:00Z");
        old.setLeadText("김혜성이 다저스와 계약했다.");
        when(news.findById(3L)).thenReturn(Optional.of(old));
        when(news.findLatest(eq("김혜성"), any())).thenReturn(List.of(old));
        stored(old);

        NewsPageResponse page = finder.find(3L);

        assertThat(page.playerPhotoUrl()).isNull();
        assertThat(page.summary()).isNull();
        assertThat(page.lead()).isEqualTo("김혜성이 다저스와 계약했다.");
    }

    /** DB에 이 기사들이 있다고 본다: findPublishedBetween은 쿼리처럼 그 선수의, 양 끝을 포함한 범위의 기사를 낸 순으로 준다 */
    private void stored(NewsArticle... articles) {
        when(news.findPublishedBetween(any(), any(), any())).thenAnswer(call -> {
            String subject = call.getArgument(0);
            Instant from = call.getArgument(1);
            Instant to = call.getArgument(2);
            return Stream.of(articles)
                    .filter(a -> a.getSubject().equals(subject))
                    .filter(a -> !a.getPublishedAt().isBefore(from) && !a.getPublishedAt().isAfter(to))
                    .sorted(Comparator.comparing(NewsArticle::getPublishedAt).thenComparing(NewsArticle::getId))
                    .toList();
        });
    }

    private static NewsArticle article(long id, String subject, String title, String publisher, String publishedAt) {
        NewsArticle article = new NewsArticle(subject, "https://news.example/" + id, "h" + id, title, publisher,
                Instant.parse(publishedAt), Instant.parse(publishedAt).plusSeconds(600));
        ReflectionTestUtils.setField(article, "id", id);
        return article;
    }
}
