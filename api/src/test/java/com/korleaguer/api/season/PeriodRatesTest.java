package com.korleaguer.api.season;

import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.Batting;
import com.korleaguer.domain.Game;
import com.korleaguer.domain.Pitching;
import com.korleaguer.domain.Player;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 내 선수 카드 기간 wRC+·ERA+ 계산: 그 시즌 리그 평균 대비, 0~300으로 가두고, 근거가 모자라면 null */
class PeriodRatesTest {

    private static final LeagueEnv LEAGUE = new LeagueEnv(0.314, 0.117, 4.2);

    @Test
    void hitterWrcPlusUsesLeagueAverage() {
        // 4타수 2안타(2루타 1) 볼넷 1: wOBA .565 → 리그 .314 기준 272.7
        Appearance game = batted(new Batting(4, 1, 2, 1, 0, 0, 1, 1, 0, 0, 0, 0, 5, 3));
        assertThat(PeriodRates.wrcPlus(List.of(game), LEAGUE)).isEqualTo(272.7);
    }

    @Test
    void hitterWrcPlusIsClampedToZeroAndCap() {
        Appearance hitless = batted(new Batting(4, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 4, 0));
        assertThat(PeriodRates.wrcPlus(List.of(hitless), LEAGUE)).isEqualTo(0.0);
        Appearance slam = batted(new Batting(4, 4, 4, 0, 0, 4, 4, 0, 0, 0, 0, 0, 4, 16));
        assertThat(PeriodRates.wrcPlus(List.of(slam), LEAGUE)).isEqualTo(300.0);
    }

    @Test
    void hitterWrcPlusIsNullWithoutDetailOrPlateAppearances() {
        // 2루타·3루타 칸이 없는 옛 기록
        Appearance old = batted(new Batting(4, 1, 2, null, null, 0, 1, 1, 0, 0, 0, 0, 5, 3));
        assertThat(PeriodRates.wrcPlus(List.of(old), LEAGUE)).isNull();
        assertThat(PeriodRates.wrcPlus(List.of(), LEAGUE)).isNull();
        Appearance empty = batted(new Batting(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThat(PeriodRates.wrcPlus(List.of(empty), LEAGUE)).isNull();
    }

    @Test
    void pitcherEraPlusUsesLeagueEra() {
        // 9이닝 7자책: ERA 7.00 → 4.2 ÷ 7 × 100 = 60.0
        Appearance start = pitched(new Pitching(27, 9, 7, 7, 2, 5, 1, 90, 0, 1, 0));
        assertThat(PeriodRates.eraPlus(List.of(start), LEAGUE)).isEqualTo(60.0);
    }

    @Test
    void pitcherEraPlusIsCappedAndNull() {
        Appearance scoreless = pitched(new Pitching(9, 1, 0, 0, 0, 3, 0, 30, 0, 0, 0));
        assertThat(PeriodRates.eraPlus(List.of(scoreless), LEAGUE)).isEqualTo(300.0);
        Appearance outsUnknown = pitched(new Pitching(null, 1, 0, 0, 0, 3, 0, 30, 0, 0, 0));
        assertThat(PeriodRates.eraPlus(List.of(outsUnknown), LEAGUE)).isNull();
        assertThat(PeriodRates.eraPlus(List.of(), LEAGUE)).isNull();
        // 한 점 주고 1아웃: ERA 27.00이라 상한 없이 15.6
        assertThat(PeriodRates.eraPlus(1, 1, LEAGUE)).isEqualTo(15.6);
    }

    @Test
    void eraPlusFromMinusFlipsOfficialEraMinus() {
        assertThat(PeriodRates.eraPlusFromMinus(80.0)).isEqualTo(125.0);
        assertThat(PeriodRates.eraPlusFromMinus(216.1)).isEqualTo(46.3);
        assertThat(PeriodRates.eraPlusFromMinus(1.0)).isEqualTo(300.0);
        assertThat(PeriodRates.eraPlusFromMinus(0.0)).isNull();
        assertThat(PeriodRates.eraPlusFromMinus(null)).isNull();
    }

    @Test
    void singleLevelIsNullWhenMixed() {
        assertThat(PeriodRates.singleLevel(List.of(game("AAA"), game("AAA")))).isEqualTo("AAA");
        assertThat(PeriodRates.singleLevel(List.of(game("AA"), game("AAA")))).isNull();
        assertThat(PeriodRates.singleLevel(List.of(game(null)))).isNull();
    }

    @Test
    void mixedLevelsOnlyWhenTwoKnownLevels() {
        assertThat(PeriodRates.mixedLevels(List.of(game("AAA"), game("AAA")))).isFalse();
        assertThat(PeriodRates.mixedLevels(List.of(game("ROK"), game("AA"), game("AAA")))).isTrue();
        // 단계를 모르는 경기는 '오갔다'의 근거가 아니다
        assertThat(PeriodRates.mixedLevels(List.of(game("AAA"), game(null)))).isFalse();
        assertThat(PeriodRates.mixedLevels(List.of())).isFalse();
    }

    private static com.korleaguer.domain.MinorGameLog game(String level) {
        var game = new com.korleaguer.domain.MinorGameLog(new Player("선수"), 2026, 1L);
        game.updateGame(java.time.LocalDate.of(2026, 6, 1), level, "T", "O", true, true);
        return game;
    }

    @Test
    void minorLinesAreMeasuredAgainstTheirOwnLevel() {
        // 같은 기록이라도 타자 친화 리그(wOBA .338)에서는 메이저(.314)보다 낮게 나온다
        Batting line = new Batting(4, 1, 2, 1, 0, 0, 1, 1, 0, 0, 0, 0, 5, 3);
        Double inMajors = PeriodRates.wrcPlusOf(List.of(line), LEAGUE);
        Double inTripleA = PeriodRates.wrcPlusOf(List.of(line), new LeagueEnv(0.338, 0.13, 4.8));
        assertThat(inTripleA).isLessThan(inMajors);
        assertThat(PeriodRates.wrcPlusOf(List.of(line), null)).isNull();
        assertThat(PeriodRates.eraPlusOf(List.of(), null)).isNull();
    }

    private static Appearance batted(Batting batting) {
        Appearance appearance = new Appearance(new Player("타자"), new Game(1L));
        appearance.update(null, batting, null);
        return appearance;
    }

    private static Appearance pitched(Pitching pitching) {
        Appearance appearance = new Appearance(new Player("투수"), new Game(1L));
        appearance.update(null, null, pitching);
        return appearance;
    }
}
