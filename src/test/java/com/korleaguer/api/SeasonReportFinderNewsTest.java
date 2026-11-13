package com.korleaguer.api;

import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.MinorGameLogRepository;
import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.domain.PlayerMoveRepository;
import com.korleaguer.domain.PlayerSeasonMetricRepository;
import com.korleaguer.domain.SeasonDatesRepository;
import com.korleaguer.news.NewsProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 성적표 '이번 겨울' 기사: 시즌이 끝난 뒤 기사만, 지금 수집하는 언론사만, 같은 기사는 한 줄 (기사 내용은 지어낸 값) */
class SeasonReportFinderNewsTest {

    private final NewsArticleRepository news = mock(NewsArticleRepository.class);
    private final SeasonReportFinder finder = new SeasonReportFinder(mock(AppearanceRepository.class),
            mock(MinorGameLogRepository.class), mock(PlayerMoveRepository.class), mock(PlayerSeasonMetricRepository.class),
            mock(SeasonDatesRepository.class), news, new NewsProperties(true, "0 */30 * * * *", "korleaguer/test",
            List.of("korleaguer"), List.of(new NewsProperties.Source("마이데일리", "https://a.example/rss", null, null, null,
            "lead"), new NewsProperties.Source("스포츠조선", "https://b.example/rss", null, null, null, "lead"))));

    @Test
    void onlyWinterArticlesFromCurrentPublishersOneLinePerStory() {
        when(news.findLatest(eq("김하성"), any())).thenReturn(List.of(
                article("https://a.example/2", "김하성 필라델피아행?", "마이데일리", "2026-11-11T00:00:00Z"),
                article("https://b.example/2", "김하성 필라델피아행?", "스포츠조선", "2026-11-11T01:00:00Z"),
                article("https://c.example/1", "김하성 FA 소식", "뺀 언론사", "2026-11-10T00:00:00Z"),
                article("https://a.example/1", "김하성 시즌 마지막 경기", "마이데일리", "2026-09-27T20:00:00Z")));

        List<PostingSummary.Headline> headlines = finder.winterNews("김하성", Instant.parse("2026-09-29T04:00:00Z"));

        assertThat(headlines).extracting(PostingSummary.Headline::url).containsExactly("https://a.example/2");
        assertThat(headlines.getFirst().alsoBy()).containsExactly("스포츠조선");
    }

    @Test
    void playerPageNewsUsesTheSameRulesWithoutTheWinterCutAndAtMostLimit() {
        when(news.findLatest(eq("김하성"), any())).thenReturn(List.of(
                article("https://a.example/3", "김하성 FA 선언", "마이데일리", "2026-11-12T00:00:00Z"),
                article("https://a.example/2", "김하성 필라델피아행?", "마이데일리", "2026-11-11T00:00:00Z"),
                article("https://c.example/1", "김하성 FA 소식", "뺀 언론사", "2026-11-10T00:00:00Z"),
                article("https://a.example/1", "김하성 시즌 마지막 경기", "마이데일리", "2026-09-27T20:00:00Z")));

        assertThat(finder.news("김하성", Instant.EPOCH, 10)).extracting(PostingSummary.Headline::url)
                .containsExactly("https://a.example/3", "https://a.example/2", "https://a.example/1");
        assertThat(finder.news("김하성", Instant.EPOCH, 2)).hasSize(2);
    }

    @Test
    void noNameMeansNoArticles() {
        assertThat(finder.winterNews(null, Instant.EPOCH)).isEmpty();
    }

    private static NewsArticle article(String url, String title, String publisher, String publishedAt) {
        return new NewsArticle("김하성", url, url, title, publisher, Instant.parse(publishedAt), Instant.parse(publishedAt));
    }
}
