package com.korleaguer.news;

import com.korleaguer.config.DataMode;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.ingest.RosterProperties;
import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.posting.PostingCase;
import com.korleaguer.posting.Postings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 포스팅 선수와 명단 선수의 관련 기사 제목을 모은다(NewsScheduler가 30분마다 부른다).
 * <ul>
 *   <li>지켜볼 선수는 포스팅 선수(협상 시작 3일 전 ~ 마감 3일 뒤)와 명단 선수(roster/baseball.yml, 국내 리그로 돌아간 선수는
 *       빼고, 이슈 #119: 비시즌 성적표의 '이번 겨울' 기사)다. 지켜볼 선수가 없거나 샘플 모드면 아무 요청도 하지 않는다.</li>
 *   <li>출처(news/sources.yml)마다 피드 한 번(PoliteFetcher가 robots.txt·간격·조건부 요청을 지킨다).
 *       출처에 interval이 있으면 그 간격이 지나야 다시 받는다(큰 피드를 자주 받지 않게).</li>
 *   <li>제목에 선수 이름(한글·영어 표기)이 든 기사만 남긴다. 선수 한 명만 다루는 피드(subject)는 그 선수 기사로 본다.
 *       제목·링크·언론사·발행 시각과 피드가 주는 사진 주소를 저장하고, 출처가 첫머리를 허락하면(preview: lead) 기사 첫머리
 *       200자 안팎도 둔다. 나머지 본문은 버린다. 피드에 없는 앞부분·사진과 AI 요약은 NewsEnricher가 기사 페이지에서 붙인다.</li>
 *   <li>AI 요약 출처(preview: ai)는 Claude 계열 이름까지 robots.txt가 허용할 때만 받는다(aiFetcher).</li>
 *   <li>같은 선수에 같은 주소는 한 번만 저장한다. 포스팅 선수는 협상 시작 30일 전, 명단 선수는 지금부터 30일 전보다
 *       오래된 기사는 버린다.</li>
 * </ul>
 */
@Component
public class NewsCollector {

    private static final Logger log = LoggerFactory.getLogger(NewsCollector.class);

    static final Duration OLDEST = Duration.ofDays(30);
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Seoul");

    private final NewsProperties properties;
    private final Postings postings;
    private final RosterProperties roster;
    private final PlayerRepository players;
    private final PoliteFetcher fetcher;
    private final PoliteFetcher aiFetcher;
    private final NewsArticleRepository articles;
    private final DataMode dataMode;
    private final Clock clock;
    // 출처(피드 주소)별 마지막으로 받으러 간 시각. 서버가 다시 뜨면 비어 첫 번에 다시 받는다.
    private final Map<String, Instant> lastVisited = new HashMap<>();

    /**
     * @param fetcher   앞부분만 쓰는 출처용(robots.txt를 우리 이름으로 본다)
     * @param aiFetcher AI 요약 출처용(Claude 계열 이름도 본다)
     */
    public NewsCollector(NewsProperties properties, Postings postings, RosterProperties roster, PlayerRepository players,
                         PoliteFetcher fetcher, @Qualifier("aiFetcher") PoliteFetcher aiFetcher,
                         NewsArticleRepository articles, DataMode dataMode, Clock clock) {
        this.properties = properties;
        this.postings = postings;
        this.roster = roster;
        this.players = players;
        this.fetcher = fetcher;
        this.aiFetcher = aiFetcher;
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
        List<Subject> watched = subjects(now);
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

    /**
     * 기사를 모을 선수 한 명.
     *
     * @param nameKo      저장할 이름(기사의 subject)
     * @param searchNames 제목에서 찾을 이름(한글·영어 표기)
     * @param oldest      이보다 먼저 나온 기사는 버린다
     */
    record Subject(String nameKo, List<String> searchNames, Instant oldest) {
    }

    /** 지켜볼 선수: 포스팅 선수 먼저, 그다음 명단 선수(같은 이름이면 포스팅 쪽). 명단 선수의 영어 이름은 MLB에서 받은 이름 */
    List<Subject> subjects(Instant now) {
        Map<String, Subject> out = new LinkedHashMap<>();
        for (PostingCase posting : postings.newsWatch(now)) {
            out.put(posting.nameKo(), new Subject(posting.nameKo(), posting.searchNames(),
                    posting.windowOpens().minus(OLDEST)));
        }
        List<RosterProperties.Entry> entries = roster.baseball() == null ? List.of() : roster.baseball();
        if (entries.isEmpty()) {
            return List.copyOf(out.values());
        }
        Map<String, String> english = new HashMap<>();
        for (Player player : players.findByMlbIdIsNotNull()) {
            if (player.getNameKo() != null && player.getNameEn() != null) {
                english.put(player.getNameKo(), player.getNameEn());
            }
        }
        for (RosterProperties.Entry entry : entries) {
            // 국내 리그로 돌아간 선수는 해외파 소식이 아니다(국내 기사만 쌓인다)
            if (entry.nameKo() == null || entry.returnedKo() != null) {
                continue;
            }
            String nameKo = entry.nameKo().trim();
            List<String> names = new ArrayList<>(List.of(nameKo));
            if (english.containsKey(nameKo)) {
                names.add(english.get(nameKo));
            }
            out.putIfAbsent(nameKo, new Subject(nameKo, List.copyOf(names), now.minus(OLDEST)));
        }
        return List.copyOf(out.values());
    }

    private int collect(NewsProperties.Source source, List<Subject> watched, Instant now) {
        URI uri;
        try {
            uri = URI.create(source.feedUrl().trim());
        } catch (RuntimeException e) {
            log.warn("news: bad feed url for {}: {}", source.publisher(), source.feedUrl());
            return 0;
        }
        Optional<byte[]> body = (source.ai() ? aiFetcher : fetcher).fetch(uri);
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
        if (items.isEmpty()) {
            // 피드·사이트맵 모양이 바뀌었거나(색인 파일, 이름 공간 등) 비었다. 0건이 이어지면 sources.yml을 고친다
            log.warn("news: no items in the feed of {} ({})", source.publisher(), source.feedUrl());
            return 0;
        }
        int saved = 0;
        for (FeedParser.Item item : items) {
            for (Subject subject : watched) {
                boolean subjectFeed = subject.nameKo().equals(trimmed(source.subject()));
                if (!subjectFeed && !mentions(item.title(), subject.searchNames())) {
                    continue;
                }
                Instant published = item.publishedAt() == null || item.publishedAt().isAfter(now)
                        ? now : item.publishedAt();
                if (published.isBefore(subject.oldest())) {
                    continue;
                }
                if (save(subject.nameKo(), item, source, published, now)) {
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
            // 첫머리·사진을 넣기 전에 모은 기사는 피드에 아직 있으면 채운다
            boolean leadToFill = source.lead() && item.lead() != null;
            if (leadToFill || item.imageUrl() != null) {
                articles.findBySubjectAndUrlHash(subject, hash)
                        .filter(a -> (leadToFill && a.getLeadText() == null) || (item.imageUrl() != null && a.getImageUrl() == null))
                        .ifPresent(a -> {
                            if (leadToFill && a.getLeadText() == null) {
                                a.setLeadText(item.lead());
                            }
                            if (item.imageUrl() != null && a.getImageUrl() == null) {
                                a.setImageUrl(item.imageUrl());
                            }
                            articles.save(a);
                        });
            }
            return false;
        }
        try {
            NewsArticle article = new NewsArticle(subject, url, hash, item.title(), source.publisher(), published, now);
            if (source.lead()) {
                article.setLeadText(item.lead());
            }
            article.setImageUrl(item.imageUrl());
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
