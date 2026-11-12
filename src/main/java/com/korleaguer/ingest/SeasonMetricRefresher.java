package com.korleaguer.ingest;

import com.korleaguer.client.MlbApiException;
import com.korleaguer.client.MlbBlockedException;
import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbSeasonSplit;
import com.korleaguer.config.CurrentSeason;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.PlayerSeasonMetric;
import com.korleaguer.domain.PlayerSeasonMetricRepository;
import com.korleaguer.domain.Role;
import com.korleaguer.domain.SeasonDates;
import com.korleaguer.domain.SeasonDatesRepository;
import com.korleaguer.support.BaseballMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 비시즌 홈의 시즌 성적표 판정에 쓸 시즌 합계와 고급 지표를 받아 player_season_metric에 저장한다(이슈 #118).
 * <ul>
 *   <li>지금 시즌의 정규시즌이 끝난 뒤에만 받는다(시즌 날짜를 모르면 기다린다. 샘플·고정 시즌이면 바로 받는다).</li>
 *   <li>선수마다 하루(UTC 날짜)에 한 번. 지금 시즌 줄은 메이저 기록이 없어도 남겨 '오늘 받았음' 표시로 쓴다.</li>
 *   <li>선수마다 yearByYear 1번으로 지금 시즌과 앞 3시즌의 메이저 타석(투수는 이닝·선발)을 받고,
 *       sabermetrics(wRC+·ERA-·WAR)는 지금 시즌(메이저 기록이 있을 때)과, 지난 시즌 중 아직 WAR가 없는 시즌만 묻는다.</li>
 *   <li>한 시즌에 여러 팀에서 뛰었으면 합친 줄을 쓰고, 합친 줄이 없으면 팀별 줄을 더한다(WAR만. wRC+·ERA-는 모름).</li>
 *   <li>403/429면 그대로 던져 그 실행을 멈춘다. 다른 오류는 그 선수만 건너뛰고 다음 수집 때 다시 묻는다.</li>
 * </ul>
 */
@Component
public class SeasonMetricRefresher {

    private static final Logger log = LoggerFactory.getLogger(SeasonMetricRefresher.class);
    /** 지금 시즌 앞으로 몇 시즌을 더 받나(판정의 '전에 주전이었나'가 앞 3시즌을 본다) */
    static final int PREVIOUS_SEASONS = 3;

    private final MlbClient client;
    private final PlayerRepository players;
    private final PlayerSeasonMetricRepository metrics;
    private final SeasonDatesRepository seasonDates;
    private final IngestWriter writer;
    private final CurrentSeason currentSeason;
    private final Clock clock;

    public SeasonMetricRefresher(MlbClient client, PlayerRepository players, PlayerSeasonMetricRepository metrics,
                                 SeasonDatesRepository seasonDates, IngestWriter writer, CurrentSeason currentSeason,
                                 Clock clock) {
        this.client = client;
        this.players = players;
        this.metrics = metrics;
        this.seasonDates = seasonDates;
        this.writer = writer;
        this.currentSeason = currentSeason;
        this.clock = clock;
    }

    /**
     * 정규시즌이 끝났으면 오늘 아직 받지 않은 선수의 시즌 합계·지표를 받는다.
     *
     * @return 수집 기록 메시지에 붙일 한 줄. 할 일이 없었으면 빈 문자열
     * @throws MlbBlockedException 403/429
     */
    public String refresh() {
        int season = currentSeason.current();
        Instant now = clock.instant();
        if (!regularSeasonOver(season, LocalDate.ofInstant(now, GameIngest.US_EASTERN))) {
            return "";
        }
        Instant startOfDay = LocalDate.ofInstant(now, ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Set<Long> fresh = Set.copyOf(metrics.findPlayerIdsFetchedSince(season, startOfDay));
        List<Player> due = players.findByMlbIdIsNotNull().stream()
                .filter(p -> !fresh.contains(p.getId()))
                .toList();
        if (due.isEmpty()) {
            return "";
        }
        int done = 0;
        int failed = 0;
        for (Player player : due) {
            try {
                refresh(player, season, now);
                done++;
            } catch (MlbBlockedException e) {
                throw e;
            } catch (MlbApiException e) {
                log.warn("[mlb] {}: season metrics failed, will retry next ingest ({})", player.getNameKo(),
                        e.getMessage());
                failed++;
            }
        }
        return "시즌 지표 " + done + "명" + (failed > 0 ? ", 실패 " + failed + "명" : "");
    }

    private boolean regularSeasonOver(int season, LocalDate usToday) {
        Optional<SeasonDates> dates = seasonDates.findById(season);
        if (dates.isEmpty()) {
            return currentSeason.isFixed();
        }
        return usToday.isAfter(dates.get().getRegularSeasonEnd());
    }

    private void refresh(Player player, int season, Instant now) {
        Role role = player.role();
        MlbClient.Group group = role == Role.PITCHER ? MlbClient.Group.PITCHING : MlbClient.Group.HITTING;
        PlayerSeasonMetric.Group metricGroup = PlayerSeasonMetric.Group.of(role);
        Map<Integer, Totals> totals = totals(client.getYearByYear(player.getMlbId(), group), role);
        Map<Integer, PlayerSeasonMetric> saved = metrics.findSeasons(List.of(player.getId()),
                        season - PREVIOUS_SEASONS, season).stream()
                .filter(m -> m.getStatGroup() == metricGroup)
                .collect(Collectors.toMap(PlayerSeasonMetric::getSeason, Function.identity(), (a, b) -> a));

        for (int year = season - PREVIOUS_SEASONS; year <= season; year++) {
            Totals total = totals.get(year);
            if (total == null && year != season) {
                continue; // 그 시즌 메이저 기록이 없다
            }
            if (total == null) {
                total = Totals.none(role);
            }
            IngestWriter.SeasonMetrics seasonMetrics;
            if (!total.played()) {
                seasonMetrics = year == season ? new IngestWriter.SeasonMetrics(null, null, null) : null;
            } else if (year == season || saved.get(year) == null || saved.get(year).getWar() == null) {
                seasonMetrics = sabermetrics(client.getSabermetrics(player.getMlbId(), group, year));
            } else {
                seasonMetrics = null; // 지난 시즌 지표는 이미 받았다
            }
            writer.saveSeasonMetric(player.getId(), year, metricGroup, total.plateAppearances(), total.inningsOuts(),
                    total.gamesStarted(), seasonMetrics, now);
        }
    }

    /** 시즌 → 메이저 합계. 여러 팀 시즌은 합친 줄, 없으면 팀별 줄을 더한다. */
    static Map<Integer, Totals> totals(List<MlbSeasonSplit> splits, Role role) {
        Map<Integer, List<MlbSeasonSplit>> bySeason = splits.stream()
                .filter(s -> s.stat() != null && season(s) != null)
                .collect(Collectors.groupingBy(SeasonMetricRefresher::season, TreeMap::new, Collectors.toList()));
        Map<Integer, Totals> result = new TreeMap<>();
        bySeason.forEach((year, lines) -> {
            List<MlbSeasonSplit> used = lines.stream().filter(MlbSeasonSplit::combined).findFirst()
                    .map(List::of).orElse(lines);
            int plateAppearances = 0;
            int outs = 0;
            int started = 0;
            for (MlbSeasonSplit line : used) {
                plateAppearances += orZero(line.stat().plateAppearances());
                Integer lineOuts = BaseballMath.outsFromInnings(line.stat().inningsPitched());
                outs += lineOuts == null ? 0 : lineOuts;
                started += orZero(line.stat().gamesStarted());
            }
            result.put(year, role == Role.PITCHER ? new Totals(null, outs, started)
                    : new Totals(plateAppearances, null, null));
        });
        return result;
    }

    /** 고급 지표 줄들 → 한 시즌 지표. 여러 줄이면 합친 줄, 없으면 WAR만 더한다. */
    static IngestWriter.SeasonMetrics sabermetrics(List<MlbSeasonSplit> splits) {
        List<MlbSeasonSplit> lines = splits.stream().filter(s -> s.stat() != null).toList();
        if (lines.isEmpty()) {
            return new IngestWriter.SeasonMetrics(null, null, null);
        }
        Optional<MlbSeasonSplit> one = lines.size() == 1 ? Optional.of(lines.getFirst())
                : lines.stream().filter(MlbSeasonSplit::combined).findFirst();
        if (one.isPresent()) {
            MlbSeasonSplit.Stat stat = one.get().stat();
            return new IngestWriter.SeasonMetrics(stat.wRcPlus(), stat.eraMinus(), stat.war());
        }
        boolean anyWar = lines.stream().anyMatch(s -> s.stat().war() != null);
        double war = lines.stream().mapToDouble(s -> s.stat().war() == null ? 0 : s.stat().war()).sum();
        return new IngestWriter.SeasonMetrics(null, null, anyWar ? war : null);
    }

    private static Integer season(MlbSeasonSplit split) {
        try {
            return split.season() == null ? null : Integer.valueOf(split.season());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    /** 한 시즌 메이저 합계. 타자는 타석만, 투수는 아웃 수·선발만 있다. */
    record Totals(Integer plateAppearances, Integer inningsOuts, Integer gamesStarted) {

        static Totals none(Role role) {
            return role == Role.PITCHER ? new Totals(null, 0, 0) : new Totals(0, null, null);
        }

        boolean played() {
            return (plateAppearances != null && plateAppearances > 0) || (inningsOuts != null && inningsOuts > 0);
        }
    }
}
