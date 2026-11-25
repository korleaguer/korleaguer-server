package com.korleaguer.api.season;

import com.korleaguer.api.season.SeasonVerdict.Line;
import com.korleaguer.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 시즌 성적표 판정: 2026 실제 기록으로 정한 결과와 지난 시즌 확인 사례가 그대로 나와야 한다. */
class SeasonVerdictTest {

    @Test
    @DisplayName("579타석에 wRC+가 101.8이면 제 몫 한 시즌이다")
    void leeJungHooPlayedTheFullSeasonAtLeagueAverage() {
        // when // then
        // 579타석, wRC+ 101.8, WAR 0.97
        assertThat(hitter(new Line(2026, 579, 0, 101.8, 0.97), 0,
                new Line(2024, 158, 0, 93.0, 0.3), new Line(2025, 617, 0, 107.0, 2.0)))
                .isEqualTo(SeasonTier.SOLID);
    }

    @Test
    @DisplayName("메이저 4타석에 트리플A 374타석이면 마이너에서 보낸 시즌이다")
    void baeJiHwanSpentTheSeasonInTripleA() {
        // when // then
        // 메이저 4타석, 트리플A 374타석. 2023년(371타석)은 주전급이 아니었다
        assertThat(hitter(new Line(2026, 4, 0, 249.4, 0.09), 374, new Line(2023, 371, 0, 77.0, 0.3)))
                .isEqualTo(SeasonTier.MINORS);
    }

    @Test
    @DisplayName("메이저 131타석보다 트리플A 370타석이 많으면 마이너에서 보낸 시즌이다")
    void kimHyeseongPlayedMoreInTripleA() {
        // when // then
        // 메이저 131타석, 트리플A 370타석
        assertThat(hitter(new Line(2026, 131, 0, 83.0, 0.35), 370, new Line(2025, 170, 0, 90.0, 0.4)))
                .isEqualTo(SeasonTier.MINORS);
    }

    @Test
    @DisplayName("메이저 4이닝에 마이너 50이닝이면 마이너에서 보낸 시즌이다")
    void goWooSukPitchedMostlyInTheMinors() {
        // when // then
        // 메이저 4이닝(ERA- 216은 어느 정도 뛴 기준 30이닝에 못 미쳐 보지 않는다), 마이너 50이닝
        assertThat(SeasonVerdict.judge(Role.PITCHER, new Line(2026, 4, 0, 216.1, -0.14), 50, List.of()))
                .isEqualTo(SeasonTier.MINORS);
    }

    @Test
    @DisplayName("154타석에 wRC+가 57.9면 아쉬운 시즌이다")
    void songSungMunHitWellBelowAverage() {
        // when // then
        // 154타석(어느 정도 뛴 기준 150 이상), wRC+ 57.9
        assertThat(hitter(new Line(2026, 154, 0, 57.9, 0.24), 120)).isEqualTo(SeasonTier.POOR);
    }

    @Test
    @DisplayName("앞 시즌에 주전이던 타자가 147타석에 wRC+ 7.3이면 아쉬운 시즌이다")
    void kimHaSeongWasARegularBeforeAndMissedMostOfTheSeason() {
        // when // then
        // 147타석은 150에 못 미치지만, 앞 시즌들(2023 626타석 WAR 4.4, 2024 470타석 WAR 2.6) 주전이었다
        assertThat(hitter(new Line(2026, 147, 0, 7.3, -1.17), 30,
                new Line(2023, 626, 0, 110.0, 4.4), new Line(2024, 470, 0, 101.0, 2.6),
                new Line(2025, 191, 0, 80.0, 0.1)))
                .isEqualTo(SeasonTier.POOR);
    }

