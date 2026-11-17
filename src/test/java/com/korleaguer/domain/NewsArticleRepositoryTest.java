package com.korleaguer.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 다시 요약할 기사 고르기(findOutdatedSummaries): 지금과 다른 판으로 요약했고, 시도가 남고, 오래되지 않은 기사만.
 * 기사 페이지가 같은 글을 찾는 범위 읽기(findPublishedBetween): 그 선수의, 양 끝을 포함한 시각 범위의 기사를 낸 순으로
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NewsArticleRepositoryTest {

    private static final String CURRENT = "claude-haiku-5-5/v2";
    private static final Instant SINCE = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    private NewsArticleRepository news;

    @Test
    void outdatedSummariesAreTheOnesFromAnotherPromptThatCanStillBeRedone() {
        summarized("https://a.example/1", "마이데일리", "claude-haiku-5-5", "2026-11-10T00:00:00Z"); // 옛 판
        summarized("https://a.example/2", "마이데일리", null, "2026-11-09T00:00:00Z"); // 판을 적지 않은 요약
        summarized("https://a.example/3", "마이데일리", CURRENT, "2026-11-08T00:00:00Z"); // 지금 판
        summarized("https://a.example/4", "마이데일리", "claude-haiku-5-5", "2026-09-01T00:00:00Z"); // 너무 오래됨
        summarized("https://a.example/5", "다른 언론사", "claude-haiku-5-5", "2026-11-10T00:00:00Z"); // 요약하지 않는 출처
        NewsArticle gaveUp = summarized("https://a.example/6", "마이데일리", "claude-haiku-5-5", "2026-11-07T00:00:00Z");
        for (int i = 0; i < 3; i++) {
            gaveUp.enrichFailed(Instant.parse("2026-11-12T00:00:00Z")); // 다시 요약하다 세 번 실패
        }
        news.save(new NewsArticle("노시환", "https://a.example/7", "h7", "노시환 소식", "마이데일리",
                Instant.parse("2026-11-11T00:00:00Z"), Instant.parse("2026-11-11T00:10:00Z"))); // 아직 요약 없음

        List<NewsArticle> found = news.findOutdatedSummaries(List.of("마이데일리"), CURRENT, 3, SINCE, PageRequest.of(0, 10));

        assertThat(found).extracting(NewsArticle::getUrl).containsExactly("https://a.example/1", "https://a.example/2");
    }

    @Test
    void publishedBetweenIsThatPlayersArticlesInTheRangeWithBothEndsInPublishingOrder() {
        // SameArticles는 정확히 NEAR 떨어진 기사도 같은 글로 볼 수 있어 범위의 양 끝도 읽어야 한다
        Instant from = Instant.parse("2026-11-10T00:00:00Z");
        Instant to = Instant.parse("2026-11-14T00:00:00Z");
        saved("노시환", "https://b.example/1", from.minusSeconds(1)); // 범위 앞
        saved("노시환", "https://b.example/2", to); // 끝
        saved("노시환", "https://b.example/3", from); // 처음
        saved("노시환", "https://b.example/4", Instant.parse("2026-11-12T00:00:00Z"));
        saved("이정후", "https://b.example/5", Instant.parse("2026-11-12T00:00:00Z")); // 다른 선수
        saved("노시환", "https://b.example/6", to.plusSeconds(1)); // 범위 뒤

        List<NewsArticle> found = news.findPublishedBetween("노시환", from, to);

        assertThat(found).extracting(NewsArticle::getUrl)
                .containsExactly("https://b.example/3", "https://b.example/4", "https://b.example/2");
    }

    private NewsArticle saved(String subject, String url, Instant publishedAt) {
        return news.save(new NewsArticle(subject, url, "b" + url.substring(url.lastIndexOf('/') + 1), subject + " 소식",
                "마이데일리", publishedAt, publishedAt.plusSeconds(600)));
    }

    private NewsArticle summarized(String url, String publisher, String version, String publishedAt) {
        NewsArticle article = new NewsArticle("노시환", url, "h" + url.substring(url.lastIndexOf('/') + 1), "노시환 소식",
                publisher, Instant.parse(publishedAt), Instant.parse(publishedAt).plusSeconds(600));
        article.enriched(null, null, "요약이다.", version, Instant.parse(publishedAt).plusSeconds(1200));
        return news.save(article);
    }
}
