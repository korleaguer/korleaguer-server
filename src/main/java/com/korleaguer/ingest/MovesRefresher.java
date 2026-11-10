package com.korleaguer.ingest;

import com.korleaguer.client.MlbApiException;
import com.korleaguer.client.MlbBlockedException;
import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbPerson;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.client.MlbTransaction;
import com.korleaguer.config.KorleaguerProperties;
import com.korleaguer.domain.Level;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * MLB 공식 Stats API로 선수의 지금 단계(메이저/마이너)·지금 팀과 이동 기록을 새로 받는다. IngestService가 수집 끝에 부른다.
 * <ul>
 *   <li>명단(baseball.yml)에 mlb-id가 있는 선수만 본다. balldontlie 매칭과는 상관없다.</li>
 *   <li>선수마다 하루(UTC 날짜)에 한 번만 받는다. 실패한 선수는 확인 시각을 남기지 않아 다음 수집 때 다시 묻는다.</li>
 *   <li>이동 기록은 지난 시즌 1월 1일부터 이번 시즌 12월 31일까지 받는다.</li>
 *   <li>팀 단계(sport.id)는 MLB 30개 구단이면 묻지 않고, 나머지 팀은 한 번 물어 앱이 떠 있는 동안 기억한다.</li>
 *   <li>403/429면 이번 실행에서 MLB를 더 부르지 않는다. 다른 오류는 그 선수만 건너뛴다.</li>
 * </ul>
 */
@Component
public class MovesRefresher {

    private static final Logger log = LoggerFactory.getLogger(MovesRefresher.class);

    private final MlbClient client;
    private final PlayerRepository players;
    private final IngestWriter writer;
    private final RosterProperties roster;
    private final Clock clock;
    private final int season;
    // 팀 번호 → 단계(모르는 팀이면 빈 값). 조회에 실패한 팀은 넣지 않는다.
    private final Map<Long, Optional<Level>> teamLevels = new ConcurrentHashMap<>();

    public MovesRefresher(MlbClient client, PlayerRepository players, IngestWriter writer, RosterProperties roster,
                          KorleaguerProperties properties, Clock clock) {
        this.client = client;
        this.players = players;
        this.writer = writer;
        this.roster = roster;
        this.season = properties.season();
        this.clock = clock;
    }

    /** 오늘 아직 확인하지 않은 선수만 새로 받는다. */
    public Result refreshStale() {
        long requestsBefore = client.requestCount();
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        int checked = 0;
        int failed = 0;
        for (RosterProperties.Entry entry : roster.baseball()) {
            if (entry.mlbId() == null) {
                continue;
            }
            Optional<Player> player = players.findByNameKo(entry.nameKo());
            if (player.isEmpty() || checkedOn(player.get(), today)) {
                continue;
            }
            try {
                refresh(player.get(), entry.mlbId(), now);
                checked++;
            } catch (MlbBlockedException e) {
                log.warn("[mlb] stop calling MLB for this run: {}", e.getMessage());
                return new Result(checked, failed, true, client.requestCount() - requestsBefore);
            } catch (MlbApiException e) {
                log.warn("[mlb] {}: skip, will retry next ingest ({})", entry.nameKo(), e.getMessage());
                failed++;
            }
        }
        return new Result(checked, failed, false, client.requestCount() - requestsBefore);
    }

    private void refresh(Player player, long mlbId, Instant now) {
        MlbPerson person = client.getPerson(mlbId);
        if (person == null) {
            throw new MlbApiException("MLB person " + mlbId + " not found", null);
        }
        MlbTeam current = person.currentTeam();
        Level level = levelOf(current == null ? null : current.id());
        List<MlbTransaction> transactions = client.getTransactions(mlbId,
                LocalDate.of(season - 1, 1, 1), LocalDate.of(season, 12, 31));

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
        writer.saveLevelAndMoves(player.getId(), level, current == null ? null : current.name(), now,
                transactions, levels);
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
            StringBuilder message = new StringBuilder("MLB 단계·이동 기록 ").append(checked).append("명");
            if (failed > 0) {
                message.append(", 실패 ").append(failed).append("명");
            }
            if (blocked) {
                message.append(", MLB가 요청을 거절해(403/429) 중단");
            }
            return message.append(" (MLB 요청 ").append(requests).append("회)").toString();
        }
    }
}
