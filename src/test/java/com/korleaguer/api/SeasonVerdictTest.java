package com.korleaguer.api;

import com.korleaguer.api.SeasonVerdict.Line;
import com.korleaguer.domain.Role;
import org.junit.jupiter.api.Test;

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

    private static SeasonTier hitter(Line current, double tripleAPlateAppearances, Line... previous) {
        return SeasonVerdict.judge(Role.HITTER, current, tripleAPlateAppearances, List.of(previous));
    }
}
