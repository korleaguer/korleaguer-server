package com.korleaguer.ingest;

import com.korleaguer.client.MlbApiException;
import com.korleaguer.client.MlbBlockedException;
import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbSeasonSplit;
import com.korleaguer.config.KoreanMlbProperties;
import com.korleaguer.domain.KoreanMlbSeason;
import com.korleaguer.domain.KoreanMlbSeasonRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.PlayerSeasonMetric;
import com.korleaguer.domain.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 한국인 메이저리거 역대 기록(기록 탭, 이슈 #177)에 쓰는 시즌별 개수 기록을 받는다. 따라잡기(IngestService.catchUp)의 맨 뒤 단계다.
 * <ul>
 *   <li>대상: 지금 명단(baseball.yml) 중 MLB 번호가 있는 선수 + korean-mlb.yml의 명단 밖 선수(은퇴·국내 복귀). 번호가 겹치면 한 번만.</li>
 *   <li>명단 선수는 하루(UTC 날짜)에 한 번 yearByYear 1번(지금 시즌 기록이 바뀐다). 명단 밖 선수는 저장한 줄이 없을 때만
 *       (처음 한 번. 지난 기록은 바뀌지 않는다).</li>
 *   <li>이미 물었던 선수는 결과(실패·빈 기록 포함)와 상관없이 다시 묻지 않는다: 명단 선수는 그날, 명단 밖 선수는 서버가 켜져 있는 동안
 *       (12분마다 같은 요청을 되풀이하지 않게. MLB에 기록이 없는 번호를 날마다 묻지도 않게).</li>
 *   <li>403/429면 그대로 던져 그 실행을 멈춘다. 그 밖의 실패는 그 선수만 건너뛴다(명단 선수는 다음 날, 명단 밖 선수는 서버를 다시 띄운 뒤 다시 묻는다).</li>
 * </ul>
 */
@Component
public class KoreanRecordsRefresher {

    private static final Logger log = LoggerFactory.getLogger(KoreanRecordsRefresher.class);

    private final MlbClient client;
    private final PlayerRepository players;
    private final KoreanMlbSeasonRepository seasons;
    private final KoreanRecordsWriter writer;
    private final KoreanMlbProperties past;
    private final Clock clock;
    /** MLB 번호 → 마지막으로 물은 날(UTC). 서버를 다시 띄우면 비지만, 그때는 위 기준으로 한 번 더 묻기만 한다 */
    private final Map<Long, LocalDate> asked = new ConcurrentHashMap<>();

    public KoreanRecordsRefresher(MlbClient client, PlayerRepository players, KoreanMlbSeasonRepository seasons,
                                  KoreanRecordsWriter writer, KoreanMlbProperties past, Clock clock) {
        this.client = client;
        this.players = players;
        this.seasons = seasons;
        this.writer = writer;
        this.past = past;
        this.clock = clock;
    }

    /** 받을 선수 한 명(명단 선수면 current) */
    record Target(long mlbId, String nameKo, boolean pitcher, boolean current) {
    }

    /**
     * 아직 받지 않은 선수의 시즌별 기록을 받는다.
     *
     * @return 수집 기록 메시지에 붙일 한 줄. 할 일이 없었으면 빈 문자열
     * @throws MlbBlockedException 403/429
     */
    public String refresh() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Instant startOfDay = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        Set<Long> saved = Set.copyOf(seasons.findMlbIds());
        Set<Long> freshToday = Set.copyOf(seasons.findMlbIdsFetchedSince(startOfDay));
        List<Target> due = targets().stream()
                .filter(t -> t.current()
                        ? !today.equals(asked.get(t.mlbId())) && !freshToday.contains(t.mlbId())
                        : !asked.containsKey(t.mlbId()) && !saved.contains(t.mlbId()))
                .toList();
        if (due.isEmpty()) {
            return "";
        }
        int done = 0;
        int failed = 0;
        for (Target target : due) {
            asked.put(target.mlbId(), today);
            MlbClient.Group group = target.pitcher() ? MlbClient.Group.PITCHING : MlbClient.Group.HITTING;
            try {
                Map<Integer, KoreanMlbSeason.Counts> counts = seasonCounts(client.getYearByYear(target.mlbId(), group),
                        target.pitcher());
                writer.save(target.mlbId(), target.pitcher() ? PlayerSeasonMetric.Group.PITCHING
                        : PlayerSeasonMetric.Group.HITTING, counts, now);
                done++;
            } catch (MlbBlockedException e) {
                throw e;
            } catch (MlbApiException e) {
                log.warn("[mlb] {}: korean records failed, will retry tomorrow ({})", target.nameKo(), e.getMessage());
                failed++;
            }
        }
        return "한국인 역대 기록 " + done + "명" + (failed > 0 ? ", 실패 " + failed + "명" : "");
    }

    /** 명단 선수(MLB 번호 있는 선수) 먼저, 그다음 명단 밖 선수. 번호가 겹치면 명단 쪽만 */
    List<Target> targets() {
        Map<Long, Target> byId = new LinkedHashMap<>();
        for (Player player : players.findByMlbIdIsNotNull()) {
            byId.put(player.getMlbId(), new Target(player.getMlbId(), player.getNameKo(),
                    player.role() == Role.PITCHER, true));
        }
        for (KoreanMlbProperties.Entry entry : past.past()) {
            if (entry.mlbId() != null) {
                byId.putIfAbsent(entry.mlbId(), new Target(entry.mlbId(), entry.nameKo(), entry.pitcher(), false));
            }
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * yearByYear 줄들 → 시즌마다 개수 기록. 여러 팀 시즌은 합친 줄(numTeams, 팀 없음)을 쓰고,
     * 합친 줄이 없으면 팀별 줄을 더한다(LeaguePositionRefresher.careerTotals와 같은 규칙).
     */
    static Map<Integer, KoreanMlbSeason.Counts> seasonCounts(List<MlbSeasonSplit> splits, boolean pitcher) {
        Map<Integer, List<MlbSeasonSplit>> bySeason = new TreeMap<>();
        for (MlbSeasonSplit split : splits) {
            Integer year = split.seasonYear();
            if (split.stat() != null && year != null) {
                bySeason.computeIfAbsent(year, y -> new ArrayList<>()).add(split);
            }
        }
        Map<Integer, KoreanMlbSeason.Counts> result = new TreeMap<>();
        bySeason.forEach((year, lines) -> {
            List<MlbSeasonSplit> use = lines.stream().filter(MlbSeasonSplit::combined).findFirst()
                    .map(List::of)
                    .orElseGet(() -> lines.stream().filter(l -> !l.combined()).toList());
            result.put(year, pitcher
                    ? new KoreanMlbSeason.Counts(sum(use, MlbSeasonSplit.Stat::gamesPlayed), null, null, null, null,
                            sum(use, MlbSeasonSplit.Stat::wins), sum(use, MlbSeasonSplit.Stat::saves),
                            sum(use, MlbSeasonSplit.Stat::strikeOuts))
                    : new KoreanMlbSeason.Counts(sum(use, MlbSeasonSplit.Stat::gamesPlayed),
                            sum(use, MlbSeasonSplit.Stat::hits), sum(use, MlbSeasonSplit.Stat::homeRuns),
                            sum(use, MlbSeasonSplit.Stat::rbi), sum(use, MlbSeasonSplit.Stat::stolenBases), null, null,
                            null));
        });
        return result;
    }

    /** 줄들의 값을 더한다. 모든 줄에 값이 없으면 null */
    private static Integer sum(List<MlbSeasonSplit> lines, Function<MlbSeasonSplit.Stat, Integer> value) {
        Integer total = null;
        for (MlbSeasonSplit line : lines) {
            Integer v = value.apply(line.stat());
            if (v != null) {
                total = (total == null ? 0 : total) + v;
            }
        }
        return total;
    }
}
