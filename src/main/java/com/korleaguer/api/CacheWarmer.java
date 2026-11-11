package com.korleaguer.api;

import com.korleaguer.config.KorleaguerProperties;
import com.korleaguer.config.ResponseCaches;
import com.korleaguer.config.ResponseCachesCleared;
import com.korleaguer.ingest.CatchUpFinished;
import com.korleaguer.ingest.GameIngest;
import com.korleaguer.posting.PostingCase;
import com.korleaguer.posting.Postings;
import com.korleaguer.support.KoreanTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 화면이 부를 응답을 미리 계산해 캐시에 넣는다. 운영 장비에서는 홈·선수 응답 하나를 처음 만들 때 1~2초 걸려서,
 * 사람이 처음 부를 때 기다리지 않게 하려는 것이다.
 * <ul>
 *   <li>홈(GET /api/home)</li>
 *   <li>선수 페이지 전부(GET /api/players/{id}). 컨트롤러의 시즌 바로 받기(OnDemandSeason)는 거치지 않는다(MLB를 부르지 않는다).</li>
 *   <li>내 선수 카드: 명단 전원 × 7·14·30일(MyPlayersService.card). 어떤 '내 선수' 조합이든 이 카드를 모아 바로 답한다.</li>
 *   <li>비시즌 홈의 선수 카드(GET /api/my, 명단 전원·7일). 웹(OffseasonPlayers)이 부르는 ids 순서·days와 같아야 캐시가 맞는다.</li>
 * </ul>
 * 언제 채우나:
 * <ul>
 *   <li>캐시를 지운 뒤(ResponseCachesCleared): 지운 쪽(수집 등)을 붙잡지 않게 뒤에서, 5초 뒤 지운 캐시를 한 번 더 지우고 채운다. 자동 수집이 켜진 운영에서만
 *       (테스트에서는 뒤에서 도는 계산이 결과를 흔들지 않게 끈다).</li>
 *   <li>따라잡기가 끝날 때(CatchUpFinished, 12분마다·앱 시작 때): 먼저 시각으로 바뀌는 값이 바뀌었는지(한국·미국 날짜면 모두,
 *       포스팅 상태·진행 막대면 홈만), 수집 밖에서 DB에 쓴 것(기사는 홈만)이 있는지 보고 그렇다면 지운다.
 *       그다음 빠진 것을 채운다(하루 만료로 빠진 것 포함).</li>
 * </ul>
 * 이미 들어 있는 응답은 다시 만들지 않는다. 한 번에 하나만 채운다(겹치면 뒤의 것은 캐시에서 바로 끝난다).
 * 하나가 실패해도 나머지는 채운다(실패한 것은 사람이 처음 부를 때 만든다).
 */
@Component
public class CacheWarmer {

    private static final Logger log = LoggerFactory.getLogger(CacheWarmer.class);

    /** 웹 비시즌 홈(web의 src/components/home/OffseasonPlayers.tsx, DAYS)이 /api/my에 넘기는 기간 */
    static final int OFFSEASON_CARD_DAYS = 7;

    /** 내 선수 화면이 고를 수 있는 기간(웹의 7·14·30일 탭). 명단 전원의 카드를 기간마다 채워 둔다 */
    static final List<Integer> CARD_DAYS = List.of(7, 14, 30);

    /** 캐시를 지운 뒤 다시 지우고 채우기까지 기다리는 시간(그사이 끝나는 요청이 넣은 옛 응답을 걷어 낸다) */
    private static final Duration SETTLE = Duration.ofSeconds(5);

    private final BaseballQueryService queryService;
    private final MyPlayersService myPlayers;
    private final ResponseCaches responseCaches;
    private final Postings postings;
    private final ExecutorService executor;
    private final Clock clock;
    private final boolean warmInBackground;
    private final ReentrantLock warming = new ReentrantLock();
    /** 지난 따라잡기 때의 날짜 키(dateKey)·포스팅 키(postingKey). 처음에는 null */
    private String seenDateKey;
    private String seenPostingKey;

    public CacheWarmer(BaseballQueryService queryService, MyPlayersService myPlayers, ResponseCaches responseCaches,
                       Postings postings, @Qualifier("ingestExecutor") ExecutorService executor, Clock clock,
                       KorleaguerProperties properties) {
        this.queryService = queryService;
        this.myPlayers = myPlayers;
        this.responseCaches = responseCaches;
        this.postings = postings;
        this.executor = executor;
        this.clock = clock;
        this.warmInBackground = properties.ingest().scheduleEnabled();
    }

