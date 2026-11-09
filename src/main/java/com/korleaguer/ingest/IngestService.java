package com.korleaguer.ingest;

import com.korleaguer.client.MlbBlockedException;
import com.korleaguer.client.MlbClient;
import com.korleaguer.config.AppConfig;
import com.korleaguer.config.KorleaguerProperties;
import com.korleaguer.domain.IngestKind;
import com.korleaguer.domain.IngestLog;
import com.korleaguer.domain.IngestLogRepository;
import com.korleaguer.domain.IngestStatus;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.PlayerSeasonRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 수집 진입점. MLB 공식 Stats API는 여기(수집기)서만 부르고, 화면용 API는 DB만 읽는다.
 * - 실행마다 ingest_log에 한 줄을 남기고(requestsUsed = MLB 요청 수), 끝나면 홈·선수 응답 캐시를 비운다.
 *   스케줄 실행(SCHEDULED)이 MLB를 한 번도 부르지 않았으면 줄을 남기지 않는다.
 * - 한 번에 하나만 돈다. 이미 도는 중이면 IngestAlreadyRunningException을 던진다.
 * - 단계마다 받은 것을 저장한다. MLB가 403/429로 거절하면 그 실행의 남은 단계는 건너뛰고 BLOCKED로 끝난다.
 * - mlb와 sample이 같은 코드를 탄다(MlbClient 구현만 다르다).
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    /** 이 앞뒤 기간에 우리 팀 경기가 있으면 시즌 중으로 본다. */
    private static final int SEASON_MARGIN_DAYS = 3;
    /** 따라잡기 한 번에 선발·교체를 채우는 지난 경기 수(1초에 1회라 약 20초) */
    private static final int LINEUP_GAMES_PER_RUN = 20;

    private final MlbClient client;
    private final IngestWriter writer;
    private final PlayerRefresher playerRefresher;
    private final GameIngest gameIngest;
    private final PlayerRepository players;
    private final PlayerSeasonRepository seasons;
    private final IngestLogRepository ingestLogs;
    private final RosterProperties roster;
    private final int season;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    public IngestService(MlbClient client, IngestWriter writer, PlayerRefresher playerRefresher, GameIngest gameIngest,
                         PlayerRepository players, PlayerSeasonRepository seasons, IngestLogRepository ingestLogs,
                         RosterProperties roster, KorleaguerProperties properties, Clock clock) {
        this.client = client;
        this.writer = writer;
        this.playerRefresher = playerRefresher;
        this.gameIngest = gameIngest;
        this.players = players;
        this.seasons = seasons;
        this.ingestLogs = ingestLogs;
        this.roster = roster;
        this.season = properties.season();
        this.clock = clock;
    }

    /** 명단 선수를 만들고, 프로필·지금 팀·단계·이동 기록을 오늘 이미 받았어도 다시 받는다. */
    @CacheEvict(cacheNames = {AppConfig.HOME_CACHE, AppConfig.PLAYER_CACHE}, allEntries = true)
    public IngestLog syncPlayers() {
        return run(IngestKind.PLAYERS, steps -> {
            ensureRoster();
            steps.add(() -> playersStep(true));
        });
    }

    /** 명단 선수 전원의 한 시즌을 (받은 적이 있어도) 다시 받는다. */
    @CacheEvict(cacheNames = {AppConfig.HOME_CACHE, AppConfig.PLAYER_CACHE}, allEntries = true)
    public IngestLog ingestSeason(int season) {
        return run(IngestKind.SEASON, steps -> {
            ensureRoster();
            steps.add(() -> playersStep(false));
            steps.add(() -> gameIngest.backfillSeason(players.findByMlbIdIsNotNull(), season));
        });
    }

    /** 미국 날짜 usDate와 그 전날의 우리 팀 경기를 다시 받는다(끝난 경기도 박스스코어를 다시 받는다). */
    @CacheEvict(cacheNames = {AppConfig.HOME_CACHE, AppConfig.PLAYER_CACHE}, allEntries = true)
    public IngestLog ingestGames(LocalDate usDate) {
        return run(IngestKind.GAMES, steps -> steps.add(() -> gameIngest.refreshGames(usDate.minusDays(1), usDate, true)));
    }

    /**
     * 따라잡기(스케줄과 앱 시작 때). 필요한 것만 받는다.
     * <ol>
     *   <li>명단 선수 줄 만들기</li>
     *   <li>오늘 아직 안 받은 선수의 정보·이동 기록</li>
     *   <li>이번 시즌을 아직 안 받은 선수의 시즌 백필</li>
     *   <li>refreshGames면: 우리 팀의 오늘·어제(미국 날짜) 경기 갱신, 끝난 경기 재확인</li>
     *   <li>선발·교체를 모르는 지난 경기를 최근 것부터 조금씩(LINEUP_GAMES_PER_RUN) 채우기</li>
     * </ol>
     */
    @CacheEvict(cacheNames = {AppConfig.HOME_CACHE, AppConfig.PLAYER_CACHE}, allEntries = true)
    public IngestLog catchUp(boolean refreshGames) {
        return run(IngestKind.SCHEDULED, steps -> {
            ensureRoster();
            steps.add(() -> playersStep(false));
            steps.add(() -> gameIngest.backfillSeason(withoutSeason(players.findByMlbIdIsNotNull()), season));
            if (refreshGames) {
                LocalDate today = gameIngest.usDate(clock.instant());
                steps.add(() -> gameIngest.refreshGames(today.minusDays(1), today, false));
                steps.add(gameIngest::recheckFinals);
            }
            steps.add(() -> gameIngest.fillLineups(LINEUP_GAMES_PER_RUN));
        });
    }

    /** 선수 한 명의 이번 시즌을 아직 안 받았으면 받는다(선수 페이지를 열 때). */
    @CacheEvict(cacheNames = {AppConfig.HOME_CACHE, AppConfig.PLAYER_CACHE}, allEntries = true)
    public IngestLog loadPlayerSeason(long playerId) {
        return run(IngestKind.PLAYER_SEASON, steps -> {
            Player player = players.findById(playerId).orElse(null);
            if (player != null && player.getMlbId() != null) {
                steps.add(() -> gameIngest.backfillSeason(withoutSeason(List.of(player)), season));
            }
        });
    }

    /** 이번 시즌 경기가 지금 앞뒤 며칠 안에 있는지. 없으면 비시즌으로 보고 경기 갱신을 하지 않는다. */
    public boolean inSeason(Instant now) {
        return gameIngest.hasGamesAround(now, SEASON_MARGIN_DAYS);
    }

    /** 선수의 이번 시즌을 받았는지 */
    public boolean hasSeason(long playerId) {
        return seasons.exists(playerId, season);
    }

    private void ensureRoster() {
        for (RosterProperties.Entry entry : roster.baseball()) {
            writer.ensurePlayer(entry.nameKo(), entry.mlbId());
        }
    }

    private String playersStep(boolean force) {
        PlayerRefresher.Result result = playerRefresher.refresh(force);
        if (result.blocked()) {
            throw new MlbBlockedException(result.describe(), null);
        }
        return result.describe();
    }

    private List<Player> withoutSeason(List<Player> candidates) {
        return candidates.stream().filter(p -> !seasons.exists(p.getId(), season)).toList();
    }

    /**
     * 수집 한 번. plan이 단계(Supplier: 남길 메시지)를 차례로 넣고, 여기서 하나씩 돌린다.
     * 403/429가 나면 남은 단계는 건너뛴다. 그 밖의 예외는 FAILED로 남긴다.
     */
    private IngestLog run(IngestKind kind, Consumer<List<Supplier<String>>> plan) {
        if (!lock.tryLock()) {
            throw new IngestAlreadyRunningException();
        }
        try {
            IngestLog entry = ingestLogs.save(IngestLog.start(kind, clock.instant()));
            long requestsBefore = client.requestCount();
            List<String> messages = new ArrayList<>();
            IngestStatus status = IngestStatus.SUCCESS;
            try {
                List<Supplier<String>> steps = new ArrayList<>();
                plan.accept(steps);
                for (Supplier<String> step : steps) {
                    String message = step.get();
                    if (message != null && !message.isEmpty()) {
                        messages.add(message);
                    }
                }
            } catch (MlbBlockedException e) {
                log.warn("ingest {} stopped: MLB refused ({})", kind, e.getMessage());
                status = IngestStatus.BLOCKED;
                messages.add("MLB가 요청을 거절해(403/429) 중단: " + e.getMessage());
            } catch (RuntimeException e) {
                log.error("ingest {} failed", kind, e);
                status = IngestStatus.FAILED;
                messages.add(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            int requestsUsed = (int) (client.requestCount() - requestsBefore);
            String message = messages.isEmpty() ? "할 일 없음" : String.join(" · ", messages);
            entry.finish(status, message, requestsUsed, clock.instant());
            log.info("ingest {} {} ({} MLB requests): {}", kind, status, requestsUsed, message);
            if (kind == IngestKind.SCHEDULED && status == IngestStatus.SUCCESS && requestsUsed == 0) {
                // 할 일이 없던 스케줄 실행은 남기지 않는다(12분마다 쌓이지 않게).
                ingestLogs.delete(entry);
                return entry;
            }
            return ingestLogs.save(entry);
        } finally {
            lock.unlock();
        }
    }
}
