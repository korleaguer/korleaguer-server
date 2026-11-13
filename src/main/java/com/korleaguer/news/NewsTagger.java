package com.korleaguer.news;

import com.korleaguer.config.DataMode;
import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.domain.NewsDestination;
import com.korleaguer.domain.NewsDestinationRepository;
import com.korleaguer.news.DestinationTagger.Kind;
import com.korleaguer.posting.PostingCase;
import com.korleaguer.posting.Postings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 기사마다 그 선수의 행선지로 거론된 MLB 구단을 골라 둔다(DestinationTagger, 이슈 #115). 홈 MLB 도전 카드의
 * '행선지로 거론된 구단'이 센다. NewsScheduler가 요약(NewsEnricher) 다음에 부른다.
 * <ul>
 *   <li>요약이 붙은 기사는 요약을 읽고, 요약을 포기한 기사는 제목·앞부분으로 고른다. 요약이 곧 붙을 기사는 기다린다.</li>
 *   <li>최근 기사부터 한 번에 PER_RUN건. 답을 읽을 수 없으면(기사 탓) MAX_ATTEMPTS번까지만 다시 해 본다.
 *       한 번도 고르지 못한 기사는 판이 바뀌면 다시 MAX_ATTEMPTS번(이슈 #148).</li>
 *   <li>AI 서비스가 답하지 못하면(키·사용량·장애) 이번 차례를 멈추고 다음 차례에 다시 한다(세지 않는다).</li>
 *   <li>지시문 판(DestinationTagger.version)이 바뀌거나, 고른 뒤 요약이 붙거나 다시 요약되면 다시 고른다.</li>
 *   <li>이번 겨울 포스팅 선수의 기사는 포스팅 지시문으로, 나머지(명단 선수)는 MLB 선수 지시문으로 고른다(이슈 #139).
 *       포스팅 선수 기사를 먼저 고른다(홈 맨 위 카드).</li>
 *   <li>샘플 모드에서는 하지 않는다.</li>
 * </ul>
 */
@Component
public class NewsTagger {

    private static final Logger log = LoggerFactory.getLogger(NewsTagger.class);

    static final int PER_RUN = 40;
    static final int MAX_ATTEMPTS = 3;
    /** 홈은 최근 7일만 센다. 그보다 넉넉히 고른다 */
    static final Duration OLDEST = Duration.ofDays(30);

    private final NewsArticleRepository articles;
    private final NewsDestinationRepository destinations;
    private final DestinationTagger tagger;
    private final Postings postings;
    private final DataMode dataMode;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public NewsTagger(NewsArticleRepository articles, NewsDestinationRepository destinations, DestinationTagger tagger,
                      Postings postings, DataMode dataMode, Clock clock, PlatformTransactionManager transactions) {
        this.articles = articles;
        this.destinations = destinations;
        this.tagger = tagger;
        this.postings = postings;
        this.dataMode = dataMode;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** 구단을 고른 기사 수(거론된 구단이 없던 기사도 센다) */
    public synchronized int tag() {
        if (dataMode == DataMode.SAMPLE || !tagger.enabled()) {
            return 0;
        }
        Instant now = clock.instant();
        // 빈 목록 in은 DB마다 달라 포스팅 선수가 없으면 어떤 선수 이름과도 다른 빈 이름을 넣는다
        List<String> posting = postings.current().stream().map(PostingCase::nameKo).toList();
        List<String> postingNames = posting.isEmpty() ? List.of("") : posting;
        List<Todo> todo = new ArrayList<>();
        String postingVersion = tagger.version(Kind.POSTING);
        articles.findToTagDestinations(postingNames, postingVersion, MAX_ATTEMPTS, NewsEnricher.MAX_ATTEMPTS,
                        now.minus(OLDEST), PageRequest.of(0, PER_RUN))
                .forEach(a -> todo.add(new Todo(a, Kind.POSTING, postingVersion)));
        if (todo.size() < PER_RUN) {
            String mlbVersion = tagger.version(Kind.MLB);
            articles.findToTagDestinationsBesides(postingNames, mlbVersion, MAX_ATTEMPTS, NewsEnricher.MAX_ATTEMPTS,
                            now.minus(OLDEST), PageRequest.of(0, PER_RUN - todo.size()))
                    .forEach(a -> todo.add(new Todo(a, Kind.MLB, mlbVersion)));
        }
        int done = 0;
        for (Todo next : todo) {
            NewsArticle article = next.article();
            Optional<DestinationTagger.Tagging> picked;
            try {
                picked = tagger.tag(article.getSubject(), next.kind(), title(article), text(article));
            } catch (Summarizer.Failure e) {
                log.warn("news: could not tag destinations, trying again next run: {}", e.getMessage());
                break;
            }
            if (picked.isEmpty()) {
                article.destinationsFailed(next.version());
                articles.save(article);
                continue;
            }
            save(article, picked.get(), next.version(), now);
            done++;
        }
        if (done > 0) {
            log.info("news: tagged destinations on {} article(s)", done);
        }
        return done;
    }

    /** 고를 기사 하나와 그 선수의 처지, 그 처지의 지시문 판 */
    private record Todo(NewsArticle article, Kind kind, String version) {
    }

    /** 전에 고른 구단을 지우고 새로 고른 구단을 둔다(한 번에) */
    private void save(NewsArticle article, DestinationTagger.Tagging picked, String version, Instant now) {
        transaction.executeWithoutResult(status -> {
            destinations.deleteByArticle(article.getId());
            destinations.flush();
            for (DestinationTagger.Destination d : picked.destinations()) {
                destinations.save(new NewsDestination(article.getId(), d.team(), d.stance()));
            }
            // 주인공이 아닌 기사는 목록에서 뺀다(이슈 #129). 답에 판단이 없으면 null(보인다)
            Boolean offTopic = picked.aboutPlayer() == null ? null : !picked.aboutPlayer();
            article.destinationsTagged(version, now, offTopic);
            articles.save(article);
        });
    }

    /** 영어 기사는 한국어 제목과 원래 제목을 함께 */
    static String title(NewsArticle article) {
        return article.getTitleKo() == null ? article.getTitle() : article.getTitleKo() + " (" + article.getTitle() + ")";
    }

    /** 요약, 없으면 앞부분 */
    static String text(NewsArticle article) {
        return article.getSummary() != null ? article.getSummary() : article.getLeadText();
    }
}
