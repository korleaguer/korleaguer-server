package com.korleaguer.api.season;

import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.Batting;
import com.korleaguer.domain.Game;
import com.korleaguer.domain.Pitching;
import com.korleaguer.domain.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 내 선수 카드 기간 wRC+·ERA+ 계산: 그 시즌 리그 평균 대비, 0~300으로 가두고, 근거가 모자라면 null */
class PeriodRatesTest {

    private static final LeagueEnv LEAGUE = new LeagueEnv(0.314, 0.117, 4.2);

    @Test
    @DisplayName("타자의 기간 wRC+는 그 시즌 리그 평균과 견준다")
    void hitterWrcPlusUsesLeagueAverage() {
        // given
        // 4타수 2안타(2루타 1) 볼넷 1: wOBA .565 → 리그 .314 기준 272.7
        Appearance game = batted(BattingLine.atBats(4).runs(1).hits(2).doubles(1).rbi(1).walks(1).build());

        // when // then
        assertThat(PeriodRates.wrcPlus(List.of(game), LEAGUE)).isEqualTo(272.7);
    }

    @Test
    @DisplayName("기간 wRC+는 0보다 낮거나 300보다 높게 나오지 않는다")
    void hitterWrcPlusIsClampedToZeroAndCap() {
        // given
        Appearance hitless = batted(BattingLine.atBats(4).strikeouts(2).build());

        // when // then
        assertThat(PeriodRates.wrcPlus(List.of(hitless), LEAGUE)).isEqualTo(0.0);

        // given
        Appearance slam = batted(BattingLine.atBats(4).runs(4).hits(4).homeRuns(4).rbi(4).build());

        // when // then
        assertThat(PeriodRates.wrcPlus(List.of(slam), LEAGUE)).isEqualTo(300.0);
    }

    @Test
    @DisplayName("2루타·3루타를 모르거나 타석이 없으면 기간 wRC+를 내지 않는다")
    void hitterWrcPlusIsNullWithoutDetailOrPlateAppearances() {
        // given
        // 2루타·3루타 칸이 없는 옛 기록
        Appearance old = batted(BattingLine.atBats(4).runs(1).hits(2).withoutExtraBases().rbi(1).walks(1)
                .totalBases(3).build());

        // when // then
        assertThat(PeriodRates.wrcPlus(List.of(old), LEAGUE)).isNull();
        assertThat(PeriodRates.wrcPlus(List.of(), LEAGUE)).isNull();

        // given
        Appearance empty = batted(BattingLine.atBats(0).build());

        // when // then
        assertThat(PeriodRates.wrcPlus(List.of(empty), LEAGUE)).isNull();
    }

    @Test
    @DisplayName("투수의 기간 ERA+는 그 시즌 리그 평균자책점과 견준다")
    void pitcherEraPlusUsesLeagueEra() {
        // given
        // 9이닝 7자책: ERA 7.00 → 4.2 ÷ 7 × 100 = 60.0
        Appearance start = pitched(new Pitching(27, 9, 7, 7, 2, 5, 1, 90, 0, 1, 0));

        // when // then
        assertThat(PeriodRates.eraPlus(List.of(start), LEAGUE)).isEqualTo(60.0);
    }

    @Test
    @DisplayName("기간 ERA+는 300을 넘지 않고, 아웃 수를 모르거나 등판이 없으면 내지 않는다")
    void pitcherEraPlusIsCappedAndNull() {
        // given
        Appearance scoreless = pitched(new Pitching(9, 1, 0, 0, 0, 3, 0, 30, 0, 0, 0));

        // when // then
        assertThat(PeriodRates.eraPlus(List.of(scoreless), LEAGUE)).isEqualTo(300.0);

        // given
        Appearance outsUnknown = pitched(new Pitching(null, 1, 0, 0, 0, 3, 0, 30, 0, 0, 0));

        // when // then
        assertThat(PeriodRates.eraPlus(List.of(outsUnknown), LEAGUE)).isNull();
        assertThat(PeriodRates.eraPlus(List.of(), LEAGUE)).isNull();
        // 한 점 주고 1아웃: ERA 27.00이라 상한 없이 15.6
        assertThat(PeriodRates.eraPlus(1, 1, LEAGUE)).isEqualTo(15.6);
    }

    @Test
    @DisplayName("공식 ERA-를 ERA+로 뒤집되 300에서 자르고 0이거나 없으면 내지 않는다")
    void eraPlusFromMinusFlipsOfficialEraMinus() {
        // when // then
        assertThat(PeriodRates.eraPlusFromMinus(80.0)).isEqualTo(125.0);
        assertThat(PeriodRates.eraPlusFromMinus(216.1)).isEqualTo(46.3);
        assertThat(PeriodRates.eraPlusFromMinus(1.0)).isEqualTo(300.0);
        assertThat(PeriodRates.eraPlusFromMinus(0.0)).isNull();
        assertThat(PeriodRates.eraPlusFromMinus(null)).isNull();
    }

    @Test
    @DisplayName("기간 경기가 모두 같은 단계일 때만 단계를 알려 주고 섞이거나 모르면 비운다")
    void singleLevelIsNullWhenMixed() {
        // when // then
        assertThat(PeriodRates.singleLevel(List.of(game("AAA"), game("AAA")))).isEqualTo("AAA");
        assertThat(PeriodRates.singleLevel(List.of(game("AA"), game("AAA")))).isNull();
        assertThat(PeriodRates.singleLevel(List.of(game(null)))).isNull();
    }

    @Test
    @DisplayName("아는 단계가 둘 이상일 때만 단계를 오간 것으로 본다")
    void mixedLevelsOnlyWhenTwoKnownLevels() {
        // when // then
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
    @DisplayName("같은 기록도 타자가 잘 치는 마이너 리그에서는 wRC+가 낮다")
    void minorLinesAreMeasuredAgainstTheirOwnLevel() {
        // given
        // 같은 기록이라도 타자 친화 리그(wOBA .338)에서는 메이저(.314)보다 낮게 나온다
        Batting line = BattingLine.atBats(4).runs(1).hits(2).doubles(1).rbi(1).walks(1).build();

        // when
        Double inMajors = PeriodRates.wrcPlusOf(List.of(line), LEAGUE);
        Double inTripleA = PeriodRates.wrcPlusOf(List.of(line), new LeagueEnv(0.338, 0.13, 4.8));

        // then
        assertThat(inTripleA).isLessThan(inMajors);

        // when // then
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
