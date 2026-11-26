package com.korleaguer.ingest;

import com.korleaguer.domain.PlayerCareerSeason;
import com.korleaguer.domain.PlayerCareerSeasonRepository;
import com.korleaguer.domain.PlayerLeaguePosition;
import com.korleaguer.domain.PlayerLeaguePositionRepository;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.PlayerSeasonMetric;
import com.korleaguer.domain.PlayerSeasonMetricRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 선수의 시즌 단위 값(시즌 합계와 지표, MLB 안 위치, MLB 시즌별 합계)을 DB에 upsert한다.
 * HTTP 호출은 수집기(SeasonMetricRefresher·LeaguePositionRefresher)가 먼저 다 받아 오고, 저장만 여기서 트랜잭션으로 묶는다.
 */
@Component
@Transactional
public class SeasonStatWriter {

    private final PlayerRepository players;
    private final PlayerSeasonMetricRepository seasonMetrics;
    private final PlayerLeaguePositionRepository leaguePositions;
    private final PlayerCareerSeasonRepository careerSeasons;

    public SeasonStatWriter(PlayerRepository players, PlayerSeasonMetricRepository seasonMetrics,
                            PlayerLeaguePositionRepository leaguePositions,
                            PlayerCareerSeasonRepository careerSeasons) {
        this.players = players;
        this.seasonMetrics = seasonMetrics;
        this.leaguePositions = leaguePositions;
        this.careerSeasons = careerSeasons;
    }

    /**
     * 선수의 시즌 합계를 새 값으로 덮어쓴다. metrics가 null이면(이번에 묻지 않음) 지표는 지난 값을 둔다.
     */
    public void saveSeasonMetric(long playerId, int season, PlayerSeasonMetric.Group group, Integer plateAppearances,
                                 Integer inningsOuts, Integer gamesStarted, SeasonMetrics metrics, Instant fetchedAt) {
        PlayerSeasonMetric row = seasonMetrics.find(playerId, season, group)
                .orElseGet(() -> new PlayerSeasonMetric(players.getReferenceById(playerId), season, group));
        row.updateTotals(plateAppearances, inningsOuts, gamesStarted, fetchedAt);
        if (metrics != null) {
            row.updateMetrics(metrics.wrcPlus(), metrics.eraMinus(), metrics.war());
        }
        seasonMetrics.save(row);
    }

    /** 한 시즌 고급 지표. 모르는 칸은 null */
    public record SeasonMetrics(Double wrcPlus, Double eraMinus, Double war) {
    }

    /**
     * 선수의 지금 시즌 MLB 안 위치를 새 값으로 덮어쓴다. 타자 줄은 hitting, 투수 줄은 pitching을 쓰고,
     * 그 값이 null이면(리그 기록에 없음) 값을 비우고 받은 시각만 남긴다.
     */
    public void saveLeaguePosition(long playerId, int season, PlayerSeasonMetric.Group group,
                                   PlayerLeaguePosition.Hitting hitting, PlayerLeaguePosition.Pitching pitching,
                                   Instant fetchedAt) {
        PlayerLeaguePosition row = leaguePositions.find(playerId, season, group)
                .orElseGet(() -> new PlayerLeaguePosition(players.getReferenceById(playerId), season, group));
        if (group == PlayerSeasonMetric.Group.PITCHING) {
            row.update(pitching, fetchedAt);
        } else {
            row.update(hitting, fetchedAt);
        }
        leaguePositions.save(row);
    }

    /**
     * 선수의 MLB 시즌별 합계를 새 값으로 덮어쓴다. metrics에 있는 시즌만 지표도 바꾸고, 없는 시즌은 지난 지표를 둔다.
     * 지금 시즌(currentSeason) 지표는 '물었음' 표시를 남기지 않는다(시즌이 지나면 선수별 지표를 한 번 다시 묻는다).
     */
    public void saveCareerSeasons(long playerId, PlayerSeasonMetric.Group group, int currentSeason,
                                  Map<Integer, PlayerCareerSeason.Totals> totals,
                                  Map<Integer, SeasonMetrics> metrics, Instant fetchedAt) {
        Map<Integer, PlayerCareerSeason> saved = careerSeasons.findNewestFirst(playerId, group).stream()
                .collect(Collectors.toMap(PlayerCareerSeason::getSeason, Function.identity(), (a, b) -> a));
        totals.forEach((season, total) -> {
            PlayerCareerSeason row = saved.get(season);
            if (row == null) {
                row = new PlayerCareerSeason(players.getReferenceById(playerId), season, group);
            }
            row.updateTotals(total, fetchedAt);
            SeasonMetrics seasonMetrics = metrics.get(season);
            if (seasonMetrics != null) {
                row.updateMetrics(seasonMetrics.wrcPlus(), seasonMetrics.eraMinus(), seasonMetrics.war(),
                        season == currentSeason ? null : fetchedAt);
            }
            careerSeasons.save(row);
        });
    }
}
