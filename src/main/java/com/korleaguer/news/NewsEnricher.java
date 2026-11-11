package com.korleaguer.news;

import com.korleaguer.config.DataMode;
import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * 피드만으로는 모자란 것을 기사 페이지에서 채운다. NewsScheduler가 기사를 모은 뒤 부른다.
 * <ul>
 *   <li>앞부분만 쓰는 출처: 피드가 앞부분이나 사진을 주지 않은 기사(뉴스 사이트맵 등)는 페이지를 한 번 읽어
 *       og:description(앞부분)과 og:image(대표 사진 원본 주소)를 채운다. robots.txt는 우리 수집기 이름으로 본다.</li>
 *   <li>AI 요약 출처(preview: ai): 요약기가 켜져 있으면(ANTHROPIC_API_KEY) 페이지 본문으로 한국어 제목·요약을 만들고
 *       대표 사진 주소를 둔다. robots.txt는 Claude 계열 이름까지 본다(aiFetcher). 본문은 요약에만 쓰고 버린다.</li>
 *   <li>한 번에 최근 기사 몇 건만 한다. 기사 탓인 실패(페이지가 없어짐, 본문 없음, 요약 거절)는 세 번까지 다시 해 본다.</li>
 *   <li>기사 탓이 아닌 실패(사이트가 쉬는 중·429·503·연결 실패, 요약 서비스 장애·키 문제)는 세지 않는다. 요약은 이번 차례를
 *       멈추고(다음 차례에 다시), 페이지 읽기는 그 기사만 건너뛴다. 그 기사만 403·5xx를 주면 기사 탓으로 센다.</li>
 *   <li>샘플 모드에서는 아무것도 받지 않는다.</li>
 * </ul>
 */
@Component
public class NewsEnricher {

    private static final Logger log = LoggerFactory.getLogger(NewsEnricher.class);

    static final int PER_RUN = 5;
    /** 한 번에 읽는 기사 페이지 수(앞부분 출처). 언론사가 여럿이라 사이트마다 몇 건씩이다 */
    static final int PAGES_PER_RUN = 10;
    static final int MAX_ATTEMPTS = 3;
    static final Duration OLDEST = Duration.ofDays(45);
    /** 이보다 짧은 본문은 요약할 거리가 없다(페이지 구조가 바뀐 경우 등) */
    static final int MIN_BODY = 200;

    private final NewsProperties properties;
    private final PoliteFetcher fetcher;
    private final PoliteFetcher aiFetcher;
    private final NewsArticleRepository articles;
    private final Summarizer summarizer;
    private final DataMode dataMode;
    private final Clock clock;

    public NewsEnricher(NewsProperties properties, PoliteFetcher fetcher, @Qualifier("aiFetcher") PoliteFetcher aiFetcher,
                        NewsArticleRepository articles, Summarizer summarizer, DataMode dataMode, Clock clock) {
        this.properties = properties;
        this.fetcher = fetcher;
        this.aiFetcher = aiFetcher;
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
        /** 기사 탓으로 실패했고 그 요청으로 사이트가 쉬기 시작해 이번 차례는 멈춘다 */
        FAILED_STOP,
        /** 기사 탓이 아니다(사이트가 쉬는 중, 요약 서비스 문제). 세지 않고 이번 차례를 멈춘다 */
        WAIT
    }

    /** 페이지를 읽어 채우거나 요약을 붙인 기사 수 */
    public synchronized int enrich() {
        if (dataMode == DataMode.SAMPLE) {
            return 0;
        }
        Instant now = clock.instant();
        return readPages(now) + summarize(now);
    }

