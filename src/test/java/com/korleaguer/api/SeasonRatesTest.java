package com.korleaguer.api;

import com.korleaguer.api.SeasonRates.Rates;
import com.korleaguer.api.SeasonReports.GameDay;
import com.korleaguer.api.SeasonReports.Input;
import com.korleaguer.api.SeasonVerdict.Line;
import com.korleaguer.domain.Batting;
import com.korleaguer.domain.Pitching;
import com.korleaguer.domain.Role;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 성적표 숫자 칸의 시즌 wRC+·ERA+(이슈 #179): 메이저는 공식 값, 마이너는 단계 리그 평균과 견준 값, 표본이 작으면 null.
 * 리그 평균은 2026 정규시즌 값(MLB 공식 API 팀 합계를 LeagueEnvironments와 같이 더한 값: wOBA MLB .3152·트리플A .3382·
 * 더블A .3315)을 반올림한 것이고, 경기별 기록은 합계만 맞춘 지어낸 값이다.
 */
class SeasonRatesTest {

    private static final LocalDate OPENING = LocalDate.of(2026, 3, 26);
    private static final LocalDate LAST_DAY = LocalDate.of(2026, 9, 27);
    private static final LeagueEnvironments.Env MLB = new LeagueEnvironments.Env(0.315, 0.118, 4.17);
    private static final LeagueEnvironments.Env AAA = new LeagueEnvironments.Env(0.338, 0.138, 4.99);
    private static final LeagueEnvironments.Env AA = new LeagueEnvironments.Env(0.332, 0.137, 4.85);
    private static final Map<String, LeagueEnvironments.Env> LEAGUES = Map.of("MLB", MLB, "AAA", AAA, "AA", AA);

    @Test
    void majorHitterUsesTheOfficialSeasonWrcPlus() {
        // 이정후: 공식 시즌 wRC+ 101.8(구장 보정 포함)을 반올림한다. 달 막대를 시즌 하나로 합친 값과 같다
        List<GameDay> majors = hitGames(null, OPENING, 143, 539, 149, 31, 10, 29);

        Rates rates = SeasonRates.of(hitter(new Line(2026, 579, 0, 101.8, 0.97), majors, List.of()), false, LEAGUES);

        assertThat(rates).isEqualTo(new Rates(102, null, null));
    }

    @Test
    void majorHitterWithoutTheOfficialValueIsMeasuredAgainstTheMlbAverage() {
        // 지표를 아직 받지 못했다: 400타수 110안타 2루타 20 홈런 12 볼넷 40 → wOBA .332, MLB 평균(.315) 대비 112
        List<GameDay> majors = hitGames(null, OPENING, 110, 400, 110, 20, 12, 40);

        assertThat(SeasonRates.of(hitter(null, majors, List.of()), false, LEAGUES).wrcPlus()).isEqualTo(112);
        assertThat(SeasonRates.of(hitter(new Line(2026, 440, 0, null, null), majors, List.of()), false, LEAGUES)
                .wrcPlus()).isEqualTo(112);
    }

    @Test
    void fewPlateAppearancesGiveNoSeasonWrcPlus() {
        // 배지환 메이저 4타석 wRC+ 249.4는 시즌 숫자로 쓰지 않는다(50타석 미만). 경기별 기록도 같은 선
        List<GameDay> fourGames = hitGames(null, LocalDate.of(2026, 8, 10), 4, 2, 1, 0, 0, 2);
        assertThat(SeasonRates.of(hitter(new Line(2026, 4, 0, 249.4, 0.09), fourGames, List.of()), false, LEAGUES))
                .isEqualTo(new Rates(null, null, null));

        List<GameDay> justEnough = hitGames(null, OPENING, 13, 46, 12, 2, 1, 4);
        List<GameDay> oneShort = hitGames(null, OPENING, 13, 45, 12, 2, 1, 4);
        assertThat(SeasonRates.of(hitter(null, justEnough, List.of()), false, LEAGUES).wrcPlus()).isNotNull();
        assertThat(SeasonRates.of(hitter(null, oneShort, List.of()), false, LEAGUES).wrcPlus()).isNull();
    }

    @Test
    void minorSeasonIsMeasuredAgainstItsOwnLeague() {
        // 김혜성 트리플A처럼: 85경기 328타수 86안타(.262) 2루타 12 홈런 3 볼넷 34 → wOBA .296.
        // 트리플A 평균(.338)과 견주면 76이고, MLB 표준값과 견주면 91로 실제보다 좋아 보인다. 메이저 공식 값(83)은 쓰지 않는다
        List<GameDay> majors = hitGames(null, OPENING, 43, 116, 30, 3, 1, 12);
        List<GameDay> minors = hitGames("AAA", LocalDate.of(2026, 5, 29), 85, 328, 86, 12, 3, 34);
        Input input = hitter(new Line(2026, 131, 0, 83.0, 0.35), majors, minors);

        assertThat(SeasonRates.of(input, true, LEAGUES)).isEqualTo(new Rates(76, null, "트리플A"));
        assertThat(SeasonRates.of(input, true, Map.of()).wrcPlus()).isEqualTo(91);
    }

    @Test
    void mixedMinorLevelsBlendTheLeagueAveragesByPlateAppearances() {
        // 트리플A 200타석 + 더블A 100타석 → 리그 평균을 2:1로 섞는다. 트리플A로만 재면 109, 더블A로만 재면 113
        List<GameDay> minors = new ArrayList<>();
        minors.addAll(hitGames("AAA", LocalDate.of(2026, 4, 1), 50, 180, 50, 10, 5, 20));
        minors.addAll(hitGames("AA", LocalDate.of(2026, 6, 1), 25, 90, 30, 5, 3, 10));

        Rates rates = SeasonRates.of(hitter(null, List.of(), minors), true, LEAGUES);

        assertThat(rates).isEqualTo(new Rates(110, null, "마이너"));
    }

    @Test
    void cardWithoutMajorGamesUsesTheMinorTotalsLikeTheWebNumbersBox() {
        // 메이저 경기 없이 더블A만 뛰었으면 성적표 level은 MLB여도 웹 숫자 칸은 마이너 합계다
        List<GameDay> minors = hitGames("AA", LocalDate.of(2026, 5, 1), 35, 120, 36, 6, 2, 12);

        assertThat(SeasonRates.of(hitter(null, List.of(), minors), false, LEAGUES))
                .isEqualTo(new Rates(103, null, "더블A"));
    }

    @Test
    void unknownMinorLevelMixedInMakesTheLevelNameMinors() {
        // 단계를 모르는 마이너 경기(level null)는 표준값으로 재므로, 트리플A와 섞이면 '트리플A'가 아니라 '마이너'다
        // (MinorRecords.levelKo와 같은 규칙, 이슈 #179 리뷰). 마이너 목록의 null 단계는 메이저가 아니라 '모름'이다
        List<GameDay> tripleA = hitGames("AAA", LocalDate.of(2026, 5, 1), 30, 100, 30, 5, 2, 10);
        List<GameDay> withUnknown = new ArrayList<>(tripleA);
        withUnknown.addAll(hitGames(null, LocalDate.of(2026, 7, 1), 10, 30, 8, 1, 0, 3));

        assertThat(SeasonRates.of(hitter(null, List.of(), tripleA), true, LEAGUES).statLevelKo()).isEqualTo("트리플A");
        Rates mixed = SeasonRates.of(hitter(null, List.of(), withUnknown), true, LEAGUES);
        assertThat(mixed.wrcPlus()).isNotNull();
        assertThat(mixed.statLevelKo()).isEqualTo("마이너");
        // 모두 모르면 MinorRecords.levelKo(null)과 같이 '마이너'
        List<GameDay> unknownOnly = hitGames(null, LocalDate.of(2026, 7, 1), 20, 70, 20, 3, 1, 7);
        assertThat(SeasonRates.of(hitter(null, List.of(), unknownOnly), true, LEAGUES).statLevelKo())
                .isEqualTo("마이너");
    }

    @Test
    void mlbCardWithTheOfficialValueButNoMajorGamesYetKeepsTheOfficialValue() {
        // 공식 지표는 받았는데 경기별 메이저 기록이 아직 없고 재활 마이너 경기만 있는 MLB 카드: 마이너 합계가 아니라 공식 값을
        // 쓰고 단계 이름은 없다. 같은 카드의 saber가 MLB 공식 값이다(이슈 #179 리뷰)
        List<GameDay> rehab = hitGames("AAA", LocalDate.of(2026, 6, 1), 15, 55, 20, 4, 2, 6);
        assertThat(SeasonRates.of(hitter(new Line(2026, 579, 0, 101.8, 0.97), List.of(), rehab), false, LEAGUES))
                .isEqualTo(new Rates(102, null, null));

        List<GameDay> rehabPitching = pitchGames("AAA", LocalDate.of(2026, 6, 1), 5, 30, 2);
        assertThat(SeasonRates.of(pitcher(new Line(2026, 60, 0, 80.0, 1.0), List.of(), rehabPitching), false,
                LEAGUES)).isEqualTo(new Rates(null, 125, null));

        // 공식 지표가 없으면(양만 있고 값이 없어도) 전처럼 마이너 합계다
        assertThat(SeasonRates.of(hitter(new Line(2026, 440, 0, null, null), List.of(), rehab), false, LEAGUES)
                .statLevelKo()).isEqualTo("트리플A");
    }

    @Test
    void fewMinorGamesGiveNeitherNumberNorLevelName() {
        // 트리플A 10경기 40타석, 9이닝: 숫자가 없으면 그 앞에 붙일 단계 이름도 없다
        List<GameDay> hitting = hitGames("AAA", LocalDate.of(2026, 9, 1), 10, 36, 10, 2, 1, 4);
        assertThat(SeasonRates.of(hitter(null, List.of(), hitting), true, LEAGUES))
                .isEqualTo(new Rates(null, null, null));

        List<GameDay> pitching = pitchGames("AAA", LocalDate.of(2026, 9, 1), 9, 27, 3);
        assertThat(SeasonRates.of(pitcher(null, List.of(), pitching), true, LEAGUES))
                .isEqualTo(new Rates(null, null, null));
    }

    @Test
    void minorPitcherBlendsLeagueEraByOuts() {
        // 고우석: 더블A 13⅔이닝 1자책 + 트리플A 37이닝 21자책 = 50⅔이닝 평균자책점 3.91.
        // 리그 평균자책점을 이닝 비중으로 섞으면 4.95 → ERA+ 127(표준값 4.20이면 107)
        List<GameDay> minors = new ArrayList<>();
        minors.addAll(pitchGames("AA", LocalDate.of(2026, 4, 5), 8, 41, 1));
        minors.addAll(pitchGames("AAA", LocalDate.of(2026, 5, 1), 28, 111, 21));
        List<GameDay> majors = pitchGames(null, LocalDate.of(2026, 7, 10), 4, 12, 4);
        Input input = pitcher(new Line(2026, 4, 0, 216.1, -0.14), majors, minors);

        assertThat(SeasonRates.of(input, true, LEAGUES)).isEqualTo(new Rates(null, 127, "마이너"));
        assertThat(SeasonRates.of(input, true, Map.of()).eraPlus()).isEqualTo(107);
    }

    @Test
    void majorPitcherUsesTheOfficialEraMinusAndFewInningsGiveNothing() {
        // 공식 ERA- 80 → ERA+ 125(10000 ÷ ERA-, 달별 기록과 같은 식). 고우석 메이저 4이닝(ERA- 216.1)은 10이닝 미만이라 null
        List<GameDay> majors = pitchGames(null, OPENING.plusDays(1), 30, 180, 20);
        assertThat(SeasonRates.of(pitcher(new Line(2026, 60, 0, 80.0, 1.0), majors, List.of()), false, LEAGUES))
                .isEqualTo(new Rates(null, 125, null));

        List<GameDay> fourGames = pitchGames(null, LocalDate.of(2026, 7, 10), 4, 12, 4);
        assertThat(SeasonRates.of(pitcher(new Line(2026, 4, 0, 216.1, -0.14), fourGames, List.of()), false, LEAGUES)
                .eraPlus()).isNull();
    }

    @Test
    void pitcherWithoutEraMinusIsMeasuredFromTheGames() {
        // ERA-를 아직 받지 못했다: 40이닝 16자책(3.60) vs MLB 평균 4.17 → 116
        List<GameDay> majors = pitchGames(null, OPENING.plusDays(1), 20, 120, 16);

        assertThat(SeasonRates.of(pitcher(null, majors, List.of()), false, LEAGUES).eraPlus()).isEqualTo(116);
        assertThat(SeasonRates.of(pitcher(new Line(2026, 40, 0, null, null), majors, List.of()), false, LEAGUES)
                .eraPlus()).isEqualTo(116);
    }

    @Test
    void scorelessPitcherGetsTheCap() {
        // 무자책이면 300(달별 기록과 같은 상한). 공식 ERA- 0도 무자책이다
        List<GameDay> scoreless = pitchGames("AAA", LocalDate.of(2026, 6, 1), 10, 30, 0);
        assertThat(SeasonRates.of(pitcher(null, List.of(), scoreless), true, LEAGUES).eraPlus())
                .isEqualTo(SeasonMonths.ERA_PLUS_CAP);

        List<GameDay> majors = pitchGames(null, OPENING.plusDays(1), 12, 36, 0);
        assertThat(SeasonRates.of(pitcher(new Line(2026, 12, 0, 0.0, 0.5), majors, List.of()), false, LEAGUES)
                .eraPlus()).isEqualTo(SeasonMonths.ERA_PLUS_CAP);
    }

    // ---- 재료 ----

    private static Input hitter(Line mlb, List<GameDay> majors, List<GameDay> minors) {
        return new Input(1, Role.HITTER, 2026, OPENING, LAST_DAY, false, mlb, List.of(), majors, minors, List.of());
    }

    private static Input pitcher(Line mlb, List<GameDay> majors, List<GameDay> minors) {
        return new Input(2, Role.PITCHER, 2026, OPENING, LAST_DAY, false, mlb, List.of(), majors, minors, List.of());
    }

    /** from부터 하루 한 경기씩 count경기. 합계가 주어진 값이 되게 나눈다(wOBA는 합계로만 정해진다). level이 null이면 메이저 */
    private static List<GameDay> hitGames(String level, LocalDate from, int count, int atBats, int hits, int doubles,
                                          int homeRuns, int walks) {
        List<GameDay> games = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int ab = share(atBats, count, i);
            int bb = share(walks, count, i);
            Batting batting = new Batting(ab, 0, share(hits, count, i), share(doubles, count, i), 0,
                    share(homeRuns, count, i), 0, bb, 0, 0, 0, 0, ab + bb, null);
            games.add(new GameDay(from.plusDays(i), level, null, null, batting, null, false, null));
        }
        return games;
    }

    /** from부터 하루 한 등판씩 count등판. 아웃·자책 합계가 outs·earnedRuns가 되게 나눈다 */
    private static List<GameDay> pitchGames(String level, LocalDate from, int count, int outs, int earnedRuns) {
        List<GameDay> games = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Pitching pitching = new Pitching(share(outs, count, i), 0, share(earnedRuns, count, i),
                    share(earnedRuns, count, i), 0, 1, 0, 20, 0, 0, 0);
            games.add(new GameDay(from.plusDays(i), level, null, null, null, pitching, false, null));
        }
        return games;
    }

    /** total을 n개로 나눈 i번째 몫(앞쪽이 하나씩 더 많다) */
    private static int share(int total, int n, int i) {
        return total / n + (i < total % n ? 1 : 0);
    }
}