    @EventListener
    public void onCachesCleared(ResponseCachesCleared event) {
        if (warmInBackground) {
            executor.execute(() -> settleThenWarm(event.caches()));
        }
    }

    /**
     * 지운 순간에 계산 중이던 요청(운영 장비에서 길면 1~2초)이 옛 데이터로 만든 응답을 그 뒤에 넣을 수 있다.
     * 그대로 두면 다음 변경까지(비시즌에는 하루까지) 남으므로, 잠깐 기다렸다가 한 번 더 지우고 채운다.
     */
    private void settleThenWarm(List<String> caches) {
        try {
            Thread.sleep(SETTLE.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        responseCaches.clearQuietly(caches);
        warm();
    }

    @EventListener
    public void onCatchUpFinished(CatchUpFinished event) {
        clearIfTimeChanged();
        responseCaches.clearIfDataChanged("수집 밖에서 바뀐 데이터(기사 등)");
        warm();
    }

    /** 시각으로 바뀌는 값이 지난 따라잡기 때와 다르면 캐시를 지운다. 날짜가 바뀌면 모두, 포스팅 값만 바뀌면 홈만 */
    synchronized void clearIfTimeChanged() {
        Instant now = clock.instant();
        String dateKey = dateKey(now);
        String postingKey = postingKey(now);
        boolean dateChanged = seenDateKey != null && !seenDateKey.equals(dateKey);
        boolean postingChanged = seenPostingKey != null && !seenPostingKey.equals(postingKey);
        seenDateKey = dateKey;
        seenPostingKey = postingKey;
        if (dateChanged) {
            responseCaches.clearAll("날짜가 바뀜");
        } else if (postingChanged) {
            responseCaches.clearHome("포스팅 상태·진행 막대가 바뀜");
        }
    }

    /** 모든 응답에 나오는, 날짜로 바뀌는 값: 한국 날짜(D-day, 최근 N일, 'N일째', 나이), 미국 동부 날짜(MLB 날짜, 개막까지 남은 날) */
    static String dateKey(Instant now) {
        return KoreanTime.kstDate(now) + "|" + LocalDate.ofInstant(now, GameIngest.US_EASTERN);
    }

    /** 홈에만 나오는, 시각으로 바뀌는 포스팅 값: 포스팅마다 상태·보이는지·진행 막대(PostingFinder.timeKey) */
    String postingKey(Instant now) {
        StringBuilder key = new StringBuilder();
        for (PostingCase posting : postings.current()) {
            key.append(posting.nameKo()).append('=').append(PostingFinder.timeKey(posting, now)).append('|');
        }
        return key.toString();
    }

    /** 캐시를 채운다. 이미 들어 있는 응답은 다시 만들지 않는다 */
    public void warm() {
        warming.lock();
        try {
            fill();
        } finally {
            warming.unlock();
        }
    }

    private void fill() {
        long started = System.nanoTime();
        int filled = 0;

        HomeResponse home = null;
        try {
            home = queryService.home();
            filled++;
        } catch (RuntimeException e) {
            log.warn("cache warm: home failed", e);
        }

        List<PlayerSummary> players;
        try {
            players = queryService.players();
        } catch (RuntimeException e) {
            log.warn("cache warm: player list failed", e);
            players = List.of();
        }
        for (PlayerSummary player : players) {
            try {
                queryService.player(player.id());
                filled++;
            } catch (RuntimeException e) {
                log.warn("cache warm: player {} failed", player.id(), e);
            }
            // 내 선수 카드: 어떤 조합이든 이 카드들을 모아 바로 답한다
            for (int days : CARD_DAYS) {
                try {
                    myPlayers.card(player.id(), days);
                    filled++;
                } catch (RuntimeException e) {
                    log.warn("cache warm: card {} ({} days) failed", player.id(), days, e);
                }
            }
        }

        if (home != null && home.offseason() != null && !home.offseason().playerIds().isEmpty()) {
            String ids = home.offseason().playerIds().stream().map(String::valueOf).collect(Collectors.joining(","));
            try {
                myPlayers.find(ids, OFFSEASON_CARD_DAYS);
                filled++;
            } catch (RuntimeException e) {
                log.warn("cache warm: off-season cards failed", e);
            }
        }

        log.info("cache warm: {} responses ready in {} ms", filled, (System.nanoTime() - started) / 1_000_000);
    }
}