    @Test
    @DisplayName("60경기뿐인 2020년에 선발로 67이닝을 잘 던지면 좋은 시즌이다")
    void ryuHyunJin2020ShortSeasonIsGood() {
        // when // then
        // 2020년 60경기 시즌: 선발 12경기 67이닝, ERA- 70. 다 뛴 기준이 100 × 60/162 = 37이닝
        assertThat(SeasonVerdict.judge(Role.PITCHER, new Line(2020, 67, 12, 70.0, 1.8), 0, List.of()))
                .isEqualTo(SeasonTier.GOOD);
    }

    @Test
    @DisplayName("선발 11경기 52이닝을 ERA- 85로 던지면 짧지만 좋았던 시즌이다")
    void ryuHyunJin2023ComebackIsShortButGood() {
        // when // then
        // 2023년: 선발 11경기 52이닝(다 뛴 기준 100이닝 미만), ERA- 85
        assertThat(SeasonVerdict.judge(Role.PITCHER, new Line(2023, 52, 11, 85.0, 0.9), 0, List.of()))
                .isEqualTo(SeasonTier.SHORT_GOOD);
    }

    @Test
    @DisplayName("같은 120타석도 2020년이면 좋은 시즌, 2021년이면 출전이 적은 시즌이다")
    void the2020SeasonScalesTheVolumeBars() {
        // given
        // 120타석, wRC+ 115: 2020년이면 다 뛴 시즌(111타석 이상)이라 좋은 시즌, 다른 해면 어느 정도 뛴 기준(150)에도 못 미친다
        Line line2020 = new Line(2020, 120, 0, 115.0, 0.8);
        Line line2021 = new Line(2021, 120, 0, 115.0, 0.8);

        // when // then
        assertThat(hitter(line2020, 0)).isEqualTo(SeasonTier.GOOD);
        assertThat(hitter(line2021, 0)).isEqualTo(SeasonTier.SHORT);
        assertThat(SeasonVerdict.scale(2020)).isEqualTo(60.0 / 162);
    }

    @Test
    @DisplayName("600타석에 wRC+가 105여도 WAR가 2.6이면 좋은 시즌이다")
    void warAloneMakesAFullSeasonGood() {
        // when // then
        assertThat(hitter(new Line(2026, 600, 0, 105.0, 2.6), 0)).isEqualTo(SeasonTier.GOOD);
    }

    @Test
    @DisplayName("지표가 없으면 뛴 양만으로 시즌을 판정한다")
    void missingMetricsJudgeByVolumeOnly() {
        // when // then
        assertThat(hitter(new Line(2026, 579, 0, null, null), 0)).isEqualTo(SeasonTier.SOLID);
        assertThat(hitter(new Line(2026, 154, 0, null, null), 0)).isEqualTo(SeasonTier.SHORT);
        assertThat(hitter(new Line(2026, 4, 0, null, null), 374)).isEqualTo(SeasonTier.MINORS);
    }

    @Test
    @DisplayName("구원 투수는 45이닝과 62이닝도 다 뛴 시즌으로 보고 ERA-로 판정한다")
    void relieverFullSeasonIsFortyInnings() {
        // when // then
        // 구원 투수(선발 0): 다 뛴 기준 40이닝, ERA- 120 이상은 아쉬운 시즌
        assertThat(SeasonVerdict.judge(Role.PITCHER, new Line(2026, 62, 0, 88.0, 0.7), 0, List.of()))
                .isEqualTo(SeasonTier.GOOD);
        assertThat(SeasonVerdict.judge(Role.PITCHER, new Line(2026, 45, 0, 125.0, -0.1), 0, List.of()))
                .isEqualTo(SeasonTier.POOR);
        assertThat(SeasonVerdict.judge(Role.PITCHER, new Line(2026, 45, 0, 100.0, 0.2), 0, List.of()))
                .isEqualTo(SeasonTier.SOLID);
    }

