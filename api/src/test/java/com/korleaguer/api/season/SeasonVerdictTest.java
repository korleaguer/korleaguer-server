package com.korleaguer.api.season;

import com.korleaguer.api.season.SeasonVerdict.Line;
import com.korleaguer.domain.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 시즌 성적표 판정: 2026 실제 기록으로 정한 결과와 지난 시즌 확인 사례가 그대로 나와야 한다. */
class SeasonVerdictTest {

    @Test
    void leeJungHooPlayedTheFullSeasonAtLeagueAverage() {
        // 579타석, wRC+ 101.8, WAR 0.97
        assertThat(hitter(new Line(2026, 579, 0, 101.8, 0.97), 0,
                new Line(2024, 158, 0, 93.0, 0.3), new Line(2025, 617, 0, 107.0, 2.0)))
                .isEqualTo(SeasonTier.SOLID);
    }

    @Test
    void baeJiHwanSpentTheSeasonInTripleA() {
        // 메이저 4타석, 트리플A 374타석. 2023년(371타석)은 주전급이 아니었다
        assertThat(hitter(new Line(2026, 4, 0, 249.4, 0.09), 374, new Line(2023, 371, 0, 77.0, 0.3)))
                .isEqualTo(SeasonTier.MINORS);
    }

    @Test
    void kimHyeseongPlayedMoreInTripleA() {
        // 메이저 131타석, 트리플A 370타석
        assertThat(hitter(new Line(2026, 131, 0, 83.0, 0.35), 370, new Line(2025, 170, 0, 90.0, 0.4)))
                .isEqualTo(SeasonTier.MINORS);
    }

    @Test
    void goWooSukPitchedMostlyInTheMinors() {
        // 메이저 4이닝(ERA- 216은 어느 정도 뛴 기준 30이닝에 못 미쳐 보지 않는다), 마이너 50이닝
        assertThat(SeasonVerdict.judge(Role.PITCHER, new Line(2026, 4, 0, 216.1, -0.14), 50, List.of()))
                .isEqualTo(SeasonTier.MINORS);
    }

    @Test
    void songSungMunHitWellBelowAverage() {
        // 154타석(어느 정도 뛴 기준 150 이상), wRC+ 57.9
        assertThat(hitter(new Line(2026, 154, 0, 57.9, 0.24), 120)).isEqualTo(SeasonTier.POOR);
    }

    @Test
    void kimHaSeongWasARegularBeforeAndMissedMostOfTheSeason() {
        // 147타석은 150에 못 미치지만, 앞 시즌들(2023 626타석 WAR 4.4, 2024 470타석 WAR 2.6) 주전이었다
        assertThat(hitter(new Line(2026, 147, 0, 7.3, -1.17), 30,
                new Line(2023, 626, 0, 110.0, 4.4), new Line(2024, 470, 0, 101.0, 2.6),
                new Line(2025, 191, 0, 80.0, 0.1)))
                .isEqualTo(SeasonTier.POOR);
    }

    @Test
    void ryuHyunJin2020ShortSeasonIsGood() {
        // 2020년 60경기 시즌: 선발 12경기 67이닝, ERA- 70. 다 뛴 기준이 100 × 60/162 = 37이닝
        assertThat(SeasonVerdict.judge(Role.PITCHER, new Line(2020, 67, 12, 70.0, 1.8), 0, List.of()))
                .isEqualTo(SeasonTier.GOOD);
    }

    @Test
    void ryuHyunJin2023ComebackIsShortButGood() {
        // 2023년: 선발 11경기 52이닝(다 뛴 기준 100이닝 미만), ERA- 85
        assertThat(SeasonVerdict.judge(Role.PITCHER, new Line(2023, 52, 11, 85.0, 0.9), 0, List.of()))
                .isEqualTo(SeasonTier.SHORT_GOOD);
    }

