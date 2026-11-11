package com.korleaguer.news;

import com.korleaguer.config.DataMode;
import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 요약을 허락한 출처(news/sources.yml의 preview: ai)의 새 기사에 대표 사진 주소와 한국어 요약을 붙인다.
 * NewsScheduler가 기사를 모은 뒤 부른다.
 * <ul>
 *   <li>요약기가 꺼져 있으면(ANTHROPIC_API_KEY 없음) 기사 페이지도 받지 않는다. 샘플 모드도 마찬가지다.</li>
 *   <li>기사 페이지는 기사마다 한 번 PoliteFetcher로 받는다(robots.txt·Crawl-delay·멈춤을 지킨다). 본문은 요약에만 쓰고 버린다.</li>
 *   <li>한 번에 최근 기사 몇 건만 한다. 실패하면 다음 차례에 다시 해 보고, 세 번 실패하면 그만둔다.</li>
 * </ul>
 */
@Component
public class NewsEnricher {

    private static final Logger log = LoggerFactory.getLogger(NewsEnricher.class);

    static final int PER_RUN = 5;
    static final int MAX_ATTEMPTS = 3;
    static final Duration OLDEST = Duration.ofDays(45);
    /** 이보다 짧은 본문은 요약할 거리가 없다(페이지 구조가 바뀐 경우 등) */
    static final int MIN_BODY = 200;

    private final NewsProperties properties;
    private final PoliteFetcher fetcher;
    private final NewsArticleRepository articles;
    private final Summarizer summarizer;
    private final DataMode dataMode;
    private final Clock clock;

    public NewsEnricher(NewsProperties properties, PoliteFetcher fetcher, NewsArticleRepository articles,
                        Summarizer summarizer, DataMode dataMode, Clock clock) {
        this.properties = properties;
        this.fetcher = fetcher;
        this.articles = articles;
        this.summarizer = summarizer;
        this.dataMode = dataMode;
        this.clock = clock;
    }

    /** 요약을 붙인 기사 수 */
    public synchronized int enrich() {
        if (dataMode == DataMode.SAMPLE || !summarizer.enabled()) {
            return 0;
        }
        Set<String> publishers = properties.sources().stream()
                .filter(NewsProperties.Source::ai)
                .map(NewsProperties.Source::publisher)
                .collect(Collectors.toSet());
        if (publishers.isEmpty()) {
            return 0;
        }
        Instant now = clock.instant();
        List<NewsArticle> todo = articles.findToEnrich(publishers, MAX_ATTEMPTS, now.minus(OLDEST),
                PageRequest.of(0, PER_RUN));
        int done = 0;
        for (NewsArticle article : todo) {
            if (enrich(article, now)) {
                done++;
            }
            articles.save(article);
        }
        if (done > 0) {
            log.info("news: summarized {} article(s)", done);
        }
        return done;
    }

    private boolean enrich(NewsArticle article, Instant now) {
        try {
            Optional<byte[]> html = fetcher.fetch(URI.create(article.getUrl()));
            if (html.isEmpty()) {
                article.enrichFailed(now);
                return false;
            }
            ArticlePage page = ArticlePage.parse(html.get());
            if (page.text().length() < MIN_BODY) {
                log.warn("news: no article body found at {}", article.getUrl());
                article.enrichFailed(now);
                return false;
            }
            Optional<String> summary = summarizer.summarize(article.getSubject(), article.getTitle(), page.text());
            if (summary.isEmpty()) {
                article.enrichFailed(now);
                return false;
            }
            article.enriched(page.imageUrl(), summary.get(), summarizer.model(), now);
            return true;
        } catch (RuntimeException e) {
            log.warn("news: could not summarize {}: {}", article.getUrl(), e.getMessage());
            article.enrichFailed(now);
            return false;
        }
    }
}
