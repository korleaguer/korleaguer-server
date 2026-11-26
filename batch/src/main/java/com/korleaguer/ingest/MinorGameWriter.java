package com.korleaguer.ingest;

import com.korleaguer.client.MlbGameLogSplit;
import com.korleaguer.client.MlbMinorGameLog;
import com.korleaguer.client.MlbMinorGames;
import com.korleaguer.client.MlbScheduleGame;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.domain.MinorGameLog;
import com.korleaguer.domain.MinorGameLogRepository;
import com.korleaguer.domain.MinorGameSchedule;
import com.korleaguer.domain.MinorSeason;
import com.korleaguer.domain.MinorSeasonRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.support.GameStatuses;
import com.korleaguer.support.KoreanTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.korleaguer.ingest.MlbValues.date;
import static com.korleaguer.ingest.MlbValues.teamId;
import static com.korleaguer.ingest.MlbValues.teamName;
import static com.korleaguer.ingest.MlbValues.toBatting;
import static com.korleaguer.ingest.MlbValues.toPitching;

/**
 * 선수의 마이너 경기별 기록과 그 경기 일정(시작 시각·점수)을 DB에 저장한다.
 * HTTP 호출은 수집기(PlayerRefresher)가 먼저 다 받아 오고, 저장만 여기서 트랜잭션으로 묶는다.
 * 값이 이상한 줄(마이너가 아님, 번호·날짜가 없음)은 그 줄만 건너뛴다.
 */
@Component
@Transactional
public class MinorGameWriter {

    private static final Logger log = LoggerFactory.getLogger(MinorGameWriter.class);
    /**
     * 끝나 점수를 받지 못한 마이너 경기의 일정을 다시 묻는 기간(공식 날짜부터 며칠). 경기 중에 받은 경기·하루 이틀 뒤 이어 한 중단
     * 경기는 이 안에 끝난다. 그 뒤에도 안 끝난 경기(이어 하지 않은 중단 경기·응답에 없던 경기)는 더 묻지 않는다
     */
    static final int MINOR_RECHECK_DAYS = 7;

    private final PlayerRepository players;
    private final MinorSeasonRepository minorSeasons;
    private final MinorGameLogRepository minorGameLogs;

    public MinorGameWriter(PlayerRepository players, MinorSeasonRepository minorSeasons,
                           MinorGameLogRepository minorGameLogs) {
        this.players = players;
        this.minorSeasons = minorSeasons;
        this.minorGameLogs = minorGameLogs;
    }

    /**
     * 선수의 한 시즌 마이너 경기별 기록을 새로 받은 것으로 통째로 바꾸고, 마이너 경기 수(MinorSeason)도 그 기록으로 센다.
     * 같은 경기(gamePk)의 타격·투구는 한 줄로 합친다. 마이너가 아닌 줄(sport.id 11~16 밖)·번호나 날짜가 없는 줄은 건너뛴다.
     * 이미 받아 둔 일정 값(시작 시각·점수, saveMinorSchedule)은 같은 경기 줄에 옮겨 담는다(끝난 경기를 다시 묻지 않게).
     *
     * @return 일정을 물어야 할 경기 번호(오래된 경기부터). 아직 한 번도 묻지 않은 경기와, 물었지만 끝나 점수를 받지 못한 경기 중
     * 공식 날짜가 MINOR_RECHECK_DAYS 안인 경기다(중단된 채 끝나지 않은 경기·응답에 없던 경기를 끝없이 묻지 않게)
     */
    public List<Long> saveMinorGameLog(long playerId, int season, MlbMinorGameLog log, Instant fetchedAt) {
        MlbMinorGames counts = log.counts();
        MinorSeason row = minorSeasons.find(playerId, season)
                .orElseGet(() -> new MinorSeason(players.getReferenceById(playerId), season));
        row.update(counts.hitting(), counts.pitching(), fetchedAt);
        minorSeasons.save(row);

        Map<Long, MinorGameSchedule> known = minorGameLogs.findSeason(List.of(playerId), season).stream()
                .filter(game -> game.getSchedule() != null)
                .collect(Collectors.toMap(MinorGameLog::getGamePk, MinorGameLog::getSchedule, (a, b) -> a));
        minorGameLogs.deleteSeason(playerId, season);
        Player player = players.getReferenceById(playerId);
        Map<Long, MinorGameLog> byGame = new LinkedHashMap<>();
        log.hitting().forEach(split -> minorLine(byGame, player, season, split)
                .ifPresent(line -> line.updateBatting(toBatting(split.stat()))));
        log.pitching().forEach(split -> minorLine(byGame, player, season, split)
                .ifPresent(line -> line.updatePitching(toPitching(split.stat()), split.stat() == null ? null
                        : split.stat().holds(), split.stat() == null ? null : split.stat().blownSaves())));
        byGame.values().forEach(line -> line.updateSchedule(known.get(line.getGamePk())));
        minorGameLogs.saveAll(byGame.values());
        LocalDate recheckFrom = UtcDay.of(fetchedAt).minusDays(MINOR_RECHECK_DAYS);
        return byGame.values().stream()
                .filter(line -> line.getSchedule() == null
                        || (!line.getSchedule().isSettled() && !line.getGameDate().isBefore(recheckFrom)))
                .sorted(Comparator.comparing(MinorGameLog::getGameDate).thenComparing(MinorGameLog::getGamePk))
                .map(MinorGameLog::getGamePk)
                .toList();
    }

