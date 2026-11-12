package com.korleaguer.news;

import com.korleaguer.config.DataMode;
import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.domain.NewsBlockedHost;
import com.korleaguer.domain.NewsBlockedHostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *   <li>요약 지시문을 바꾸면(Summarizer.version) 옛 판으로 만든 요약도 차례로 다시 요약한다. 아직 요약이 없는 기사가 먼저다.
 *       새 요약을 받을 때까지는 옛 요약을 그대로 보이고, 다시 요약하지 못하면 옛 요약을 둔다.</li>
 *   <li>기사 탓이 아닌 실패(사이트가 쉬는 중·429·503·연결 실패, 요약 서비스 장애·키 문제)는 세지 않는다. 요약은 이번 차례를
 *       멈추고(다음 차례에 다시), 페이지 읽기는 그 기사만 건너뛴다. 그 기사만 403·5xx를 주면 기사 탓으로 센다.</li>
 *   <li>샘플 모드에서는 아무것도 받지 않는다.</li>
 * </ul>
 */
@Component
public class NewsEnricher {

    private static final Logger log = LoggerFactory.getLogger(NewsEnricher.class);

    static final int PER_RUN = 10;
    /** 한 번에 읽는 기사 페이지 수(앞부분 출처, 실제로 요청한 것만 센다) */
    static final int PAGES_PER_RUN = 10;
    /** 페이지 읽기 후보를 넉넉히 뽑는다(쉬는 사이트의 기사가 칸을 다 차지하지 않게) */
    static final int PAGE_CANDIDATES = 50;
    /** 기사 페이지를 401·403·451로 막은 사이트는 이만큼 페이지를 읽지 않는다(피드는 계속 받는다) */
    static final Duration PAGE_BLOCK = Duration.ofDays(30);
    static final int MAX_ATTEMPTS = 3;
    static final Duration OLDEST = Duration.ofDays(45);
    /** 이보다 짧은 본문은 요약할 거리가 없다(페이지 구조가 바뀐 경우 등) */
    static final int MIN_BODY = 200;
    private static final java.util.regex.Pattern HANGUL = java.util.regex.Pattern.compile("\\p{IsHangul}");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final NewsProperties properties;
    private final PoliteFetcher fetcher;
    private final PoliteFetcher aiFetcher;
    private final NewsArticleRepository articles;
    private final Summarizer summarizer;
    private final DataMode dataMode;
    private final Clock clock;
    // 기사 페이지를 막은 사이트(호스트)와 다시 읽어 볼 시각(DB news_blocked_host를 처음 쓸 때 읽어 둔다)
    private final Map<String, Instant> pagesBlockedUntil = new HashMap<>();
    private final NewsBlockedHostRepository blockedHosts;
    private boolean blockedHostsLoaded;

    public NewsEnricher(NewsProperties properties, PoliteFetcher fetcher, @Qualifier("aiFetcher") PoliteFetcher aiFetcher,
                        NewsArticleRepository articles, NewsBlockedHostRepository blockedHosts, Summarizer summarizer,
                        DataMode dataMode, Clock clock) {
        this.blockedHosts = blockedHosts;
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
        /** 기사 탓이 아니다(사이트가 쉬는 중, 요약 서비스 문제). 세지 않고 이번 차례를 멈춘다 */
        WAIT,
        /** 그 사이트만 지금 받을 수 없다(쉬는 중·페이지를 막음). 세지 않고 다음 기사로 */
        SKIP
    }

    /** 페이지를 읽어 채우거나 요약을 붙인 기사 수 */
    public synchronized int enrich() {
        if (dataMode == DataMode.SAMPLE) {
            return 0;
        }
        Instant now = clock.instant();
        // 요약하며 앞부분·사진도 채우므로 요약을 먼저 하고, 남은 기사(요약기 꺼짐·실패)만 페이지를 따로 읽는다
        return summarize(now) + readPages(now);
    }

