package com.korleaguer.api;

import com.korleaguer.domain.PlayerLeaguePosition;
import com.korleaguer.domain.PlayerSeasonMetric;
import com.korleaguer.support.BaseballMath;

/**
 * 선수 화면의 'MLB 안 위치'(GET /api/players/{id}의 saber, 이슈 #133). 지금 시즌 MLB 공식 Stats API sabermetrics 값과,
 * 같은 시즌 MLB 선수 중 이 선수의 위치(백분위)다. 타자면 Hitter, 투수면 Pitcher 모양이다. 계산 규칙은 ingest.LeaguePositions.
 * <ul>
 *   <li>공통: season, war, poolSize(견준 MLB 선수 수: 타자 100타석·투수 30이닝 이상), percentiles</li>
 *   <li>percentiles: 값마다 1~99 정수(견준 선수 중 이 선수보다 값이 나쁜 비율, 높을수록 좋다).
 *       표본이 적으면(타자 50타석·투수 10이닝 미만) percentiles 자체가 null이다(값은 그대로 나온다).
 *       견줄 선수가 아직 없거나(시즌 초) 이 선수의 그 값이 없으면 그 칸만 null이다.</li>
 *   <li>반올림: WAR·득점은 소수 둘째 자리, wRC+·ERA-·FIP-·K%·BB%는 소수 첫째 자리</li>
 * </ul>
 */
public sealed interface PlayerSaber permits PlayerSaber.Hitter, PlayerSaber.Pitcher {

    /** 저장된 줄 → 응답. 줄이 없거나 그 시즌 리그 기록에 이 선수가 없으면(시즌 전, 마이너에만 있음) null */
    static PlayerSaber of(PlayerLeaguePosition row) {
        if (row == null || !row.inLeague()) {
            return null;
        }
        if (row.getStatGroup() == PlayerSeasonMetric.Group.PITCHING) {
            PitcherPercentiles percentiles = row.getPctOverall() == null && row.getPctRunPrevention() == null
                    && row.getPctStrikeouts() == null && row.getPctWalks() == null ? null
                    : new PitcherPercentiles(row.getPctOverall(), row.getPctRunPrevention(), row.getPctStrikeouts(),
                    row.getPctWalks());
            return new Pitcher(row.getSeason(), round(row.getWar(), 2), poolSize(row), row.getInningsOuts(),
                    round(row.getEraMinus(), 1), round(row.getFipMinus(), 1), round(row.getStrikeoutRate(), 1),
                    round(row.getWalkRate(), 1), percentiles);
        }
        HitterPercentiles percentiles = row.getPctOverall() == null && row.getPctBatting() == null
                && row.getPctBaseRunning() == null && row.getPctFielding() == null ? null
                : new HitterPercentiles(row.getPctOverall(), row.getPctBatting(), row.getPctBaseRunning(),
                row.getPctFielding());
        return new Hitter(row.getSeason(), round(row.getWar(), 2), poolSize(row), row.getPlateAppearances(),
                round(row.getWrcPlus(), 1), round(row.getBattingRuns(), 2), round(row.getBaseRunningRuns(), 2),
                round(row.getFieldingRuns(), 2), percentiles);
    }

    private static int poolSize(PlayerLeaguePosition row) {
        return row.getPoolSize() == null ? 0 : row.getPoolSize();
    }

    private static Double round(Double value, int scale) {
        return BaseballMath.round(value, scale);
    }

    /**
     * 타자. batting은 타격 득점, baseRunning은 주루 득점, fielding은 수비 득점(MLB fielding + positional, 포지션 난도 포함)으로
     * 모두 리그 평균 대비 득점(+면 평균보다 낫다)이다.
     */
    record Hitter(int season, Double war, int poolSize, Integer plateAppearances, Double wrcPlus, Double batting,
                  Double baseRunning, Double fielding, HitterPercentiles percentiles) implements PlayerSaber {
    }

    /** 타자 위치: 종합(타석당 WAR), 타격(wRC+), 주루·수비(타석당 득점) */
    record HitterPercentiles(Integer overall, Integer batting, Integer baseRunning, Integer fielding) {
    }

    /** 투수. inningsOuts는 이닝을 아웃 수로(50⅔이닝 = 152), strikeoutRate·walkRate는 상대 타자 중 비율(%, 0~100)이다. */
    record Pitcher(int season, Double war, int poolSize, Integer inningsOuts, Double eraMinus, Double fipMinus,
                   Double strikeoutRate, Double walkRate, PitcherPercentiles percentiles) implements PlayerSaber {
    }

    /** 투수 위치: 종합(아웃당 WAR), 실점 억제(ERA- 낮을수록), 삼진(K% 높을수록), 볼넷(BB% 낮을수록) */
    record PitcherPercentiles(Integer overall, Integer runPrevention, Integer strikeouts, Integer walks) {
    }
}
