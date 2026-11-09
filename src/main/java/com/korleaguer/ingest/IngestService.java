package com.korleaguer.ingest;

import com.korleaguer.client.BdlClient;
import com.korleaguer.client.BdlGame;
import com.korleaguer.client.BdlPlayer;
import com.korleaguer.client.BdlStats;
import com.korleaguer.client.BdlTeam;
import com.korleaguer.client.StatsUnavailableException;
import com.korleaguer.config.AppConfig;
import com.korleaguer.domain.Affiliation;
import com.korleaguer.domain.AffiliationRepository;
import com.korleaguer.domain.IngestKind;
import com.korleaguer.domain.IngestLog;
import com.korleaguer.domain.IngestLogRepository;
import com.korleaguer.domain.IngestStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 수집 진입점. 실행마다 ingest_log에 한 줄을 남기고, 끝나면 홈·선수 응답 캐시를 비운다.
 * - 한 번에 하나만 돈다. 이미 도는 중이면 IngestAlreadyRunningException을 던진다.
 * - 경기와 선수 기록을 다 받은 뒤 한 트랜잭션으로 저장한다(도중에 끊기면 아무것도 저장하지 않는다).
 * - 실제 모드와 샘플 모드가 같은 코드를 탄다(BdlClient·MlbClient 구현만 다르다).
 * - 시즌·하루치 수집 끝에는 MLB 공식 API로 선수 단계·이동 기록을 받는다(MovesRefresher, 선수마다 하루 한 번).
 *   이쪽이 실패해도 balldontlie 수집 결과와 상태는 그대로 두고 메시지에만 남긴다.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final BdlClient client;
    private final PlayerResolver resolver;
    private final IngestWriter writer;
    private final MovesRefresher moves;
    private final AffiliationRepository affiliations;
    private final IngestLogRepository ingestLogs;
    private final RosterProperties roster;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    public IngestService(BdlClient client, PlayerResolver resolver, IngestWriter writer, MovesRefresher moves,
                         AffiliationRepository affiliations, IngestLogRepository ingestLogs,
                         RosterProperties roster, Clock clock) {
        this.client = client;
        this.resolver = resolver;
        this.writer = writer;
        this.moves = moves;
        this.affiliations = affiliations;
        this.ingestLogs = ingestLogs;
        this.roster = roster;
        this.clock = clock;
    }

    /** 명단의 선수를 balldontlie 선수와 짝짓고 현재 팀을 저장한다. */
    @CacheEvict(cacheNames = {AppConfig.HOME_CACHE, AppConfig.PLAYER_CACHE}, allEntries = true)
    public IngestLog resolvePlayers() {
        return run(IngestKind.RESOLVE_PLAYERS, () -> {
            LocalDate today = LocalDate.now(clock);
            // yml에 고정한 id는 처음부터 "사용 중"으로 두어 다른 선수가 가져가지 못하게 한다.
            Set<Long> usedIds = roster.baseball().stream()
                    .map(RosterProperties.Entry::bdlPlayerId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(HashSet::new));
            List<String> missing = new ArrayList<>();
            for (RosterProperties.Entry entry : roster.baseball()) {
                writer.ensurePlayer(entry.nameKo());
                Optional<BdlPlayer> found = resolver.resolve(entry, usedIds);
                if (found.isPresent()) {
                    usedIds.add(found.get().id());
                    writer.savePlayer(entry.nameKo(), found.get(), today);
                } else {
                    missing.add(entry.nameKo());
                }
            }
            int resolved = roster.baseball().size() - missing.size();
            String message = "선수 " + resolved + "명 확인"
                    + (missing.isEmpty() ? "" : ", 못 찾음: " + String.join(", ", missing));
            return new Outcome(IngestStatus.SUCCESS, message);
        });
    }

    /** 시즌 전체: 우리 선수 팀들의 경기 + 우리 선수들의 기록(+ 이적 전 팀의 경기) */
    @CacheEvict(cacheNames = {AppConfig.HOME_CACHE, AppConfig.PLAYER_CACHE}, allEntries = true)
    public IngestLog ingestSeason(int season) {
        return run(IngestKind.SEASON, () -> withMoves(seasonGames(season)));
    }

    private Outcome seasonGames(int season) {
        Targets targets = targets();
        if (targets.playerIds().isEmpty()) {
            return Outcome.noPlayers();
        }
        List<BdlGame> games = new ArrayList<>(client.getGames(targets.teamIds(), List.of(season), List.of()));
        List<BdlStats> stats;
        try {
            stats = client.getStats(targets.playerIds(), List.of(season), List.of());
        } catch (StatsUnavailableException e) {
            return saveWithoutStats(games);
        }
        // 이적한 선수가 이전 팀에서 남긴 기록도 버리지 않도록 그 팀의 경기도 받는다.
        List<Long> otherTeamIds = stats.stream()
                .map(BdlStats::team)
                .filter(team -> team != null && team.id() != null)
                .map(BdlTeam::id)
                .filter(id -> !targets.teamIds().contains(id))
                .distinct()
                .toList();
        if (!otherTeamIds.isEmpty()) {
            games.addAll(client.getGames(otherTeamIds, List.of(season), List.of()));
        }
        return saveWithStats(distinctById(games), stats);
    }

    /** 하루치: 그 날짜(UTC 기준 전날·당일)의 우리 팀 경기와 그 경기의 선수 기록 */
    @CacheEvict(cacheNames = {AppConfig.HOME_CACHE, AppConfig.PLAYER_CACHE}, allEntries = true)
    public IngestLog ingestDaily(LocalDate date) {
        return run(IngestKind.DAILY, () -> withMoves(dailyGames(date)));
    }

    private Outcome dailyGames(LocalDate date) {
        Targets targets = targets();
        if (targets.playerIds().isEmpty()) {
            return Outcome.noPlayers();
        }
        List<BdlGame> games = client.getGames(targets.teamIds(), List.of(), List.of(date.minusDays(1), date));
        if (games.isEmpty()) {
            return new Outcome(IngestStatus.NO_GAMES, "경기 없음");
        }
        List<Long> gameIds = games.stream().map(BdlGame::id).filter(Objects::nonNull).toList();
        List<BdlStats> stats;
        try {
            stats = client.getStats(targets.playerIds(), List.of(), gameIds);
        } catch (StatsUnavailableException e) {
            return saveWithoutStats(games);
        }
        return saveWithStats(games, stats);
    }

    // MLB 단계·이동 기록. 실패해도 수집 상태는 그대로 두고 메시지에만 붙인다.
    private Outcome withMoves(Outcome outcome) {
        String note;
        try {
            note = moves.refreshStale().describe();
        } catch (RuntimeException e) {
            log.error("MLB level/moves refresh failed", e);
            note = "MLB 단계·이동 기록 실패: " + e.getClass().getSimpleName();
        }
        return note.isEmpty() ? outcome : new Outcome(outcome.status(), outcome.message() + " · " + note);
    }

    private Outcome saveWithStats(List<BdlGame> games, List<BdlStats> stats) {
        IngestWriter.SaveResult result = writer.saveAll(games, stats);
        return new Outcome(IngestStatus.SUCCESS, describe(result));
    }

    private Outcome saveWithoutStats(List<BdlGame> games) {
        IngestWriter.SaveResult result = writer.saveAll(games, List.of());
        return new Outcome(IngestStatus.STATS_UNAVAILABLE, describe(result) + ", 선수 기록은 요금제 때문에 받지 못함");
    }

    private static String describe(IngestWriter.SaveResult result) {
        StringBuilder message = new StringBuilder()
                .append("경기 ").append(result.savedGames()).append("개, 선수 기록 ").append(result.savedStats()).append("개");
        if (result.skippedGames() > 0) {
            message.append(", 건너뛴 경기 ").append(result.skippedGames()).append("개");
        }
        if (result.skippedStats() > 0) {
            message.append(", 건너뛴 기록 ").append(result.skippedStats()).append("개");
        }
        return message.toString();
    }

    private static List<BdlGame> distinctById(List<BdlGame> games) {
        Map<Long, BdlGame> byId = new LinkedHashMap<>();
        for (BdlGame game : games) {
            byId.putIfAbsent(game.id(), game);
        }
        return new ArrayList<>(byId.values());
    }

    // 짝지어진 선수들의 balldontlie id와 그 선수들의 현재 팀 id
    private Targets targets() {
        List<Affiliation> current = affiliations.findAllCurrent();
        List<Long> playerIds = current.stream()
                .map(a -> a.getPlayer().getBdlPlayerId())
                .filter(Objects::nonNull)
                .toList();
        List<Long> teamIds = current.stream()
                .map(a -> a.getTeam().getBdlTeamId())
                .distinct()
                .toList();
        return new Targets(playerIds, teamIds);
    }

    private IngestLog run(IngestKind kind, Supplier<Outcome> work) {
        if (!lock.tryLock()) {
            throw new IngestAlreadyRunningException();
        }
        try {
            IngestLog entry = ingestLogs.save(IngestLog.start(kind, clock.instant()));
            long requestsBefore = client.requestCount();
            Outcome outcome;
            try {
                outcome = work.get();
            } catch (RuntimeException e) {
                log.error("ingest {} failed", kind, e);
                outcome = new Outcome(IngestStatus.FAILED, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            int requestsUsed = (int) (client.requestCount() - requestsBefore);
            entry.finish(outcome.status(), outcome.message(), requestsUsed, clock.instant());
            log.info("ingest {} {} ({} requests): {}", kind, outcome.status(), requestsUsed, outcome.message());
            return ingestLogs.save(entry);
        } finally {
            lock.unlock();
        }
    }

    private record Targets(List<Long> playerIds, List<Long> teamIds) {
    }

    private record Outcome(IngestStatus status, String message) {

        static Outcome noPlayers() {
            return new Outcome(IngestStatus.FAILED, "확인된 선수가 없습니다. 먼저 /admin/resolve-players를 실행하세요");
        }
    }
}
