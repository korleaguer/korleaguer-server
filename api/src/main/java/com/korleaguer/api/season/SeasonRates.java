package com.korleaguer.api.season;

import com.korleaguer.api.season.SeasonReports.GameDay;
import com.korleaguer.domain.Role;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 성적표 숫자 칸의 시즌 wRC+·ERA+와 그 단계 이름(SeasonReport의 wrcPlus·eraPlus·statLevelKo, 이슈 #179).
 * 홈 '2026 시즌 성적표'를 '목록 + 누르면 펼치기'로 바꾸면 목록 한 줄에 타율·평균자책점 대신 이 값을 보인다
 * (사용자 11-17 결정. 타율 하나로 시즌을 말하지 않는다).
 * <ul>
 *   <li>단계: 웹 숫자 칸과 같은 합계를 잰다. 성적표 level이 MINORS면 마이너, 아니면 메이저다. 메이저 경기가 하나도 없고
 *       마이너 경기만 있으면 웹이 마이너 합계를 크게 보이므로(SeasonReportCard) 마이너를 잰다. 다만 공식 지표를 받았으면
 *       경기별 메이저 기록이 아직 없어도 MLB 카드라 공식 값을 쓴다(같은 카드의 saber와 같은 단계).</li>
 *   <li>메이저: MLB 공식 시즌 wRC+(player_season_metric, 구장 보정 포함)를 반올림한다. SeasonMonths가 메이저 달을 맞추는
 *       값이라 달 막대를 시즌 하나로 합친 값과 같다. 투수는 공식 ERA-로 낸 ERA+(10000 ÷ ERA-, EraMath.fromMinus).
 *       공식 값을 아직 받지 못했으면 경기별 기록을 MLB 리그 평균과 견준다(보정 없는 메이저 달과 같다).</li>
 *   <li>마이너: 웹 숫자 칸(GET /api/home의 minorHitters·minorPitchers)과 같은 경기, 곧 그 시즌 마이너 경기 전부(재활 경기
 *       포함)를 더해 단계 리그 평균(LeagueEnvironments)과 견준다. 여러 단계를 뛰었으면 단계 리그 평균을 타석(투수는 아웃)
 *       비중으로 섞는다. 단계마다 '그 리그 평균 선수였다면 낸 득점·자책점'을 더해 견주는 것과 같은 뜻이다.</li>
 *   <li>ERA+: 높을수록 잘함, SeasonMonths.ERA_PLUS_CAP(300)까지, 무자책이면 300(달별 기록과 같은 규칙).</li>
 *   <li>표본이 작으면(타자 MIN_PLATE_APPEARANCES타석·투수 MIN_OUTS아웃 미만) null이다.</li>
 *   <li>statLevelKo: 숫자가 마이너 합계일 때 그 단계 이름(한 단계면 '트리플A', 섞였거나 단계를 모르는 경기가 끼었으면
 *       '마이너'). 메이저거나 숫자가 없으면 null. 홈 마이너 줄의 단계 이름(MinorRecords.levelKo)과 같은 규칙이다. 웹 숫자 칸
 *       위 '트리플A 기록' 이름표(/api/my 시즌 합계의 단계)와도 같되, 그쪽(MinorRecords.minors)은 단계를 모르는 경기를 빼고
 *       단계를 정해서 그런 경기가 낀 때만 다르다.</li>
 * </ul>
 */
final class SeasonRates {

    /**
     * 시즌 wRC+를 내는 최소 타석. 달 막대가 '경기 적은 달'로 옅게 그리는 선(타자 10경기, SeasonMonths.FEW_HITTER_GAMES)을
     * 타석으로 옮기면 주전 10경기 40타석 남짓이다. 그보다 조금 위인, 선수 화면 'MLB 안 위치'가 백분위를 숨기는 선
     * (50타석, ingest.LeaguePositions)에 맞춰, 펼친 카드에서 시즌 wRC+와 백분위(saber)가 함께 보이고 함께 빠지게 했다.
     * 배지환 메이저 4타석 wRC+ 249 같은 값은 시즌 숫자로 쓰지 않는다
     */
    static final int MIN_PLATE_APPEARANCES = 50;
    /**
     * 시즌 ERA+를 내는 최소 아웃(10이닝). 달 막대 선(투수 5등판, SeasonMonths.FEW_PITCHER_GAMES)은 구원 5이닝 ~ 선발 25이닝이고,
     * 그 사이에서 백분위를 숨기는 선(10이닝)과 맞췄다. 고우석 메이저 4이닝 ERA+ 46 같은 값은 시즌 숫자로 쓰지 않는다
     */
    static final int MIN_OUTS = 30;
    /** 여러 마이너 단계를 더한 숫자의 단계 이름 */
    static final String MIXED_MINORS = "마이너";

    private SeasonRates() {
    }

    /** 숫자 칸 단계의 시즌 wRC+(타자)·ERA+(투수)와, 그 숫자가 마이너 합계일 때 단계 이름. 모르면 각각 null */
    record Rates(Integer wrcPlus, Integer eraPlus, String statLevelKo) {
    }

    /**
     * @param minorsCard 성적표가 마이너 합계를 앞세우는지(level == MINORS)
     * @param leagues    단계(MLB·AAA 등)별 리그 평균. 없는 단계는 LeagueEnv.STANDARD
     */
    static Rates of(SeasonReports.Input in, boolean minorsCard, Map<String, LeagueEnv> leagues) {
        boolean pitcher = in.role() == Role.PITCHER;
        // 공식 지표(wRC+·ERA-)를 받았으면 그 값, 아니면 경기별 기록
        SeasonVerdict.Line official = in.mlb() != null && in.mlb().quality() != null ? in.mlb() : null;
        // 마이너 합계를 재는 때: 마이너 카드거나, 메이저 기록이 경기별로도 공식 지표로도 없을 때. 공식 지표가 있으면 경기별
        // 메이저 기록이 아직 없어도(재활 마이너 경기만 있어도) MLB 카드라 공식 값을 쓴다. 같은 카드의 saber가 MLB 공식
        // 값이라 목록 숫자도 같은 단계여야 한다(이슈 #179 리뷰)
        boolean minors = !in.minors().isEmpty() && (minorsCard || (in.majors().isEmpty() && official == null));
        if (minors) {
            Integer wrcPlus = pitcher ? null : wrcPlus(in.minors(), true, leagues);
            Integer eraPlus = pitcher ? eraPlus(in.minors(), true, leagues) : null;
            // 단계 이름은 숫자 앞에 붙는 이름표라, 표본이 작아 숫자가 없으면 이름도 없다
            String levelKo = wrcPlus == null && eraPlus == null ? null : levelKo(in.minors());
            return new Rates(wrcPlus, eraPlus, levelKo);
        }
        if (pitcher) {
            return new Rates(null, official != null ? officialEraPlus(official) : eraPlus(in.majors(), false, leagues),
                    null);
        }
        return new Rates(official != null ? officialWrcPlus(official) : wrcPlus(in.majors(), false, leagues), null,
                null);
    }

    /** 공식 시즌 wRC+ 반올림. 표본은 공식 타석으로 센다 */
    private static Integer officialWrcPlus(SeasonVerdict.Line official) {
        return official.volume() < MIN_PLATE_APPEARANCES ? null : (int) Math.round(official.quality());
    }

    /** 공식 ERA-로 낸 ERA+(10000 ÷ ERA-). ERA- 0은 무자책이라 상한. 표본은 공식 이닝으로 센다 */
    private static Integer officialEraPlus(SeasonVerdict.Line official) {
        if (Math.round(official.volume() * 3) < MIN_OUTS) {
            return null;
        }
        double eraMinus = official.quality();
        return eraMinus <= 0 ? SeasonMonths.ERA_PLUS_CAP
                : (int) Math.min(SeasonMonths.ERA_PLUS_CAP, Math.round(EraMath.fromMinus(eraMinus)));
    }

    /** 경기별 기록의 wRC+. 리그 평균은 경기 단계(minors가 아니면 모두 MLB)의 값을 타석 비중으로 섞는다 */
    private static Integer wrcPlus(List<GameDay> games, boolean minors, Map<String, LeagueEnv> leagues) {
        List<GameDay> batted = games.stream().filter(g -> g.batting() != null).toList();
        // 단계를 모르는 마이너 경기(null)는 표준값으로 잰다(SeasonMonths.league). HashMap이라 null 단계도 담는다
        Map<String, Integer> plateAppearances = new HashMap<>();
        for (GameDay game : batted) {
            plateAppearances.merge(minors ? game.level() : LeagueEnv.MLB,
                    game.batting().plateAppearancesOrComputed(), Integer::sum);
        }
        int total = plateAppearances.values().stream().mapToInt(Integer::intValue).sum();
        if (total < MIN_PLATE_APPEARANCES) {
            return null;
        }
        return SeasonMonths.wrcPlus(batted, mixed(plateAppearances, total, leagues), 0);
    }

    /** 경기별 기록의 ERA+. 아웃 수를 아는 경기만 센다(숫자 칸의 평균자책점과 같은 기준). 리그 평균은 아웃 비중으로 섞는다 */
    private static Integer eraPlus(List<GameDay> games, boolean minors, Map<String, LeagueEnv> leagues) {
        Map<String, Integer> outsByStage = new HashMap<>();
        int outs = 0;
        int earnedRuns = 0;
        for (GameDay game : games) {
            if (game.pitching() == null || game.pitching().pitchingOuts() == null) {
                continue;
            }
            int gameOuts = game.pitching().pitchingOuts();
            outsByStage.merge(minors ? game.level() : LeagueEnv.MLB, gameOuts, Integer::sum);
            outs += gameOuts;
            earnedRuns += SeasonReports.orZero(game.pitching().earnedRuns());
        }
        if (outs < MIN_OUTS) {
            return null;
        }
        return SeasonMonths.eraPlus(earnedRuns, outs, mixed(outsByStage, outs, leagues), 1);
    }

    /** 단계 리그 평균을 weights(타석·아웃) 비중으로 섞은 평균. total은 weights의 합(0보다 크다) */
    private static LeagueEnv mixed(Map<String, Integer> weights, int total,
                                                Map<String, LeagueEnv> leagues) {
        double woba = 0;
        double runsPerPa = 0;
        double era = 0;
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            LeagueEnv league = SeasonMonths.league(leagues, entry.getKey());
            double share = (double) entry.getValue() / total;
            woba += league.woba() * share;
            runsPerPa += league.runsPerPa() * share;
            era += league.era() * share;
        }
        return new LeagueEnv(woba, runsPerPa, era);
    }

    /**
     * 마이너 합계의 단계 이름: 모두 한 단계면 그 이름(트리플A), 단계가 섞였거나 단계를 모르는 경기(level null)가 끼었으면
     * '마이너'. 모르는 단계도 한 단계로 센다: 숫자는 그 경기를 표준값(LeagueEnv.STANDARD)으로 섞어 재므로
     * '트리플A'라 하면 잣대와 어긋난다(이슈 #179 리뷰). 모두 모르면 MinorRecords.levelKo(null)과 같이 '마이너'.
     * 홈 마이너 줄이 쓰는 MinorRecords.levelKo(마이너 경기 목록)와 같은 규칙이다(한쪽을 바꾸면 다른 쪽도 바꾼다)
     */
    private static String levelKo(List<GameDay> minors) {
        List<String> levels = minors.stream().map(GameDay::level).distinct().toList();
        return levels.size() == 1 ? MinorRecords.levelKo(levels.getFirst()) : MIXED_MINORS;
    }
}
