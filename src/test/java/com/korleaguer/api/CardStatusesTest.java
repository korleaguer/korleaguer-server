package com.korleaguer.api;

import com.korleaguer.domain.Level;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerMove;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 카드 상태 배지: 실제 2026 이동 기록 문구로 상태·시작일·며칠째를 확인한다(이슈 #33). */
class CardStatusesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 25);
    private long nextId = 1;

    @Test
    void injuredListUsesRetroactiveDateAndCountsDays() {
        Player lee = player(Level.MLB, null, false);
        List<PlayerMove> moves = newestFirst(
                move(lee, "2026-09-21", "SC", "San Francisco Giants placed RF Jung Hoo Lee on the 10-day injured list "
                        + "retroactive to September 20, 2026. Groin contusion.", Level.MLB));

        CardStatuses.Resolved resolved = CardStatuses.resolve(lee, moves, null, null, TODAY, false);

        assertThat(resolved.status().kind()).isEqualTo("IL");
        assertThat(resolved.status().since()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(resolved.status().days()).isEqualTo(6);
    }

    @Test
    void transferKeepsTheFirstPlacementDate() {
        // 10일 → 60일 이동은 처음 오른 날부터 센다(배지환, 마이너 7일 명단)
        Player bae = player(Level.MINORS, "Nashville Sounds", false);
        List<PlayerMove> moves = newestFirst(
                move(bae, "2026-08-25", "SC", "Nashville Sounds placed CF Jihwan Bae on the 7-day injured list.", Level.MINORS),
                move(bae, "2026-09-01", "SC", "Nashville Sounds transferred CF Jihwan Bae from the 7-day injured list "
                        + "to the 60-day injured list.", Level.MINORS));

        CardStatuses.Resolved resolved = CardStatuses.resolve(bae, moves, null,
                new CardStatuses.MinorSpot("AAA", "Syracuse Mets"), TODAY, false);

        assertThat(resolved.status().kind()).isEqualTo("MINORS_IL");
        assertThat(resolved.status().since()).isEqualTo(LocalDate.of(2026, 8, 25));
        // 팀 이름·단계는 넘겨받은 지금 마이너 팀(MinorSpot)을 쓴다
        assertThat(resolved.status().teamName()).isEqualTo("Syracuse Mets");
        assertThat(resolved.status().level()).isEqualTo("AAA");
    }

    @Test
    void activationShowsReturnedForSevenDaysThenOnlyKeepsTheStintStart() {
        Player kim = player(Level.MLB, null, false);
        List<PlayerMove> moves = newestFirst(
                move(kim, "2026-07-04", "SC", "Atlanta Braves placed SS Ha-Seong Kim on the 10-day injured list.", Level.MLB),
                move(kim, "2026-07-13", "ASG", "Atlanta Braves sent SS Ha-Seong Kim on a rehab assignment to FCL Braves.",
                        Level.MINORS),
                move(kim, "2026-08-03", "SC", "Atlanta Braves activated SS Ha-Seong Kim from the 10-day injured list.",
                        Level.MLB));

        CardStatuses.Resolved day3 = CardStatuses.resolve(kim, moves, null, null, LocalDate.of(2026, 8, 5), false);
        assertThat(day3.status().kind()).isEqualTo("RETURNED");
        assertThat(day3.status().days()).isEqualTo(3);
        assertThat(day3.start()).isEqualTo(CardStatuses.Start.RETURNED);

        CardStatuses.Resolved day18 = CardStatuses.resolve(kim, moves, null, null, LocalDate.of(2026, 8, 20), false);
        assertThat(day18.status()).isNull();
        assertThat(day18.stintStart()).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(day18.start()).isEqualTo(CardStatuses.Start.RETURNED);

        // 재활 경기 배정 중에는 아직 부상자 명단이다
        CardStatuses.Resolved rehab = CardStatuses.resolve(kim, moves, null, null, LocalDate.of(2026, 7, 20), false);
        assertThat(rehab.status().kind()).isEqualTo("IL");
        assertThat(rehab.status().since()).isEqualTo(LocalDate.of(2026, 7, 4));
    }

    @Test
    void sentDownCountsFromTheOptionEvenWhenTheMinorTeamActivatesLater() {
        // 김혜성: 같은 날 옵션 뒤 마이너 팀이 '활성화'를 남긴다
        Player kim = player(Level.MINORS, "Oklahoma City Comets", false);
        List<PlayerMove> moves = newestFirst(
                move(kim, "2026-04-05", "CU", "Los Angeles Dodgers recalled 2B Hyeseong Kim from Oklahoma City Comets.",
                        Level.MLB),
                move(kim, "2026-05-29", "OPT", "Los Angeles Dodgers optioned SS Hyeseong Kim to Oklahoma City Comets.",
                        Level.MINORS),
                move(kim, "2026-05-29", "SC", "Oklahoma City Comets activated SS Hyeseong Kim.", Level.MINORS));

        CardStatuses.Resolved resolved = CardStatuses.resolve(kim, moves, null,
                new CardStatuses.MinorSpot("AAA", "Oklahoma City Comets"), LocalDate.of(2026, 6, 10), false);

        assertThat(resolved.status().kind()).isEqualTo("MINORS");
        assertThat(resolved.status().since()).isEqualTo(LocalDate.of(2026, 5, 29));
        assertThat(resolved.stintStart()).isEqualTo(LocalDate.of(2026, 5, 29));
        assertThat(resolved.start()).isEqualTo(CardStatuses.Start.SENT_DOWN);
    }

    @Test
    void calledUpWithinSevenDays() {
        Player go = player(Level.MLB, null, false);
        List<PlayerMove> moves = newestFirst(
                move(go, "2026-07-07", "SE", "Minnesota Twins selected the contract of RHP Woo-Suk Go from St. Paul Saints.",
                        Level.MLB));

        CardStatuses.Resolved resolved = CardStatuses.resolve(go, moves, null, null, LocalDate.of(2026, 7, 10), false);

        assertThat(resolved.status().kind()).isEqualTo("CALLED_UP");
        assertThat(resolved.status().days()).isEqualTo(4);
        assertThat(resolved.start()).isEqualTo(CardStatuses.Start.CALLED_UP);
    }

    @Test
    void suspensionEndsWithTheNextActivation() {
        Player go = player(Level.MINORS, "St. Paul Saints", false);
        List<PlayerMove> moves = newestFirst(
                move(go, "2026-07-21", "OPT", "Minnesota Twins optioned RHP Woo-Suk Go to St. Paul Saints.", Level.MINORS),
                move(go, "2026-07-25", "SU", "RHP Woo-Suk Go suspended.", Level.MINORS),
                move(go, "2026-08-04", "SC", "St. Paul Saints activated RHP Woo-Suk Go.", Level.MINORS));

        assertThat(CardStatuses.resolve(go, moves, null, null, LocalDate.of(2026, 7, 30), false).status().kind())
                .isEqualTo("SUSPENDED");
        assertThat(CardStatuses.resolve(go, moves, null, null, LocalDate.of(2026, 8, 10), false).status().kind())
                .isEqualTo("MINORS");
    }

    @Test
    void designatedForAssignment() {
        Player bae = player(Level.MLB, null, false);
        List<PlayerMove> moves = newestFirst(
                move(bae, "2026-08-15", "DES", "Milwaukee Brewers designated CF Jihwan Bae for assignment.", Level.MLB));

        assertThat(CardStatuses.resolve(bae, moves, null, null, LocalDate.of(2026, 8, 16), false).status().kind())
                .isEqualTo("DFA");
    }

    @Test
    void freeAgentCountsFromTheDeclarationAndKeepsTheLastGame() {
        Player go = player(Level.MINORS, "St. Paul Saints", true);
        List<PlayerMove> moves = newestFirst(
                move(go, "2026-08-30", "OUT", "Minnesota Twins sent RHP Woo-Suk Go outright to St. Paul Saints.", Level.MINORS),
                move(go, "2026-08-30", "DFA", "RHP Woo-Suk Go elected free agency.", Level.MINORS));
        CardStatuses.LastGame last = new CardStatuses.LastGame(LocalDate.of(2026, 8, 27), "AAA");

        CardStatuses.Resolved resolved = CardStatuses.resolve(go, moves, last, null, TODAY, false);

        assertThat(resolved.status().kind()).isEqualTo("FA");
        assertThat(resolved.status().since()).isEqualTo(LocalDate.of(2026, 8, 30));
        assertThat(resolved.status().days()).isEqualTo(27);
        assertThat(resolved.status().lastGameDate()).isEqualTo(LocalDate.of(2026, 8, 27));
        assertThat(resolved.status().lastGameLevel()).isEqualTo("AAA");
    }

    @Test
    void seasonOverOnlyAfterThreeDaysWithoutGames() {
        Player lee = player(Level.MLB, null, false);
        LocalDate today = LocalDate.of(2026, 10, 5);

        CardStatuses.Resolved over = CardStatuses.resolve(lee, List.of(),
                new CardStatuses.LastGame(LocalDate.of(2026, 9, 27), "MLB"), null, today, true);
        assertThat(over.status().kind()).isEqualTo("SEASON_OVER");
        assertThat(over.status().days()).isNull();

        // 포스트시즌을 뛰는 선수(이틀 전 경기)는 평소대로 둔다
        CardStatuses.Resolved playoffs = CardStatuses.resolve(lee, List.of(),
                new CardStatuses.LastGame(LocalDate.of(2026, 10, 3), "MLB"), null, today, true);
        assertThat(playoffs.status()).isNull();
    }

    @Test
    void movesAfterTodayAreIgnored() {
        Player go = player(Level.MLB, null, false);
        List<PlayerMove> moves = newestFirst(
                move(go, "2026-07-07", "SE", "Minnesota Twins selected the contract of RHP Woo-Suk Go.", Level.MLB),
                move(go, "2026-07-21", "OPT", "Minnesota Twins optioned RHP Woo-Suk Go to St. Paul Saints.", Level.MINORS));

        assertThat(CardStatuses.resolve(go, moves, null, null, LocalDate.of(2026, 7, 9), false).status().kind())
                .isEqualTo("CALLED_UP");
    }

    private static Player player(Level level, String levelTeam, boolean freeAgent) {
        Player player = new Player("선수");
        player.updateLevel(level, levelTeam, Instant.EPOCH);
        player.updateFreeAgent(freeAgent);
        return player;
    }

    private PlayerMove move(Player player, String date, String code, String description, Level toLevel) {
        PlayerMove move = new PlayerMove(nextId++, player);
        move.update(player, LocalDate.parse(date), code, null, description, null, null, null, null, null, toLevel);
        return move;
    }

    /** 저장소처럼 최신부터(같은 날이면 번호가 큰 것부터) */
    private static List<PlayerMove> newestFirst(PlayerMove... moves) {
        List<PlayerMove> list = new ArrayList<>(List.of(moves));
        list.sort(Comparator.comparing(PlayerMove::getMoveDate).thenComparing(PlayerMove::getMlbTransactionId).reversed());
        return list;
    }
}
