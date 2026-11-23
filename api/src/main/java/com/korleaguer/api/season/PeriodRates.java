package com.korleaguer.api.season;

import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.Batting;
import com.korleaguer.domain.Pitching;
import com.korleaguer.support.BaseballMath;
import com.korleaguer.support.Woba;

import java.util.List;
import java.util.Objects;

/**
 * 내 선수 카드 기간의 wRC+·ERA+(100 = 그 시즌 메이저 리그 평균, 높을수록 잘함). 웹이 경기 기록으로 어림하던 것을(리그 wOBA .310·
 * 평균자책점 4.2 고정) 서버가 그 시즌 리그 평균(LeagueEnvironments)으로 낸다. 값은 0~300으로 가두고(몇 타수 무안타가 음수가 되거나
 * 무자책이 끝없이 커지지 않게, 성적표 ERA+ 상한과 같다), 근거가 모자라면 null이다. 구장 보정은 없다(공식 시즌 wRC+와 다르다).
 */
public final class PeriodRates {

    static final double CAP = SeasonMonths.ERA_PLUS_CAP;
    /** 세부 칸(2루타·3루타)이 있는 경기가 뛴 경기의 이 비율 아래면 근거가 모자라 null(옛 데이터) */
    private static final double MIN_DETAILED_SHARE = 0.8;

    private PeriodRates() {
    }

    /** 기간 타자 wRC+(소수 첫째 자리). 타격 기록이 없거나 세부 칸이 모자라거나 타석이 없으면 null */
    public static Double wrcPlus(List<Appearance> period, LeagueEnvironments.Env league) {
        return wrcPlusOf(period.stream().map(Appearance::getBatting).toList(), league);
    }

    /** 경기별 타격 줄(마이너 경기 포함)로 낸 기간 wRC+. 규칙은 wrcPlus와 같다 */
    static Double wrcPlusOf(List<Batting> lines, LeagueEnvironments.Env league) {
        if (league == null) {
            return null;
        }
        List<Batting> batted = lines.stream().filter(Objects::nonNull).toList();
        List<Batting> full = batted.stream().filter(b -> b.doubles() != null && b.triples() != null).toList();
        if (full.isEmpty() || full.size() < batted.size() * MIN_DETAILED_SHARE) {
            return null;
        }
        Woba.Sum sum = new Woba.Sum();
        for (Batting b : full) {
            sum.add(orZero(b.atBats()), orZero(b.hits()), orZero(b.doubles()), orZero(b.triples()),
                    orZero(b.homeRuns()), orZero(b.walks()), orZero(b.hitByPitch()), orZero(b.sacFlies()));
        }
        Double woba = sum.woba();
        return woba == null ? null : BaseballMath.round(clamp(WrcMath.wrcPlus(woba, league)), 1);
    }

    /** 기간 투수 ERA+(소수 첫째 자리). 아웃을 아는 경기가 없으면 null, 무자책이면 상한 */
    public static Double eraPlus(List<Appearance> period, LeagueEnvironments.Env league) {
        return eraPlusOf(period.stream().map(Appearance::getPitching).toList(), league);
    }

    /** 경기별 투구 줄(마이너 경기 포함)로 낸 기간 ERA+. 규칙은 eraPlus와 같다 */
    static Double eraPlusOf(List<Pitching> lines, LeagueEnvironments.Env league) {
        if (league == null) {
            return null;
        }
        List<Pitching> known = lines.stream()
                .filter(p -> p != null && p.pitchingOuts() != null).toList();
        int outs = known.stream().mapToInt(Pitching::pitchingOuts).sum();
        int earnedRuns = known.stream().mapToInt(p -> orZero(p.earnedRuns())).sum();
        return eraPlus(earnedRuns, outs, league);
    }

    public static Double eraPlus(int earnedRuns, int outs, LeagueEnvironments.Env league) {
        if (outs == 0) {
            return null;
        }
        if (earnedRuns == 0) {
            return CAP;
        }
        return BaseballMath.round(clamp(100 * league.era() / (earnedRuns * 27.0 / outs)), 1);
    }

    /** 공식 ERA-(낮을수록 잘함)를 ERA+로. 값이 없거나 0 이하면 null (SeasonRates와 같은 식, 10000 ÷ ERA-) */
    static Double eraPlusFromMinus(Double eraMinus) {
        return eraMinus == null || eraMinus <= 0 ? null : BaseballMath.round(clamp(10000 / eraMinus), 1);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(CAP, value));
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    /** 마이너 경기들이 모두 한 단계면 그 약어, 섞였거나 단계를 모르면 null (여러 단계를 한 평균에 견주지 않는다) */
    static String singleLevel(List<com.korleaguer.domain.MinorGameLog> games) {
        List<String> levels = games.stream().map(com.korleaguer.domain.MinorGameLog::getLevel).distinct().toList();
        return levels.size() == 1 ? levels.getFirst() : null;
    }

    /** 마이너 경기들이 아는 단계 둘 이상에 걸쳐 있는가(단계를 모르는 경기는 세지 않는다). 이때 singleLevel은 늘 null이다 */
    static boolean mixedLevels(List<com.korleaguer.domain.MinorGameLog> games) {
        return games.stream().map(com.korleaguer.domain.MinorGameLog::getLevel)
                .filter(java.util.Objects::nonNull).distinct().count() > 1;
    }
}
