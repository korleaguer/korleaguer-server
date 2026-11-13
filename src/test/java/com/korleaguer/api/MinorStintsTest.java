package com.korleaguer.api;

import com.korleaguer.domain.Level;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerMove;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 마이너로 내려가 있던 기간(이 기간의 메이저 팀 경기는 결장 줄을 만들지 않는다, 이슈 #134) */
class MinorStintsTest {

    private static final Player KIM = new Player("김혜성");
    private static long nextId = 1;

    @Test
    void optionToRecallIsAMinorStint() {
        // 운영 김혜성(2026): 3/22 옵션 → 4/5 콜업 → 5/29 옵션(마이너 팀 활성화가 같은 날 붙음) → 9/28 콜업
        List<MinorStints.Range> ranges = MinorStints.from(List.of(
                move("2026-03-22", "OPT", Level.MLB, Level.MINORS, "Los Angeles Dodgers optioned 2B Hyeseong Kim"),
                move("2026-04-05", "CU", Level.MINORS, Level.MLB, "Los Angeles Dodgers recalled 2B Hyeseong Kim"),
                move("2026-04-15", "NUM", null, Level.MLB, "2B Hyeseong Kim changed number to 42."),
                move("2026-05-29", "OPT", Level.MLB, Level.MINORS, "Los Angeles Dodgers optioned SS Hyeseong Kim"),
                move("2026-05-29", "SC", null, Level.MINORS, "Oklahoma City Comets activated SS Hyeseong Kim."),
                move("2026-09-28", "CU", Level.MINORS, Level.MLB, "Los Angeles Dodgers recalled SS Hyeseong Kim")));

        assertThat(ranges).containsExactly(
                new MinorStints.Range(LocalDate.parse("2026-03-22"), LocalDate.parse("2026-04-05")),
                new MinorStints.Range(LocalDate.parse("2026-05-29"), LocalDate.parse("2026-09-28")));
        assertThat(MinorStints.covers(ranges, LocalDate.parse("2026-05-28"))).isFalse();
        assertThat(MinorStints.covers(ranges, LocalDate.parse("2026-05-29"))).isTrue(); // 옵션 당일부터 뺀다
        assertThat(MinorStints.covers(ranges, LocalDate.parse("2026-09-27"))).isTrue();
        assertThat(MinorStints.covers(ranges, LocalDate.parse("2026-09-28"))).isFalse(); // 콜업 당일은 명단에 있다
    }

    @Test
    void outrightWithoutRecallStaysOpen() {
        // 운영 배지환: 계약 선택 → 지명 할당 → 아웃라이트(그 뒤 콜업 없음)
        List<MinorStints.Range> ranges = MinorStints.from(List.of(
                move("2026-08-09", "SE", Level.MINORS, Level.MLB, "Milwaukee Brewers selected the contract"),
                move("2026-08-15", "DES", null, Level.MLB, "Milwaukee Brewers designated CF Jihwan Bae"),
                move("2026-08-19", "OUT", Level.MLB, Level.MINORS, "Milwaukee Brewers sent CF Jihwan Bae outright")));

        assertThat(ranges).containsExactly(new MinorStints.Range(LocalDate.parse("2026-08-19"), null));
        assertThat(MinorStints.covers(ranges, LocalDate.parse("2026-09-30"))).isTrue();
        assertThat(MinorStints.covers(ranges, LocalDate.parse("2026-08-18"))).isFalse(); // 지명 할당 중은 결장 그대로
    }

    @Test
    void rehabAssignmentIsNotAMinorStint() {
        // 운영 김하성: 부상자 명단 중 재활 경기 배정은 메이저 명단에 있다(결장 그대로)
        List<MinorStints.Range> ranges = MinorStints.from(List.of(
                move("2026-07-04", "SC", null, Level.MLB, "Atlanta Braves placed SS Ha-Seong Kim on the 10-day injured list"),
                move("2026-07-13", "ASG", Level.MLB, Level.MINORS, "Atlanta Braves sent SS Ha-Seong Kim on a rehab assignment"),
                move("2026-08-03", "SC", null, Level.MLB, "Atlanta Braves activated SS Ha-Seong Kim")));

        assertThat(ranges).isEmpty();
    }

    @Test
    void moveBetweenAffiliatesKeepsTheStintAndARecallWithoutDemotionIsIgnored() {
        // 운영 고우석: 마이너 배정 → 다른 마이너 팀 배정 → 트레이드 → 계약 선택 → 옵션
        List<MinorStints.Range> ranges = MinorStints.from(List.of(
                move("2026-04-08", "ASG", Level.MINORS, Level.MINORS, "assigned to Erie SeaWolves from Toledo Mud Hens."),
                move("2026-05-08", "ASG", Level.MINORS, Level.MINORS, "assigned to Toledo Mud Hens from Erie SeaWolves."),
                move("2026-07-05", "TR", Level.MLB, Level.MLB, "Detroit Tigers traded RHP Woo-Suk Go to Minnesota Twins"),
                move("2026-07-07", "SE", Level.MINORS, Level.MLB, "Minnesota Twins selected the contract of RHP Woo-Suk Go"),
                move("2026-07-21", "OPT", Level.MLB, Level.MINORS, "Minnesota Twins optioned RHP Woo-Suk Go"),
                move("2026-08-30", "CU", Level.MINORS, Level.MLB, "Minnesota Twins recalled RHP Woo-Suk Go")));

        // 트레이드(팀이 바뀜)에서 첫 기간이 닫힌다. 새 팀 첫 출전 전이라 결장 줄과 상관없다
        assertThat(ranges).containsExactly(
                new MinorStints.Range(LocalDate.parse("2026-04-08"), LocalDate.parse("2026-07-05")),
                new MinorStints.Range(LocalDate.parse("2026-07-21"), LocalDate.parse("2026-08-30")));
        assertThat(MinorStints.from(List.of(
                move("2026-04-05", "CU", Level.MINORS, Level.MLB, "recalled")))).isEmpty(); // 강등 없이 콜업만
    }

    @Test
    void claimedOffTheMinorsClosesTheStint() {
        // 옵션 중 지명 할당 → 다른 팀이 클레임해 바로 메이저 명단에(콜업 기록 없음). 새 팀의 진짜 결장은 남아야 한다
        List<MinorStints.Range> ranges = MinorStints.from(List.of(
                move("2026-06-01", "OPT", Level.MLB, Level.MINORS, "optioned to Triple-A"),
                move("2026-06-10", "DES", null, Level.MLB, "designated for assignment"),
                move("2026-06-14", "CLW", Level.MLB, Level.MLB, "claimed off waivers")));

        assertThat(ranges).containsExactly(
                new MinorStints.Range(LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-14")));
        assertThat(MinorStints.covers(ranges, LocalDate.parse("2026-06-20"))).isFalse();
    }

    private static PlayerMove move(String date, String code, Level from, Level to, String description) {
        PlayerMove move = new PlayerMove(nextId++, KIM);
        move.update(KIM, LocalDate.parse(date), code, code, description, null, null, from, null, null, to);
        return move;
    }
}
