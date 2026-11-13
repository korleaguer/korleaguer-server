package com.korleaguer.ingest;

import com.korleaguer.client.MlbApiException;
import com.korleaguer.client.MlbBlockedException;
import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbMinorGameLog;
import com.korleaguer.client.MlbPerson;
import com.korleaguer.client.MlbScheduleGame;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.client.MlbTransaction;
import com.korleaguer.config.CurrentSeason;
import com.korleaguer.domain.Level;
import com.korleaguer.domain.MinorSeasonRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.support.MlbTeams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * MLB 공식 Stats API로 선수 정보(프로필·지금 팀·메이저/마이너 단계)와 이동 기록을 새로 받는다.
 * <ul>
 *   <li>MLB 선수 번호(명단의 mlb-id)가 있는 선수만 본다.</li>
 *   <li>선수마다 하루(UTC 날짜)에 한 번만 받는다(force면 무조건). 실패한 선수는 확인 시각을 남기지 않아 다음 수집 때 다시 묻는다.</li>
 *   <li>선수 정보는 한 번에 여러 명을 묻는다(people?personIds=...). 이동 기록은 선수마다 한 번, 지난 시즌 1월 1일 ~ 이번 시즌 12월 31일.
 *       개막 전(시즌이 아직 작년인 1~3월)에는 올해 12월 31일까지 본다(겨울 계약을 개막 때까지 놓치지 않게).
 *       이번 시즌은 돌 때마다 CurrentSeason에 묻는다(시즌이 바뀌면 기간과 마이너 경기 수도 새 시즌으로 넘어간다).</li>
 *   <li>이번 시즌 마이너 경기별 기록도 선수마다 한 번 묻는다(타격·투구 한 번에, '내 선수' 카드와 마이너 경기 수에 쓴다).
 *       이것만 실패하면(403/429 제외) 선수 정보는 저장하고 지난 값을 둔다.
 *       그 경기들 중 끝나 점수를 받은 경기를 뺀 나머지만 일정(시작 시각·점수)을 묻는다(경기 번호를 묶어서, 선수 화면의 마이너 경기 줄).
 *       일정만 실패하면(403/429 제외) 경기별 기록은 저장하고 일정은 다음 수집 때 다시 묻는다.
 *       오늘 받은 마이너 기록이 없는 선수는 선수 정보를 이미 받았어도 그것만 다음 수집 때 다시 묻는다.</li>
 *   <li>팀 단계(sport.id)는 MLB 30개 구단이면 묻지 않고, 나머지 팀은 한 번 물어 앱이 떠 있는 동안 기억한다.</li>
 *   <li>403/429면 이번 실행에서 MLB를 더 부르지 않는다. 다른 오류는 그 선수만 건너뛴다.</li>
 * </ul>
 */
@Component
public class PlayerRefresher {

    private static final Logger log = LoggerFactory.getLogger(PlayerRefresher.class);

    private final MlbClient client;
    private final PlayerRepository players;
    private final IngestWriter writer;
    private final MinorSeasonRepository minorSeasons;
    private final Clock clock;
    private final CurrentSeason currentSeason;
    // 팀 번호 → 단계(모르는 팀이면 빈 값). 조회에 실패한 팀은 넣지 않는다.
    private final Map<Long, Optional<Level>> teamLevels = new ConcurrentHashMap<>();

    public PlayerRefresher(MlbClient client, PlayerRepository players, IngestWriter writer,
                           MinorSeasonRepository minorSeasons, CurrentSeason currentSeason, Clock clock) {
        this.client = client;
        this.players = players;
        this.writer = writer;
        this.minorSeasons = minorSeasons;
        this.currentSeason = currentSeason;
        this.clock = clock;
    }

    /**
     * 오늘 아직 확인하지 않은 선수만(force면 전부) 새로 받는다.
     * 선수 정보는 오늘 받았지만 마이너 출전 경기 수는 오늘 받지 못한 선수(배포 직후, 그 요청만 실패)는 마이너 경기 수만 다시 묻는다.
     */
    public Result refresh(boolean force) {
        long requestsBefore = client.requestCount();
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        int season = currentSeason.current();
        List<Player> all = players.findByMlbIdIsNotNull();
        List<Player> due = all.stream()
                .filter(p -> force || !checkedOn(p, today))
                .toList();
        Set<Long> minorsFresh = Set.copyOf(
                minorSeasons.findPlayerIdsFetchedSince(season, today.atStartOfDay(ZoneOffset.UTC).toInstant()));
        List<Player> minorsOnly = all.stream()
                .filter(p -> !due.contains(p) && !minorsFresh.contains(p.getId()))
                .toList();
        if (due.isEmpty() && minorsOnly.isEmpty()) {
            return new Result(0, 0, false, 0);
        }

        Result result = due.isEmpty() ? new Result(0, 0, false, 0) : refreshPlayers(due, season, now, today);
        if (!result.blocked()) {
            for (Player player : minorsOnly) {
                try {
                    refreshMinorGames(player, season, now);
                } catch (MlbBlockedException e) {
                    log.warn("[mlb] stop calling MLB for this run: {}", e.getMessage());
                    return new Result(result.checked(), result.failed(), true, client.requestCount() - requestsBefore);
                }
            }
        }
        return new Result(result.checked(), result.failed(), result.blocked(), client.requestCount() - requestsBefore);
    }

