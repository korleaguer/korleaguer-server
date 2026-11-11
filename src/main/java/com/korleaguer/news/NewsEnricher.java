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
 *   <li>기사 페이지는 기사마다 PoliteFetcher.getPage로 받는다(robots.txt·Crawl-delay·멈춤을 지킨다).
 *       본문은 요약에만 쓰고 버린다.</li>
 *   <li>한 번에 최근 기사 몇 건만 한다. 기사 탓인 실패(페이지가 없어짐, 본문 없음, 요약 거절)는 세 번까지 다시 해 본다.</li>
 *   <li>기사 탓이 아닌 실패(사이트가 쉬는 중, 요약 서비스 장애·키 문제)는 세지 않고 이번 차례를 멈춘다(다음 차례에 다시).</li>
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

    /** 한 기사를 해 본 결과 */
    enum Outcome {
        /** 요약을 붙였다 */
        DONE,
        /** 기사 탓으로 실패했다(시도로 센다) */
        FAILED,
        /** 기사 탓으로 실패했고, 같은 일이 이어질 수 있어 이번 차례는 멈춘다 */
        FAILED_STOP,
        /** 기사 탓이 아니다(사이트가 쉬는 중, 요약 서비스 문제). 세지 않고 이번 차례를 멈춘다 */
        WAIT
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
            Outcome outcome = enrich(article, now);
            if (outcome == Outcome.WAIT) {
                break;
            }
            articles.save(article);
            if (outcome == Outcome.DONE) {
                done++;
            } else if (outcome == Outcome.FAILED_STOP) {
                break;
            }
        }
        if (done > 0) {
            log.info("news: summarized {} article(s)", done);
        }
        return done;
    }

    Outcome enrich(NewsArticle article, Instant now) {
        try {
            URI uri = URI.create(article.getUrl());
            if (fetcher.paused(uri)) {
                return Outcome.WAIT;
            }
            Optional<PoliteFetcher.Fetched> html = fetcher.getPage(uri);
            if (html.isEmpty() || !html.get().modified() || html.get().body() == null) {
                if (fetcher.paused(uri)) {
                    return Outcome.WAIT; // 제한(429·503)·거절·연결 실패로 사이트가 쉬기 시작했다
                }
                article.enrichFailed(now); // 기사가 없어졌거나(404·410) robots.txt가 막았다
                return Outcome.FAILED;
            }
            ArticlePage page = ArticlePage.parse(html.get().body());
            if (page.text().length() < MIN_BODY) {
                log.warn("news: no article body found at {}", article.getUrl());
                article.enrichFailed(now);
                return Outcome.FAILED;
            }
            Optional<String> summary = summarizer.summarize(article.getSubject(), article.getTitle(), page.text());
            if (summary.isEmpty()) {
                article.enrichFailed(now); // 거절·빈 답
                return Outcome.FAILED;
            }
            article.enriched(page.imageUrl(), summary.get(), summarizer.model(), now);
            return Outcome.DONE;
        } catch (Summarizer.Failure e) {
            log.warn("news: summary service failed for {}: {}", article.getUrl(), e.getMessage());
            if (e.articleProblem()) {
                article.enrichFailed(now);
                return Outcome.FAILED_STOP;
            }
            return Outcome.WAIT;
        } catch (RuntimeException e) {
            log.warn("news: could not summarize {}: {}", article.getUrl(), e.getMessage());
            article.enrichFailed(now);
            return Outcome.FAILED;
        }
    }
}