    @Test
    void the2020SeasonScalesTheVolumeBars() {
        // 120타석, wRC+ 115: 2020년이면 다 뛴 시즌(111타석 이상)이라 좋은 시즌, 다른 해면 어느 정도 뛴 기준(150)에도 못 미친다
        Line line2020 = new Line(2020, 120, 0, 115.0, 0.8);
        Line line2021 = new Line(2021, 120, 0, 115.0, 0.8);
        assertThat(hitter(line2020, 0)).isEqualTo(SeasonTier.GOOD);
        assertThat(hitter(line2021, 0)).isEqualTo(SeasonTier.SHORT);
        assertThat(SeasonVerdict.scale(2020)).isEqualTo(60.0 / 162);
    }

    @Test
    void warAloneMakesAFullSeasonGood() {
        assertThat(hitter(new Line(2026, 600, 0, 105.0, 2.6), 0)).isEqualTo(SeasonTier.GOOD);
    }

    @Test
    void missingMetricsJudgeByVolumeOnly() {
        assertThat(hitter(new Line(2026, 579, 0, null, null), 0)).isEqualTo(SeasonTier.SOLID);
        assertThat(hitter(new Line(2026, 154, 0, null, null), 0)).isEqualTo(SeasonTier.SHORT);
        assertThat(hitter(new Line(2026, 4, 0, null, null), 374)).isEqualTo(SeasonTier.MINORS);
    }

    @Test
    void relieverFullSeasonIsFortyInnings() {
        // 구원 투수(선발 0): 다 뛴 기준 40이닝, ERA- 120 이상은 아쉬운 시즌
        assertThat(SeasonVerdict.judge(Role.PITCHER, new Line(2026, 62, 0, 88.0, 0.7), 0, List.of()))
                .isEqualTo(SeasonTier.GOOD);
        assertThat(SeasonVerdict.judge(Role.PITCHER, new Line(2026, 45, 0, 125.0, -0.1), 0, List.of()))
                .isEqualTo(SeasonTier.POOR);
        assertThat(SeasonVerdict.judge(Role.PITCHER, new Line(2026, 45, 0, 100.0, 0.2), 0, List.of()))
                .isEqualTo(SeasonTier.SOLID);
    }

    @Test
    void regularBeforeLooksOnlyAtTheLastThreeSeasonsWithEnoughVolume() {
        // 4년 전 주전 시즌은 보지 않는다
        assertThat(SeasonVerdict.regularBefore(false, 2026, List.of(new Line(2022, 600, 0, 120.0, 5.0)))).isFalse();
        // 200타석 미만 시즌은 빼고 평균을 낸다
        assertThat(SeasonVerdict.regularBefore(false, 2026,
                List.of(new Line(2025, 100, 0, 60.0, -1.0), new Line(2024, 600, 0, 115.0, 3.0)))).isTrue();
        assertThat(SeasonVerdict.regularBefore(true, 2026, List.of(new Line(2025, 180, 30, 80.0, 3.0)))).isTrue();
    }