    /** 앞부분만 쓰는 출처의 기사 중 피드가 앞부분·사진을 주지 않은 것의 페이지를 읽는다. 채운 기사 수 */
    int readPages(Instant now) {
        Set<String> publishers = publishers(NewsProperties.Source::lead);
        if (publishers.isEmpty()) {
            return 0;
        }
        List<NewsArticle> todo = articles.findNeedingPage(publishers, MAX_ATTEMPTS, now.minus(OLDEST),
                PageRequest.of(0, PAGE_CANDIDATES));
        int requested = 0;
        int done = 0;
        for (NewsArticle article : todo) {
            if (requested >= PAGES_PER_RUN) {
                break;
            }
            Outcome outcome = readPage(article, now);
            if (outcome == Outcome.WAIT) {
                continue; // 그 사이트만 쉬거나 페이지를 막았다. 칸으로 세지 않고 다른 언론사 기사를 읽는다
            }
            requested++;
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
            String host = uri.getHost();
            if (pageBlocked(host, now) || fetcher.paused(uri)) {
                return Outcome.WAIT;
            }
            PoliteFetcher.Page html = fetcher.getPage(uri);
            if (!html.ok()) {
                int status = html.status();
                if (status == 401 || status == 403 || status == 451) {
                    blockPages(host, status, now); // 페이지를 막은 사이트: 페이지 읽기만 오래 끈다(피드는 계속)
                    return Outcome.WAIT;
                }
                if (status == 429 || status == 503 || (status == 0 && fetcher.paused(uri))) {
                    return Outcome.WAIT;
                }
                article.enrichFailed(now); // 기사가 없어졌거나 robots.txt가 막았거나 이 기사만 5xx
                return Outcome.FAILED;
            }
            ArticlePage page = ArticlePage.parse(html.body());
            article.pageRead(page.description(), page.imageUrl(), now);
            article.retitle(page.betterTitle(article.getTitle()));
            return Outcome.DONE;
        } catch (RuntimeException e) {
            log.warn("news: could not read the page {}: {}", article.getUrl(), e.getMessage());
            article.enrichFailed(now);
            return Outcome.FAILED;
        }
    }

    private boolean pageBlocked(String host, Instant now) {
        if (host == null) {
            return false;
        }
        if (!blockedHostsLoaded) {
            blockedHosts.findAll().forEach(b -> pagesBlockedUntil.put(b.getHost(), b.getBlockedUntil()));
            blockedHostsLoaded = true;
        }
        Instant until = pagesBlockedUntil.get(host);
        return until != null && now.isBefore(until);
    }

    private void blockPages(String host, int status, Instant now) {
        if (host == null) {
            return;
        }
        Instant until = now.plus(PAGE_BLOCK);
        pagesBlockedUntil.put(host, until);
        blockedHosts.save(new NewsBlockedHost(host, until));
        log.warn("news: {} refused an article page ({}); not reading its pages until {}", host, status, until);
    }

    private Set<String> publishers(java.util.function.Predicate<NewsProperties.Source> kind) {
        return properties.sources().stream()
                .filter(kind)
                .map(NewsProperties.Source::publisher)
                .collect(Collectors.toSet());
    }

    /**
     * 기사에 Claude 한국어 요약을 붙인다(요약기가 켜져 있을 때만). 사용자 결정(11-11): 국내·영어 모든 출처의 기사를 요약해
     * 우리 화면에서 읽게 한다. 영어 기사는 한국어 제목도 붙인다. 붙인 기사 수
     */
    int summarize(Instant now) {
        if (!summarizer.enabled()) {
            return 0;
        }
        Set<String> publishers = publishers(s -> s.ai() || s.lead());
        if (publishers.isEmpty()) {
            return 0;
        }
        Set<String> aiPublishers = publishers(NewsProperties.Source::ai);
        // 아직 요약이 없는 기사가 먼저, 그다음 지시문을 바꾸기 전 판으로 요약한 기사(옛 요약은 새 요약을 받을 때까지 그대로)
        List<NewsArticle> todo = new ArrayList<>(articles.findToEnrich(publishers, MAX_ATTEMPTS, now.minus(OLDEST),
                PageRequest.of(0, PAGE_CANDIDATES)));
        todo.addAll(articles.findOutdatedSummaries(publishers, summarizer.version(), MAX_ATTEMPTS, now.minus(OLDEST),
                PageRequest.of(0, PAGE_CANDIDATES)));
        int requested = 0;
        int done = 0;
        for (NewsArticle article : todo) {
            if (requested >= PER_RUN) {
                break;
            }
            Outcome outcome = enrich(article, aiPublishers.contains(article.getPublisher()) ? aiFetcher : fetcher, now);
            if (outcome == Outcome.SKIP) {
                continue; // 그 사이트만 쉬거나 페이지를 막았다. 다른 언론사 기사를 요약한다
            }
            if (outcome == Outcome.WAIT) {
                break; // 요약 서비스 문제: 다음 차례에 다시
            }
            requested++;
            articles.save(article);
            if (outcome == Outcome.DONE) {
                done++;
            }
        }
        if (done > 0) {
            log.info("news: summarized {} article(s)", done);
        }
        return done;
    }

