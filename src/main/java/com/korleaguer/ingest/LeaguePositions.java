package com.korleaguer.ingest;

import com.korleaguer.client.MlbSeasonSplit;
import com.korleaguer.domain.PlayerLeaguePosition;
import com.korleaguer.support.BaseballMath;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * MLB 리그 전체 기록으로 우리 선수의 'MLB 안 위치'(백분위)를 계산한다(이슈 #133). 값은 MLB sabermetrics 그대로 쓰고,
 * 누구와 무엇으로 견주나만 여기서 정한다.
 * <ul>
 *   <li>타자: 그 시즌 100타석 이상 MLB 타자와 견준다. 종합은 타석당 WAR, 타격은 wRC+, 주루·수비는 타석당 득점
 *       (수비 = fielding + positional, 포지션 난도 포함). 50타석 미만이면 위치는 null이다(값은 그대로).</li>
 *   <li>투수: 30이닝 이상 MLB 투수와 견준다. 종합은 아웃당 WAR, 실점 억제는 ERA-(낮을수록 좋다), 삼진은 K%,
 *       볼넷은 BB%(낮을수록 좋다). 10이닝 미만이면 위치는 null이다.</li>
 *   <li>위치 = 견준 선수 중 이 선수보다 값이 나쁜 선수의 비율 × 100(반올림, 1~99로 묶는다). 그 값이 없는 선수는 그 줄 비교에서 뺀다.</li>
 * </ul>
 */
final class LeaguePositions {

    /** 견줄 타자: 이만큼 이상 타석에 선 MLB 타자 */
    static final int HITTER_POOL_PLATE_APPEARANCES = 100;
    /** 우리 타자가 이보다 적게 타석에 섰으면 위치를 내지 않는다(값만 보인다) */
    static final int HITTER_MIN_PLATE_APPEARANCES = 50;
    /** 견줄 투수: 30이닝(90아웃) 이상 던진 MLB 투수 */
    static final int PITCHER_POOL_OUTS = 90;
    /** 우리 투수가 10이닝(30아웃)보다 적게 던졌으면 위치를 내지 않는다 */
    static final int PITCHER_MIN_OUTS = 30;

    private LeaguePositions() {
    }

    /** 리그 전체 타자 한 줄(MLB 값 그대로). fieldingRuns = fielding + positional */
    record HitterLine(long mlbId, int plateAppearances, Double wrcPlus, Double war, Double battingRuns,
                      Double baseRunningRuns, Double fieldingRuns) {
    }

    /** 리그 전체 투수 한 줄(MLB 값 그대로). strikeoutRate·walkRate는 상대 타자 중 비율(%)이고, 상대 타자 수를 모르면 null */
    record PitcherLine(long mlbId, int outs, Double eraMinus, Double fipMinus, Double war, Double strikeoutRate,
                       Double walkRate) {
    }

    /** 리그 전체 시즌 합계·고급 지표 → MLB 선수 번호마다 타자 한 줄. 시즌 합계(타석)가 없는 선수는 뺀다 */
    static Map<Long, HitterLine> hitters(List<MlbSeasonSplit> seasonLines, List<MlbSeasonSplit> saberLines) {
        Map<Long, MlbSeasonSplit.Stat> saber = byPlayer(saberLines);
        Map<Long, HitterLine> result = new HashMap<>();
        byPlayer(seasonLines).forEach((mlbId, season) -> {
            MlbSeasonSplit.Stat s = saber.get(mlbId);
            result.put(mlbId, new HitterLine(mlbId, orZero(season.plateAppearances()),
                    s == null ? null : s.wRcPlus(), s == null ? null : s.war(), s == null ? null : s.batting(),
                    s == null ? null : s.baseRunning(), s == null ? null : sum(s.fielding(), s.positional())));
        });
        return result;
    }

    /** 리그 전체 시즌 합계·고급 지표 → MLB 선수 번호마다 투수 한 줄. 시즌 합계(이닝)가 없는 선수는 뺀다 */
    static Map<Long, PitcherLine> pitchers(List<MlbSeasonSplit> seasonLines, List<MlbSeasonSplit> saberLines) {
        Map<Long, MlbSeasonSplit.Stat> saber = byPlayer(saberLines);
        Map<Long, PitcherLine> result = new HashMap<>();
        byPlayer(seasonLines).forEach((mlbId, season) -> {
            MlbSeasonSplit.Stat s = saber.get(mlbId);
            Integer outs = BaseballMath.outsFromInnings(season.inningsPitched());
            result.put(mlbId, new PitcherLine(mlbId, outs == null ? 0 : outs,
                    s == null ? null : s.eraMinus(), s == null ? null : s.fipMinus(), s == null ? null : s.war(),
                    rate(season.strikeOuts(), season.battersFaced()), rate(season.baseOnBalls(), season.battersFaced())));
        });
        return result;
    }

    /** 이 타자의 값과 위치. league는 리그 전체 타자(이 타자 포함) */
    static PlayerLeaguePosition.Hitting hitting(HitterLine me, Collection<HitterLine> league) {
        List<HitterLine> pool = league.stream()
                .filter(l -> l.plateAppearances() >= HITTER_POOL_PLATE_APPEARANCES)
                .toList();
        boolean enough = me.plateAppearances() >= HITTER_MIN_PLATE_APPEARANCES;
        return new PlayerLeaguePosition.Hitting(me.plateAppearances(), me.wrcPlus(), me.war(), me.battingRuns(),
                me.baseRunningRuns(), me.fieldingRuns(), pool.size(),
                enough ? percentile(me, pool, l -> perPlateAppearance(l.war(), l), true) : null,
                enough ? percentile(me, pool, HitterLine::wrcPlus, true) : null,
                enough ? percentile(me, pool, l -> perPlateAppearance(l.baseRunningRuns(), l), true) : null,
                enough ? percentile(me, pool, l -> perPlateAppearance(l.fieldingRuns(), l), true) : null);
    }

    /** 이 투수의 값과 위치. league는 리그 전체 투수(이 투수 포함) */
    static PlayerLeaguePosition.Pitching pitching(PitcherLine me, Collection<PitcherLine> league) {
        List<PitcherLine> pool = league.stream()
                .filter(l -> l.outs() >= PITCHER_POOL_OUTS)
                .toList();
        boolean enough = me.outs() >= PITCHER_MIN_OUTS;
        return new PlayerLeaguePosition.Pitching(me.outs(), me.eraMinus(), me.fipMinus(), me.strikeoutRate(),
                me.walkRate(), me.war(), pool.size(),
                enough ? percentile(me, pool, l -> l.war() == null || l.outs() == 0 ? null : l.war() / l.outs(), true)
                        : null,
                enough ? percentile(me, pool, PitcherLine::eraMinus, false) : null,
                enough ? percentile(me, pool, PitcherLine::strikeoutRate, true) : null,
                enough ? percentile(me, pool, PitcherLine::walkRate, false) : null);
    }

    /**
     * 견준 선수(pool) 중 me보다 값이 나쁜 선수의 비율(1~99). me나 견줄 값이 없으면 null.
     *
     * @param higherIsBetter 값이 클수록 좋은가(ERA-·BB%는 false)
     */
    static <T> Integer percentile(T me, List<T> pool, Function<T, Double> value, boolean higherIsBetter) {
        Double mine = value.apply(me);
        if (mine == null) {
            return null;
        }
        List<Double> others = pool.stream().map(value).filter(Objects::nonNull).toList();
        if (others.isEmpty()) {
            return null;
        }
        long worse = others.stream().filter(v -> higherIsBetter ? v < mine : v > mine).count();
        long percent = Math.round(worse * 100.0 / others.size());
        return (int) Math.max(1, Math.min(99, percent));
    }

    /**
     * MLB 선수 번호 → 한 줄. 리그 전체 응답은 선수마다 한 줄(여러 팀이면 합친 줄)이지만, 같은 선수가 두 번 오면
     * 팀 수(numTeams)가 많은 줄(합친 줄)을 쓴다.
     */
    private static Map<Long, MlbSeasonSplit.Stat> byPlayer(List<MlbSeasonSplit> lines) {
        Map<Long, MlbSeasonSplit> chosen = new HashMap<>();
        for (MlbSeasonSplit line : lines) {
            if (line.stat() == null || line.player() == null || line.player().id() == null) {
                continue;
            }
            chosen.merge(line.player().id(), line, (a, b) -> teams(b) > teams(a) ? b : a);
        }
        Map<Long, MlbSeasonSplit.Stat> result = new HashMap<>();
        chosen.forEach((id, line) -> result.put(id, line.stat()));
        return result;
    }

    private static int teams(MlbSeasonSplit line) {
        return line.numTeams() == null ? 1 : line.numTeams();
    }

    private static Double perPlateAppearance(Double runs, HitterLine line) {
        return runs == null || line.plateAppearances() == 0 ? null : runs / line.plateAppearances();
    }

    private static Double rate(Integer count, Integer battersFaced) {
        return count == null || battersFaced == null || battersFaced == 0 ? null : count * 100.0 / battersFaced;
    }

    private static Double sum(Double a, Double b) {
        if (a == null && b == null) {
            return null;
        }
        return (a == null ? 0 : a) + (b == null ? 0 : b);
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