    @ParameterizedTest(name = "{0}타석 wRC+ {1} WAR {2} → {3}")
    @CsvSource({
            // 좋은 타격은 wRC+ 110부터, 다 뛴 시즌은 300타석부터
            "300, 110, 0, GOOD", "300, 109.9, 0, SOLID", "299, 110, 0, SHORT_GOOD",
            // 어느 정도 뛴 시즌은 150타석부터
            "150, 110, 0, SHORT_GOOD", "149, 110, 0, SHORT",
            // 타격이 평균이어도 WAR 2.5부터는 좋은 시즌
            "300, 100, 2.5, GOOD", "300, 100, 2.49, SOLID",
            // 아쉬운 시즌은 wRC+ 80 미만이거나 WAR -0.3 미만(150타석부터)
            "300, 80, 0, SOLID", "300, 79.9, 0, POOR", "150, 79.9, 0, POOR", "149, 79.9, 0, SHORT",
            "300, 100, -0.3, SOLID", "300, 100, -0.31, POOR"})
    void hitterTierChangesExactlyAtTheBars(double plateAppearances, double wrcPlus, double war, SeasonTier expected) {
        assertThat(hitter(new Line(2026, plateAppearances, 0, wrcPlus, war), 0)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}이닝 선발 {1} ERA- {2} → {3}")
    @CsvSource({
            // 선발 10경기부터는 100이닝, 그 아래는 40이닝이 다 뛴 시즌. 좋은 투구는 ERA- 90 이하
            "100, 10, 90, GOOD", "100, 10, 90.1, SOLID", "99.9, 10, 90, SHORT_GOOD",
            "40, 9, 90, GOOD", "39.9, 9, 90, SHORT_GOOD",
            // 어느 정도 뛴 시즌은 30이닝부터
            "30, 0, 90, SHORT_GOOD", "29.9, 0, 90, SHORT",
            // 아쉬운 시즌은 ERA- 120부터(30이닝부터)
            "100, 10, 119.9, SOLID", "100, 10, 120, POOR", "30, 0, 120, POOR", "29.9, 0, 120, SHORT"})
    void pitcherTierChangesExactlyAtTheBars(double innings, int starts, double eraMinus, SeasonTier expected) {
        assertThat(SeasonVerdict.judge(Role.PITCHER, new Line(2026, innings, starts, eraMinus, 0.0), 0, List.of()))
                .isEqualTo(expected);
    }

    @Test
    void fiveStartsMakeAStarterOnlyInTheShort2020Season() {
        // 2020년 선발 5경기: 다 뛴 기준이 100 × 60/162 = 37이닝이라 20이닝은 짧은 시즌
        assertThat(pitcher(2020, 20, 5)).isEqualTo(SeasonTier.SHORT_GOOD);
        // 선발 4경기면 구원 기준 40 × 60/162 = 15이닝이라 다 뛴 시즌
        assertThat(pitcher(2020, 20, 4)).isEqualTo(SeasonTier.GOOD);
        // 다른 해에는 선발 5경기도 구원 기준 40이닝
        assertThat(pitcher(2021, 40, 5)).isEqualTo(SeasonTier.GOOD);
    }

    @Test
    void minorsNeedsMoreMinorThanMajorPlayingTime() {
        assertThat(hitter(new Line(2026, 100, 0, 100.0, 0.0), 100)).isEqualTo(SeasonTier.SHORT);
        assertThat(hitter(new Line(2026, 100, 0, 100.0, 0.0), 101)).isEqualTo(SeasonTier.MINORS);
    }

    @Test
    void regularBeforeChangesExactlyAtTheBars() {
        // 600타석당 WAR 2.5부터
        assertThat(SeasonVerdict.regularBefore(false, 2026, List.of(new Line(2025, 600, 0, 100.0, 2.5)))).isTrue();
        assertThat(SeasonVerdict.regularBefore(false, 2026, List.of(new Line(2025, 600, 0, 100.0, 2.49)))).isFalse();
        // 200타석·40이닝 이상인 시즌만
        assertThat(SeasonVerdict.regularBefore(false, 2026, List.of(new Line(2025, 200, 0, 100.0, 1.0)))).isTrue();
        assertThat(SeasonVerdict.regularBefore(false, 2026, List.of(new Line(2025, 199, 0, 100.0, 1.0)))).isFalse();
        assertThat(SeasonVerdict.regularBefore(true, 2026, List.of(new Line(2025, 40, 0, 100.0, 1.0)))).isTrue();
        assertThat(SeasonVerdict.regularBefore(true, 2026, List.of(new Line(2025, 39.9, 0, 100.0, 1.0)))).isFalse();
        // 3년 전까지
        assertThat(SeasonVerdict.regularBefore(false, 2026, List.of(new Line(2023, 600, 0, 100.0, 5.0)))).isTrue();
    }

    private static SeasonTier pitcher(int season, double innings, int starts) {
        return SeasonVerdict.judge(Role.PITCHER, new Line(season, innings, starts, 85.0, 0.0), 0, List.of());
    }

    private static SeasonTier hitter(Line current, double tripleAPlateAppearances, Line... previous) {
        return SeasonVerdict.judge(Role.HITTER, current, tripleAPlateAppearances, List.of(previous));
    }
}