    /**
     * 마이너 경기 일정(시작 시각·상태·점수·팀 지명)을 선수의 그 시즌 경기 줄에 적는다. 선수 팀이 홈인지 원정인지는 팀 번호로
     * 가리고, 번호가 맞지 않으면 경기별 기록의 홈 여부를 쓴다. 둘 다 모르면 점수·지명은 비운다.
     * 같은 경기가 두 번 오면(연기) 끝난 쪽을, 둘 다 아니면 뒤에 온 쪽을 쓴다.
     * 물은 경기(asked) 중 응답에 없거나 시작 시각을 읽을 수 없는 경기는 지난 값을 두고, 지난 값도 없으면 물었다는 표시만 남긴다.
     *
     * @return 일정을 적은 경기 수
     */
    public int saveMinorSchedule(long playerId, int season, Collection<Long> asked, List<MlbScheduleGame> games) {
        Map<Long, MlbScheduleGame> byPk = new LinkedHashMap<>();
        for (MlbScheduleGame game : games) {
            if (game.gamePk() != null) {
                byPk.merge(game.gamePk(), game, (before, after) -> isFinal(before) && !isFinal(after) ? before : after);
            }
        }
        int saved = 0;
        for (MinorGameLog line : minorGameLogs.findSeason(List.of(playerId), season)) {
            MlbScheduleGame game = byPk.get(line.getGamePk());
            MinorGameSchedule schedule = game == null ? null : minorSchedule(line, game);
            if (schedule != null) {
                line.updateSchedule(schedule);
                saved++;
            } else if (line.getSchedule() == null && asked.contains(line.getGamePk())) {
                line.updateSchedule(MinorGameSchedule.notFound());
            }
        }
        return saved;
    }

    private static MinorGameSchedule minorSchedule(MinorGameLog line, MlbScheduleGame game) {
        MlbScheduleGame.Teams sides = game.teams();
        if (game.gameDate() == null || sides == null || sides.home() == null || sides.away() == null) {
            return null;
        }
        Instant start;
        try {
            start = KoreanTime.parseUtc(game.gameDate());
        } catch (DateTimeParseException e) {
            log.debug("skip minor game {} with unreadable start time '{}'", game.gamePk(), game.gameDate());
            return null;
        }
        Long teamId = line.getTeamId();
        Boolean home;
        if (teamId != null && teamId.equals(teamId(sides.home().team()))) {
            home = true;
        } else if (teamId != null && teamId.equals(teamId(sides.away().team()))) {
            home = false;
        } else {
            home = line.getHome();
        }
        if (home == null) {
            // 어느 쪽이 선수 팀인지 모른다: 뒤바뀐 점수를 남기지 않게 시작 시각·상태만 둔다(끝나도 점수가 없어 기간 안에는 다시 묻는다)
            return new MinorGameSchedule(start, statusState(game), null, null, null, null);
        }
        MlbScheduleGame.Side mine = home ? sides.home() : sides.away();
        MlbScheduleGame.Side other = home ? sides.away() : sides.home();
        return new MinorGameSchedule(start, statusState(game), mine.score(), other.score(), place(mine.team()),
                place(other.team()));
    }

    private static String statusState(MlbScheduleGame game) {
        MlbScheduleGame.Status status = game.status();
        return status == null ? "unknown"
                : GameStatuses.of(status.abstractGameState(), status.codedGameState(), status.detailedState());
    }

    private static boolean isFinal(MlbScheduleGame game) {
        return GameStatuses.FINAL.equals(statusState(game));
    }

    private static String place(MlbTeam team) {
        return team == null ? null : team.franchiseName();
    }

    /** 마이너 경기 한 줄(같은 경기면 이미 만든 줄). 마이너가 아니거나 번호·날짜가 없으면 빈 값 */
    private static Optional<MinorGameLog> minorLine(Map<Long, MinorGameLog> byGame, Player player, int season,
                                                    MlbGameLogSplit split) {
        LocalDate gameDate = date(split.date());
        if (!MlbMinorGameLog.isMinors(split) || split.gamePk() == null || gameDate == null) {
            return Optional.empty();
        }
        MinorGameLog line = byGame.computeIfAbsent(split.gamePk(), pk -> new MinorGameLog(player, season, pk));
        line.updateGame(gameDate, split.sport().abbreviation(), teamName(split.team()), teamName(split.opponent()),
                split.isHome(), split.isWin());
        line.updateTeamIds(teamId(split.team()), teamId(split.opponent()));
        return Optional.of(line);
    }
}