    /** 선수 정보·이동 기록·마이너 경기 수를 받는다. requests는 여기서 세지 않는다(0). */
    private Result refreshPlayers(List<Player> due, int season, Instant now, LocalDate today) {
        Map<Long, MlbPerson> people;
        try {
            people = client.getPeople(due.stream().map(Player::getMlbId).toList()).stream()
                    .filter(p -> p.id() != null)
                    .collect(Collectors.toMap(MlbPerson::id, Function.identity(), (a, b) -> a));
        } catch (MlbBlockedException e) {
            log.warn("[mlb] stop calling MLB for this run: {}", e.getMessage());
            return new Result(0, 0, true, 0);
        } catch (MlbApiException e) {
            log.warn("[mlb] people request failed, will retry next ingest ({})", e.getMessage());
            return new Result(0, due.size(), false, 0);
        }

        int checked = 0;
        int failed = 0;
        for (Player player : due) {
            MlbPerson person = people.get(player.getMlbId());
            if (person == null) {
                log.warn("[mlb] {}: MLB person {} not found, will retry next ingest", player.getNameKo(),
                        player.getMlbId());
                failed++;
                continue;
            }
            try {
                refresh(player, person, season, now, today);
                checked++;
            } catch (MlbBlockedException e) {
                log.warn("[mlb] stop calling MLB for this run: {}", e.getMessage());
                return new Result(checked, failed, true, 0);
            } catch (MlbApiException e) {
                log.warn("[mlb] {}: skip, will retry next ingest ({})", player.getNameKo(), e.getMessage());
                failed++;
            }
        }
        return new Result(checked, failed, false, 0);
    }

    private void refresh(Player player, MlbPerson person, int season, Instant now, LocalDate today) {
        MlbTeam current = person.currentTeam();
        Level level = levelOf(current == null ? null : current.id());
        List<MlbTransaction> transactions = client.getTransactions(player.getMlbId(),
                LocalDate.of(season - 1, 1, 1), LocalDate.of(Math.max(season, today.getYear()), 12, 31));

        Map<Long, Level> levels = new HashMap<>();
        transactions.stream()
                .flatMap(t -> Stream.of(t.fromTeam(), t.toTeam()))
                .filter(team -> team != null && team.id() != null)
                .map(MlbTeam::id)
                .distinct()
                .forEach(id -> {
                    Level teamLevel = levelOf(id);
                    if (teamLevel != null) {
                        levels.put(id, teamLevel);
                    }
                });
        writer.savePlayer(player.getId(), person, level, now, transactions, levels, today);
        refreshMinorGames(player, season, now);
    }

    private void refreshMinorGames(Player player, int season, Instant now) {
        MlbMinorGameLog games;
        try {
            games = client.getMinorGameLog(player.getMlbId(), season);
        } catch (MlbBlockedException e) {
            throw e;
        } catch (MlbApiException e) {
            log.warn("[mlb] {}: minor game log request failed, keep the last value ({})", player.getNameKo(),
                    e.getMessage());
            return;
        }
        List<Long> unsettled = writer.saveMinorGameLog(player.getId(), season, games, now);
        if (unsettled.isEmpty()) {
            return;
        }
        List<MlbScheduleGame> schedule;
        try {
            schedule = client.getGames(unsettled);
        } catch (MlbBlockedException e) {
            throw e;
        } catch (MlbApiException e) {
            log.warn("[mlb] {}: minor game schedule request failed, will retry next time ({})", player.getNameKo(),
                    e.getMessage());
            return;
        }
        writer.saveMinorSchedule(player.getId(), season, schedule);
    }

    /** 팀 단계. 팀이 없거나 모르면 null. 팀 조회가 실패하면(403/429 제외) 기억하지 않고 null이다. */
    Level levelOf(Long teamId) {
        if (teamId == null) {
            return null;
        }
        if (MlbTeams.isClub(teamId)) {
            return Level.MLB;
        }
        Optional<Level> cached = teamLevels.get(teamId);
        if (cached != null) {
            return cached.orElse(null);
        }
        Level level;
        try {
            MlbTeam team = client.getTeam(teamId);
            level = team == null ? null : Level.fromSportId(team.sportId());
        } catch (MlbBlockedException e) {
            throw e;
        } catch (MlbApiException e) {
            log.debug("[mlb] team {} lookup failed: {}", teamId, e.getMessage());
            return null;
        }
        teamLevels.put(teamId, Optional.ofNullable(level));
        return level;
    }

    private static boolean checkedOn(Player player, LocalDate today) {
        Instant checkedAt = player.getLevelCheckedAt();
        return checkedAt != null && !LocalDate.ofInstant(checkedAt, ZoneOffset.UTC).isBefore(today);
    }

    /**
     * 한 번 돈 결과. checked: 새로 받은 선수 수, failed: 오류로 건너뛴 선수 수,
     * blocked: 403/429로 도중에 멈췄는지, requests: 보낸 MLB 요청 수
     */
    public record Result(int checked, int failed, boolean blocked, long requests) {

        /** 수집 기록 메시지에 붙일 한 줄. 오늘 이미 다 받아 할 일이 없었으면 빈 문자열 */
        public String describe() {
            if (checked == 0 && failed == 0 && !blocked) {
                return "";
            }
            StringBuilder message = new StringBuilder("선수 정보·이동 기록 ").append(checked).append("명");
            if (failed > 0) {
                message.append(", 실패 ").append(failed).append("명");
            }
            if (blocked) {
                message.append(", MLB가 요청을 거절해(403/429) 중단");
            }
            return message.toString();
        }
    }
}
