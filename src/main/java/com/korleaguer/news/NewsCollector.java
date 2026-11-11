package com.korleaguer.news;

import com.korleaguer.config.DataMode;
import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.posting.PostingCase;
import com.korleaguer.posting.Postings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 포스팅 선수 관련 기사 제목을 모은다(NewsScheduler가 30분마다 부른다).
 * <ul>
 *   <li>지켜볼 포스팅 선수(협상 시작 3일 전 ~ 마감 3일 뒤)가 없으면 아무 요청도 하지 않는다. 샘플 모드도 마찬가지다.</li>
 *   <li>출처(news/sources.yml)마다 피드 한 번(PoliteFetcher가 robots.txt·간격·조건부 요청을 지킨다).
 *       출처에 interval이 있으면 그 간격이 지나야 다시 받는다(큰 피드를 자주 받지 않게).</li>
 *   <li>제목에 선수 이름(한글·영어 표기)이 든 기사만 남긴다. 선수 한 명만 다루는 피드(subject)는 그 선수 기사로 본다.
 *       제목·링크·언론사·발행 시각을 저장하고, 출처가 첫머리를 허락하면(preview: lead) 기사 첫머리 200자 안팎도 둔다.
 *       나머지 본문·사진은 버린다. 요약·대표 사진은 NewsEnricher가 따로 붙인다.</li>
 *   <li>같은 선수에 같은 주소는 한 번만 저장한다. 협상 시작 30일 전보다 오래된 기사는 버린다.</li>
 * </ul>
 */
@Component
public class NewsCollector {

    private static final Logger log = LoggerFactory.getLogger(NewsCollector.class);

    static final Duration OLDEST = Duration.ofDays(30);
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Seoul");

    private final NewsProperties properties;
    private final Postings postings;
    private final PoliteFetcher fetcher;
    private final NewsArticleRepository articles;
    private final DataMode dataMode;
    private final Clock clock;
    // 출처(피드 주소)별 마지막으로 받으러 간 시각. 서버가 다시 뜨면 비어 첫 번에 다시 받는다.
    private final Map<String, Instant> lastVisited = new HashMap<>();

    public NewsCollector(NewsProperties properties, Postings postings, PoliteFetcher fetcher,
                         NewsArticleRepository articles, DataMode dataMode, Clock clock) {
        this.properties = properties;
        this.postings = postings;
        this.fetcher = fetcher;
        this.articles = articles;
        this.dataMode = dataMode;
        this.clock = clock;
    }

    /** 새로 저장한 기사 수 */
    public synchronized int collect() {
        if (dataMode == DataMode.SAMPLE) {
            return 0;
        }
        Instant now = clock.instant();
        List<PostingCase> watched = postings.newsWatch(now);
        if (watched.isEmpty()) {
            return 0;
        }
        int saved = 0;
        for (NewsProperties.Source source : properties.sources()) {
            if (!due(source, now)) {
                continue;
            }
            lastVisited.put(source.feedUrl(), now);
            saved += collect(source, watched, now);
        }
        if (saved > 0) {
            log.info("news: saved {} new headline(s)", saved);
        }
        return saved;
    }

    private int collect(NewsProperties.Source source, List<PostingCase> watched, Instant now) {
        URI uri;
        try {
            uri = URI.create(source.feedUrl().trim());
        } catch (RuntimeException e) {
            log.warn("news: bad feed url for {}: {}", source.publisher(), source.feedUrl());
            return 0;
        }
        Optional<byte[]> body = fetcher.fetch(uri);
        if (body.isEmpty()) {
            return 0;
        }
        List<FeedParser.Item> items;
        try {
            items = FeedParser.parse(body.get(), zone(source));
        } catch (IllegalArgumentException e) {
            log.warn("news: could not read the feed of {}: {}", source.publisher(), e.getMessage());
            return 0;
        }
        int saved = 0;
        for (FeedParser.Item item : items) {
            for (PostingCase posting : watched) {
                boolean subjectFeed = posting.nameKo().equals(trimmed(source.subject()));
                if (!subjectFeed && !mentions(item.title(), posting.searchNames())) {
                    continue;
                }
                Instant published = item.publishedAt() == null || item.publishedAt().isAfter(now)
                        ? now : item.publishedAt();
                if (published.isBefore(posting.windowOpens().minus(OLDEST))) {
                    continue;
                }
                if (save(posting.nameKo(), item, source, published, now)) {
                    saved++;
                }
            }
        }
        return saved;
    }

    private boolean save(String subject, FeedParser.Item item, NewsProperties.Source source, Instant published,
                         Instant now) {
        String url = NewsUrls.normalize(item.link());
        String hash = NewsUrls.sha256(url);
        if (articles.existsBySubjectAndUrlHash(subject, hash)) {
            return false;
        }
        try {
            NewsArticle article = new NewsArticle(subject, url, hash, item.title(), source.publisher(), published, now);
            if (source.lead()) {
                article.setLeadText(item.lead());
            }
            articles.save(article);
            return true;
        } catch (DataIntegrityViolationException e) {
            return false; // 같은 기사를 동시에 저장한 경우
        }
    }

    /** 이 출처를 받을 때인지(interval이 없으면 늘) */
    private boolean due(NewsProperties.Source source, Instant now) {
        Instant last = lastVisited.get(source.feedUrl());
        return last == null || source.interval() == null || !now.isBefore(last.plus(source.interval()));
    }

    private static String trimmed(String text) {
        return text == null ? null : text.trim();
    }

    /** 제목에 이름이 있는지. 대소문자·하이픈·가운뎃점 차이는 무시한다(Si-Hwan Roh = si hwan roh). */
    static boolean mentions(String title, List<String> names) {
        String text = normalize(title);
        return names.stream().map(NewsCollector::normalize).anyMatch(name -> !name.isEmpty() && text.contains(name));
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[-‐‑–—_·.]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static ZoneId zone(NewsProperties.Source source) {
        if (source.zone() == null || source.zone().isBlank()) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(source.zone().trim());
        } catch (DateTimeException e) {
            return DEFAULT_ZONE;
        }
    }
}