    @Test
    @DisplayName("앞 세 시즌 가운데 충분히 뛴 시즌만으로 주전이었는지 본다")
    void regularBeforeLooksOnlyAtTheLastThreeSeasonsWithEnoughVolume() {
        // when // then
        // 4년 전 주전 시즌은 보지 않는다
        assertThat(SeasonVerdict.regularBefore(false, 2026, List.of(new Line(2022, 600, 0, 120.0, 5.0)))).isFalse();
        // 200타석 미만 시즌은 빼고 평균을 낸다
        assertThat(SeasonVerdict.regularBefore(false, 2026,
                List.of(new Line(2025, 100, 0, 60.0, -1.0), new Line(2024, 600, 0, 115.0, 3.0)))).isTrue();
        assertThat(SeasonVerdict.regularBefore(true, 2026, List.of(new Line(2025, 180, 30, 80.0, 3.0)))).isTrue();
    }

    @ParameterizedTest(name = "{0}타석 wRC+ {1} WAR {2} → {3}")
    @DisplayName("타자 판정은 타석·wRC+·WAR 기준선에서 정확히 바뀐다")
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
        // when // then
        assertThat(hitter(new Line(2026, plateAppearances, 0, wrcPlus, war), 0)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}이닝 선발 {1} ERA- {2} → {3}")
    @DisplayName("투수 판정은 이닝·선발 경기 수·ERA- 기준선에서 정확히 바뀐다")
    @CsvSource({
            // 선발 10경기부터는 100이닝, 그 아래는 40이닝이 다 뛴 시즌. 좋은 투구는 ERA- 90 이하
            "100, 10, 90, GOOD", "100, 10, 90.1, SOLID", "99.9, 10, 90, SHORT_GOOD",
            "40, 9, 90, GOOD", "39.9, 9, 90, SHORT_GOOD",
            // 어느 정도 뛴 시즌은 30이닝부터
            "30, 0, 90, SHORT_GOOD", "29.9, 0, 90, SHORT",
            // 아쉬운 시즌은 ERA- 120부터(30이닝부터)
            "100, 10, 119.9, SOLID", "100, 10, 120, POOR", "30, 0, 120, POOR", "29.9, 0, 120, SHORT"})
    void pitcherTierChangesExactlyAtTheBars(double innings, int starts, double eraMinus, SeasonTier expected) {
        // when // then
        assertThat(SeasonVerdict.judge(Role.PITCHER, new Line(2026, innings, starts, eraMinus, 0.0), 0, List.of()))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("선발 5경기는 짧았던 2020년에만 선발 투수 이닝 기준으로 본다")
    void fiveStartsMakeAStarterOnlyInTheShort2020Season() {
        // when // then
        // 2020년 선발 5경기: 다 뛴 기준이 100 × 60/162 = 37이닝이라 20이닝은 짧은 시즌
        assertThat(pitcher(2020, 20, 5)).isEqualTo(SeasonTier.SHORT_GOOD);
        // 선발 4경기면 구원 기준 40 × 60/162 = 15이닝이라 다 뛴 시즌
        assertThat(pitcher(2020, 20, 4)).isEqualTo(SeasonTier.GOOD);
        // 다른 해에는 선발 5경기도 구원 기준 40이닝
        assertThat(pitcher(2021, 40, 5)).isEqualTo(SeasonTier.GOOD);
    }

    @Test
    @DisplayName("마이너 타석이 메이저보다 하나라도 많아야 마이너에서 보낸 시즌이다")
    void minorsNeedsMoreMinorThanMajorPlayingTime() {
        // when // then
        assertThat(hitter(new Line(2026, 100, 0, 100.0, 0.0), 100)).isEqualTo(SeasonTier.SHORT);
        assertThat(hitter(new Line(2026, 100, 0, 100.0, 0.0), 101)).isEqualTo(SeasonTier.MINORS);
    }

    @Test
    @DisplayName("3년 안에 200타석·40이닝부터, 600타석당 WAR 2.5부터 주전으로 본다")
    void regularBeforeChangesExactlyAtTheBars() {
        // when // then
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
