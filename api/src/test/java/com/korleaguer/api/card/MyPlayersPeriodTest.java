package com.korleaguer.api.card;

import com.korleaguer.api.card.MyPlayersService.Period;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 내 선수 카드 한 장의 기간(MyPlayersService.periodOf) */
class MyPlayersPeriodTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final Period RECENT = new Period(LocalDate.of(2026, 11, 4), TODAY, false);

    @Test
    @DisplayName("구단이 어제 경기했으면 본인이 2주째 못 뛰어도 최근 기간을 그대로 쓴다")
    void inSeasonBenchedPlayerKeepsRecentWindow() {
        // when // then
        // 구단은 어제 경기했는데 본인은 2주째 못 뛰었다(부상·벤치) → 결장을 보여야 하므로 그대로
        assertThat(MyPlayersService.periodOf(RECENT, 7, TODAY.minusDays(1), TODAY.minusDays(14))).isEqualTo(RECENT);
    }

    @Test
    @DisplayName("시즌이 끝난 선수는 본인이 마지막으로 뛴 날까지의 7일을 보인다")
    void seasonOverUsesOwnLastGame() {
        // when
        // 구단 마지막 경기 10월 16일, 본인 마지막 출전 10월 8일 → 10월 2~8일
        Period period = MyPlayersService.periodOf(RECENT, 7, LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 8));

        // then
        assertThat(period).isEqualTo(new Period(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 8), true));
    }

    @Test
    @DisplayName("마이너 선수는 구단 경기가 없어도 본인 마지막 경기까지의 7일을 보인다")
    void minorsPlayerUsesOwnLastGame() {
        // when
        // 마이너에 있으면 구단 경기가 없다(teamLast=null). 본인이 마지막으로 뛴 8월 15일까지
        Period period = MyPlayersService.periodOf(RECENT, 7, null, LocalDate.of(2026, 8, 15));

        // then
        assertThat(period).isEqualTo(new Period(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 15), true));
    }

    @Test
    @DisplayName("한 번도 안 뛰었으면 구단 마지막 경기까지, 그것도 없으면 최근 기간을 쓴다")
    void neverPlayedUsesTeamLastGameOrRecent() {
        // when
        Period period = MyPlayersService.periodOf(RECENT, 7, LocalDate.of(2026, 9, 28), null);

        // then
        assertThat(period).isEqualTo(new Period(LocalDate.of(2026, 9, 22), LocalDate.of(2026, 9, 28), true));

        // when // then
        assertThat(MyPlayersService.periodOf(RECENT, 7, null, null)).isEqualTo(RECENT);
    }
}
