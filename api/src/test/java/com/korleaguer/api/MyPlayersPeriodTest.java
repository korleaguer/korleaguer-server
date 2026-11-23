package com.korleaguer.api;

import com.korleaguer.api.MyPlayersService.Period;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 내 선수 카드 한 장의 기간(MyPlayersService.periodOf) */
class MyPlayersPeriodTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final Period RECENT = new Period(LocalDate.of(2026, 11, 4), TODAY, false);

    @Test
    void inSeasonBenchedPlayerKeepsRecentWindow() {
        // 구단은 어제 경기했는데 본인은 2주째 못 뛰었다(부상·벤치) → 결장을 보여야 하므로 그대로
        assertThat(MyPlayersService.periodOf(RECENT, 7, TODAY.minusDays(1), TODAY.minusDays(14))).isEqualTo(RECENT);
    }

    @Test
    void seasonOverUsesOwnLastGame() {
        // 구단 마지막 경기 10월 16일, 본인 마지막 출전 10월 8일 → 10월 2~8일
        Period period = MyPlayersService.periodOf(RECENT, 7, LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 8));
        assertThat(period).isEqualTo(new Period(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 8), true));
    }

    @Test
    void minorsPlayerUsesOwnLastGame() {
        // 마이너에 있으면 구단 경기가 없다(teamLast=null). 본인이 마지막으로 뛴 8월 15일까지
        Period period = MyPlayersService.periodOf(RECENT, 7, null, LocalDate.of(2026, 8, 15));
        assertThat(period).isEqualTo(new Period(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 15), true));
    }

    @Test
    void neverPlayedUsesTeamLastGameOrRecent() {
        Period period = MyPlayersService.periodOf(RECENT, 7, LocalDate.of(2026, 9, 28), null);
        assertThat(period).isEqualTo(new Period(LocalDate.of(2026, 9, 22), LocalDate.of(2026, 9, 28), true));
        assertThat(MyPlayersService.periodOf(RECENT, 7, null, null)).isEqualTo(RECENT);
    }
}
