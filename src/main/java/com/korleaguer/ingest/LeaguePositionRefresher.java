package com.korleaguer.ingest;

import com.korleaguer.client.MlbApiException;
import com.korleaguer.client.MlbBlockedException;
import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbSeasonSplit;
import com.korleaguer.config.CurrentSeason;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerCareerSeason;
import com.korleaguer.domain.PlayerCareerSeasonRepository;
import com.korleaguer.domain.PlayerLeaguePosition;
import com.korleaguer.domain.PlayerLeaguePositionRepository;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.PlayerSeasonMetric;
import com.korleaguer.domain.Role;
import com.korleaguer.support.BaseballMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 선수 화면의 'MLB 안 위치'와 지난 시즌 표에 쓸 기록을 받아 저장한다(이슈 #133).
 * <ul>
 *   <li>선수마다 하루(UTC 날짜)에 한 번. 정규시즌 중에도 끝난 뒤에도 받는다. 지금 시즌 위치 줄(player_league_position)은
 *       리그 기록에 없어도 남겨 '오늘 받았음' 표시로 쓴다.</li>
 *   <li>리그 전체: 그 실행에서 묶음(타격·투구)마다 한 번씩 시즌 합계와 sabermetrics를 받아(묶음마다 2번) 우리 선수의 값과
 *       위치를 계산한다(LeaguePositions). 우리 선수에 투수가 없으면 투구는 묻지 않는다.</li>
 *   <li>시즌별 기록: 선수마다 yearByYear 1번(player_career_season). 지난 시즌 지표(wRC+·ERA-·WAR)는 아직 묻지 않은 시즌만
 *       선수별 sabermetrics로 한 번 묻고, 지금 시즌 지표는 리그 전체 값을 쓴다.</li>
 *   <li>403/429면 그대로 던져 그 실행을 멈춘다. 다른 오류는 그 선수만(리그 전체가 실패하면 그 묶음 선수 모두) 건너뛰고
 *       다음 수집 때 다시 묻는다.</li>
 * </ul>
 */
@Component
public class LeaguePositionRefresher {

    private static final Logger log = LoggerFactory.getLogger(LeaguePositionRefresher.class);

    private final MlbClient client;
    private final PlayerRepository players;
    private final PlayerLeaguePositionRepository positions;
    private final PlayerCareerSeasonRepository careerSeasons;
    private final IngestWriter writer;
    private final CurrentSeason currentSeason;
    private final Clock clock;

    public LeaguePositionRefresher(MlbClient client, PlayerRepository players, PlayerLeaguePositionRepository positions,
                                   PlayerCareerSeasonRepository careerSeasons, IngestWriter writer,
                                   CurrentSeason currentSeason, Clock clock) {
        this.client = client;
        this.players = players;
        this.positions = positions;
        this.careerSeasons = careerSeasons;
        this.writer = writer;
        this.currentSeason = currentSeason;
        this.clock = clock;
    }

    /**
     * 오늘 아직 받지 않은 선수의 MLB 안 위치와 시즌별 기록을 받는다.
     *
     * @return 수집 기록 메시지에 붙일 한 줄. 할 일이 없었으면 빈 문자열
     * @throws MlbBlockedException 403/429
     */
    public String refresh() {
        int season = currentSeason.current();
        Instant now = clock.instant();
        Instant startOfDay = LocalDate.ofInstant(now, ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Set<Long> fresh = Set.copyOf(positions.findPlayerIdsFetchedSince(season, startOfDay));
        List<Player> due = players.findByMlbIdIsNotNull().stream()
                .filter(p -> !fresh.contains(p.getId()))
                .toList();
        if (due.isEmpty()) {
            return "";
        }
        League league = new League(season);
        int done = 0;
        int failed = 0;
        for (Player player : due) {
            try {
                refresh(player, season, now, league);
                done++;
            } catch (MlbBlockedException e) {
                throw e;
            } catch (MlbApiException e) {
                log.warn("[mlb] {}: league position failed, will retry next ingest ({})", player.getNameKo(),
                        e.getMessage());
                failed++;
            }
        }
        return "MLB 안 위치 " + done + "명" + (failed > 0 ? ", 실패 " + failed + "명" : "");
    }

    private void refresh(Player player, int season, Instant now, League league) {
        Role role = player.role();
        boolean pitcher = role == Role.PITCHER;
        MlbClient.Group group = pitcher ? MlbClient.Group.PITCHING : MlbClient.Group.HITTING;
        PlayerSeasonMetric.Group rowGroup = PlayerSeasonMetric.Group.of(role);
        long mlbId = player.getMlbId();

        PlayerLeaguePosition.Hitting hitting = null;
        PlayerLeaguePosition.Pitching pitching = null;
        IngestWriter.SeasonMetrics currentMetrics = new IngestWriter.SeasonMetrics(null, null, null);
        if (pitcher) {
            Map<Long, LeaguePositions.PitcherLine> pitchers = league.pitchers();
            LeaguePositions.PitcherLine me = pitchers.get(mlbId);
            if (me != null) {
                pitching = LeaguePositions.pitching(me, pitchers.values());
                currentMetrics = new IngestWriter.SeasonMetrics(null, me.eraMinus(), me.war());
            }
        } else {
            Map<Long, LeaguePositions.HitterLine> hitters = league.hitters();
            LeaguePositions.HitterLine me = hitters.get(mlbId);
            if (me != null) {
                hitting = LeaguePositions.hitting(me, hitters.values());
                currentMetrics = new IngestWriter.SeasonMetrics(me.wrcPlus(), null, me.war());
            }
        }

        Map<Integer, PlayerCareerSeason.Totals> totals = careerTotals(client.getYearByYear(mlbId, group), role);
        Map<Integer, PlayerCareerSeason> saved = careerSeasons.findNewestFirst(player.getId(), rowGroup).stream()
                .collect(Collectors.toMap(PlayerCareerSeason::getSeason, Function.identity(), (a, b) -> a));
        Map<Integer, IngestWriter.SeasonMetrics> metrics = new HashMap<>();
        for (int year : totals.keySet()) {
            if (year == season) {
                metrics.put(year, currentMetrics);
            } else if (saved.get(year) == null || saved.get(year).getMetricsFetchedAt() == null) {
                // 지난 시즌 지표는 바뀌지 않으므로 한 번만 묻는다
                metrics.put(year, SeasonMetricRefresher.sabermetrics(client.getSabermetrics(mlbId, group, year)));
            }
        }
        writer.saveCareerSeasons(player.getId(), rowGroup, totals, metrics, now);
        // 위치 줄이 '오늘 받았음' 표시라 마지막에 쓴다(앞에서 실패하면 다음 수집 때 다시 묻는다)
        writer.saveLeaguePosition(player.getId(), season, rowGroup, hitting, pitching, now);
    }

    /**
     * yearByYear 줄들 → 시즌마다 합계. 여러 팀 시즌은 합친 줄을 쓰고(팀 번호는 팀별 줄에서 뛴 순서로),
     * 합친 줄이 없으면 팀별 줄의 개수 기록만 더한다(타율·OPS·평균자책점은 모른다).
     */
    static Map<Integer, PlayerCareerSeason.Totals> careerTotals(List<MlbSeasonSplit> splits, Role role) {
        Map<Integer, List<MlbSeasonSplit>> bySeason = new TreeMap<>();
        for (MlbSeasonSplit split : splits) {
            Integer year = season(split);
            if (split.stat() != null && year != null) {
                bySeason.computeIfAbsent(year, y -> new ArrayList<>()).add(split);
            }
        }
        Map<Integer, PlayerCareerSeason.Totals> result = new TreeMap<>();
        bySeason.forEach((year, lines) -> {
            Set<Long> teamIds = new LinkedHashSet<>();
            lines.stream().filter(l -> l.team() != null && l.team().id() != null).forEach(l -> teamIds.add(l.team().id()));
            List<MlbSeasonSplit> teamLines = lines.stream().filter(l -> !l.combined()).toList();
            MlbSeasonSplit.Stat one = lines.stream().filter(MlbSeasonSplit::combined).findFirst()
                    .map(MlbSeasonSplit::stat)
                    .orElse(teamLines.size() == 1 ? teamLines.getFirst().stat() : null);
            result.put(year, one != null ? totals(one, role, List.copyOf(teamIds))
                    : summed(teamLines, role, List.copyOf(teamIds)));
        });
        return result;
    }

    private static PlayerCareerSeason.Totals totals(MlbSeasonSplit.Stat stat, Role role, List<Long> teamIds) {
        if (role == Role.PITCHER) {
            return new PlayerCareerSeason.Totals(teamIds, stat.gamesPlayed(), null, null, null, null,
                    BaseballMath.outsFromInnings(stat.inningsPitched()), BaseballMath.decimal(stat.era()),
                    stat.strikeOuts());
        }
        return new PlayerCareerSeason.Totals(teamIds, stat.gamesPlayed(), stat.plateAppearances(),
                BaseballMath.decimal(stat.avg()), stat.homeRuns(), BaseballMath.decimal(stat.ops()), null, null, null);
    }

    private static PlayerCareerSeason.Totals summed(List<MlbSeasonSplit> lines, Role role, List<Long> teamIds) {
        Integer games = sum(lines, MlbSeasonSplit.Stat::gamesPlayed);
        if (role == Role.PITCHER) {
            Integer outs = sum(lines, s -> BaseballMath.outsFromInnings(s.inningsPitched()));
            return new PlayerCareerSeason.Totals(teamIds, games, null, null, null, null, outs, null,
                    sum(lines, MlbSeasonSplit.Stat::strikeOuts));
        }
        return new PlayerCareerSeason.Totals(teamIds, games, sum(lines, MlbSeasonSplit.Stat::plateAppearances), null,
                sum(lines, MlbSeasonSplit.Stat::homeRuns), null, null, null, null);
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

    private static Integer season(MlbSeasonSplit split) {
        try {
            return split.season() == null ? null : Integer.valueOf(split.season());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 한 번 실행하는 동안 받은 리그 전체 기록. 묶음마다 처음 필요할 때 한 번만 묻는다.
     * 묻다 실패한 묶음은 그 실행에서 다시 묻지 않는다(그 묶음 선수는 모두 실패로 센다).
     */
    private final class League {

        private final int season;
        private final Set<MlbClient.Group> failed = EnumSet.noneOf(MlbClient.Group.class);
        private Map<Long, LeaguePositions.HitterLine> hitters;
        private Map<Long, LeaguePositions.PitcherLine> pitchers;

        League(int season) {
            this.season = season;
        }

        Map<Long, LeaguePositions.HitterLine> hitters() {
            if (hitters == null) {
                hitters = fetch(MlbClient.Group.HITTING, LeaguePositions::hitters);
            }
            return hitters;
        }

        Map<Long, LeaguePositions.PitcherLine> pitchers() {
            if (pitchers == null) {
                pitchers = fetch(MlbClient.Group.PITCHING, LeaguePositions::pitchers);
            }
            return pitchers;
        }

        private <T> Map<Long, T> fetch(MlbClient.Group group,
                                       BiFunction<List<MlbSeasonSplit>, List<MlbSeasonSplit>, Map<Long, T>> build) {
            if (failed.contains(group)) {
                throw new MlbApiException("league " + group.value() + " " + season + " failed earlier in this run",
                        null);
            }
            try {
                return build.apply(client.getLeagueSeasonStats(group, season),
                        client.getLeagueSabermetrics(group, season));
            } catch (MlbApiException e) {
                failed.add(group);
                throw e;
            }
        }
    }
}