    /** 앞부분만 쓰는 출처의 기사 중 피드가 앞부분·사진을 주지 않은 것의 페이지를 읽는다. 채운 기사 수 */
    int readPages(Instant now) {
        Set<String> publishers = publishers(false);
        if (publishers.isEmpty()) {
            return 0;
        }
        List<NewsArticle> todo = articles.findNeedingPage(publishers, MAX_ATTEMPTS, now.minus(OLDEST),
                PageRequest.of(0, PAGES_PER_RUN));
        int done = 0;
        for (NewsArticle article : todo) {
            Outcome outcome = readPage(article, now);
            if (outcome == Outcome.WAIT) {
                continue; // 그 사이트만 쉬는 중이다. 다른 언론사 기사는 계속 읽는다
            }
            articles.save(article);
            if (outcome == Outcome.DONE) {
                done++;
            }
        }
        if (done > 0) {
            log.info("news: read {} article page(s) for leads and photos", done);
        }
        return done;
    }

    Outcome readPage(NewsArticle article, Instant now) {
        try {
            URI uri = URI.create(article.getUrl());
            if (fetcher.paused(uri)) {
                return Outcome.WAIT;
            }
            PoliteFetcher.Page html = fetcher.getPage(uri);
            if (!html.ok()) {
                int status = html.status();
                if (status == 429 || status == 503 || (status == 0 && fetcher.paused(uri))) {
                    return Outcome.WAIT;
                }
                article.enrichFailed(now); // 기사가 없어졌거나 robots.txt가 막았거나 이 기사만 403·5xx
                return Outcome.FAILED;
            }
            ArticlePage page = ArticlePage.parse(html.body());
            article.pageRead(page.description(), page.imageUrl(), now);
            return Outcome.DONE;
        } catch (RuntimeException e) {
            log.warn("news: could not read the page {}: {}", article.getUrl(), e.getMessage());
            article.enrichFailed(now);
            return Outcome.FAILED;
        }
    }

    private Set<String> publishers(boolean ai) {
        return properties.sources().stream()
                .filter(s -> s.ai() == ai)
                .map(NewsProperties.Source::publisher)
                .collect(Collectors.toSet());
    }

    /** AI 요약 출처의 새 기사에 한국어 제목·요약을 붙인다(요약기가 켜져 있을 때만). 붙인 기사 수 */
    int summarize(Instant now) {
        if (!summarizer.enabled()) {
            return 0;
        }
        Set<String> publishers = publishers(true);
        if (publishers.isEmpty()) {
            return 0;
        }
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
            if (aiFetcher.paused(uri)) {
                return Outcome.WAIT;
            }
            PoliteFetcher.Page html = aiFetcher.getPage(uri);
            if (!html.ok()) {
                int status = html.status();
                if (status == 429 || status == 503 || (status == 0 && aiFetcher.paused(uri))) {
                    return Outcome.WAIT; // 사이트가 천천히 하라고 했거나 연결이 안 된다: 기사 탓이 아니다
                }
                article.enrichFailed(now); // 기사가 없어졌거나(404·410) robots.txt가 막았거나 이 기사만 403·5xx
                // 이 기사 요청으로 사이트가 쉬기 시작했으면(403·5xx) 이번 차례는 멈춘다. 세 번 세면 그만둔다
                return aiFetcher.paused(uri) ? Outcome.FAILED_STOP : Outcome.FAILED;
            }
            ArticlePage page = ArticlePage.parse(html.body());
            if (page.text().length() < MIN_BODY) {
                log.warn("news: no article body found at {}", article.getUrl());
                article.enrichFailed(now);
                return Outcome.FAILED;
            }
            Optional<Summarizer.Summary> summary = summarizer.summarize(article.getSubject(), article.getTitle(),
                    page.text());
            if (summary.isEmpty()) {
                article.enrichFailed(now); // 거절·빈 답
                return Outcome.FAILED;
            }
            article.enriched(page.imageUrl(), summary.get().titleKo(), summary.get().text(), summarizer.model(), now);
            return Outcome.DONE;
        } catch (Summarizer.Failure e) {
            log.warn("news: summary service failed for {}: {}", article.getUrl(), e.getMessage());
            return Outcome.WAIT;
        } catch (RuntimeException e) {
            log.warn("news: could not summarize {}: {}", article.getUrl(), e.getMessage());
            article.enrichFailed(now);
            return Outcome.FAILED;
        }
    }
}