    /**
     * 기사 하나를 요약한다. 같은 페이지로 피드가 주지 않은 앞부분·사진과 부호 빠진 제목도 채운다(페이지를 두 번 받지 않게).
     *
     * @param pages 이 출처를 받을 수집기(AI 요약 출처는 aiFetcher, 앞부분 출처는 우리 이름으로 보는 fetcher)
     */
    Outcome enrich(NewsArticle article, PoliteFetcher pages, Instant now) {
        try {
            URI uri = URI.create(article.getUrl());
            String host = uri.getHost();
            if (pageBlocked(host, now) || pages.paused(uri)) {
                return Outcome.SKIP;
            }
            PoliteFetcher.Page html = pages.getPage(uri);
            if (!html.ok()) {
                int status = html.status();
                if (status == 401 || status == 403 || status == 451) {
                    blockPages(host, status, now); // 페이지를 막은 사이트: 피드는 계속, 페이지만 쉰다
                    return Outcome.SKIP;
                }
                if (status == 429 || status == 503 || (status == 0 && pages.paused(uri))) {
                    return Outcome.SKIP; // 사이트가 천천히 하라고 했거나 연결이 안 된다: 기사 탓이 아니다
                }
                article.enrichFailed(now); // 기사가 없어졌거나(404·410) robots.txt가 막았거나 이 기사만 5xx
                return Outcome.FAILED;
            }
            ArticlePage page = ArticlePage.parse(html.body());
            article.pageRead(page.description(), page.imageUrl(), now);
            article.retitle(page.betterTitle(article.getTitle()));
            if (page.text().length() < MIN_BODY) {
                // 왜 못 찾았는지 다음에 고칠 수 있게 페이지 모양을 남긴다(기사 내용은 남기지 않는다, 이슈 #107)
                log.warn("news: no article body found at {} ({})", article.getUrl(), ArticlePage.outline(html.body()));
                article.enrichFailed(now);
                return Outcome.FAILED;
            }
            // 기사 날짜(한국 날짜)를 함께 준다. 모델이 '올해'·'last February'를 학습 시점의 해로 잘못 옮기지 않게
            Optional<Summarizer.Summary> summary = summarizer.summarize(article.getSubject(), article.getTitle(),
                    LocalDate.ofInstant(article.getPublishedAt(), SEOUL), page.text());
            if (summary.isEmpty()) {
                article.enrichFailed(now); // 거절·빈 답
                return Outcome.FAILED;
            }
            // 국내 기사는 언론사 제목을 그대로 둔다(AI 제목은 영어 기사에만). 사진은 pageRead가 채웠다
            // 다시 요약한 답에 제목 줄이 없으면 있던 한국어 제목을 지킨다(영어 기사)
            String titleKo = HANGUL.matcher(article.getTitle()).find() ? null
                    : Optional.ofNullable(summary.get().titleKo()).orElse(article.getTitleKo());
            String replaced = article.getSummary() == null ? "" : " (replaced " + article.getSummaryModel() + ")";
            String text = summary.get().text();
            article.enriched(null, titleKo, text, summarizer.version(), now);
            // 요약 길이가 본문 분량을 따르는지 보려고 둘 다 남긴다(지시문 v2, 이슈 #103). 본문은 8000자에서 잘린다
            log.info("news: summarized {}: body {} chars -> summary {} chars in {} lines{}", article.getUrl(),
                    page.text().length(), text.length(), text.lines().count(), replaced);
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
