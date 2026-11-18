package com.korleaguer.api;

import com.korleaguer.domain.Level;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerMove;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 선수 화면 이동 기록(moves)의 팀 이름(이슈 #183). MLB 구단은 한글 이름, 트리플A·더블A 팀(roster/minor-teams.yml)은
 * 마이너 경기 줄과 같은 한국어 지명에 단계를 붙인다. 표에 없는 팀은 MLB가 준 영어 이름 그대로다.
 * 이동 기록은 실제 기록의 모양이다(김하성 2026 재활 경기 배정: 운영 /players/3, 고우석 2025: transactions-808970.json).
 */
class MoveTeamNamesTest {

    private static final MinorTeamNames NAMES = new MinorTeamNames(MinorGameLinesTest.readTeams());
    /** 팀 이름만 보므로 선수는 누구든 같다 */
    private static final Player PLAYER = new Player("선수");
    private static final long ATLANTA = 144L;
    private static final long GWINNETT = 431L;
    private static final long COLUMBUS_DOUBLE_A = 6325L;
    private static final long COLUMBUS_TRIPLE_A = 445L;
    private long nextId = 1;

    @Test
    void tripleAAndDoubleATeamsGetTheKoreanPlaceAndTheLevel() {
        List<PlayerPageResponse.Move> moves = BaseballQueryService.fanMoves(List.of(
                rehab("2026-07-17", GWINNETT, "Gwinnett Stripers"),
                rehab("2026-04-28", COLUMBUS_DOUBLE_A, "Columbus Clingstones")), NAMES);

        assertThat(moves).extracting(PlayerPageResponse.Move::toTeam).containsExactly("귀넷(트리플A)", "콜럼버스(더블A)");
        // MLB 구단은 지금처럼 한국어 구단 이름
        assertThat(moves).extracting(PlayerPageResponse.Move::fromTeam).containsOnly("애틀랜타");
    }

    @Test
    void theLevelTellsTheTwoColumbusTeamsApart() {
        assertThat(NAMES.moveName(COLUMBUS_TRIPLE_A, "Columbus Clippers")).isEqualTo("콜럼버스(트리플A)");
        assertThat(NAMES.moveName(COLUMBUS_DOUBLE_A, "Columbus Clingstones")).isEqualTo("콜럼버스(더블A)");
    }

    @Test
    void teamsNotInTheListKeepTheEnglishName() {
        // 루키 팀(FCL Marlins)·국가대표(Korea)는 표에 없다. 이름을 지어내지 않고 MLB가 준 이름 그대로
        List<PlayerPageResponse.Move> moves = BaseballQueryService.fanMoves(List.of(
                move("2025-05-08", "ASG",
                        "Pensacola Blue Wahoos sent RHP Woo-Suk Go on a rehab assignment to FCL Marlins.",
                        4124L, "Pensacola Blue Wahoos", 467L, "FCL Marlins", Level.MINORS),
                move("2025-02-05", "ASG", "RHP Woo-Suk Go assigned to Korea.", null, null, 1171L, "Korea", null)),
                NAMES);

        assertThat(moves).extracting(PlayerPageResponse.Move::toTeam).containsExactly("FCL Marlins", "Korea");
        // 보낸 쪽이 표에 있는 더블A 팀이면 그쪽은 한국어 지명과 단계, 팀이 없는 줄은 null 그대로
        assertThat(moves).extracting(PlayerPageResponse.Move::fromTeam).containsExactly("펜서콜라(더블A)", null);
    }

    @Test
    void majorLeagueClubsKeepTheirKoreanClubName() {
        List<PlayerPageResponse.Move> moves = BaseballQueryService.fanMoves(List.of(
                move("2025-09-01", "CLW", "Atlanta Braves claimed SS Ha-Seong Kim off waivers from Tampa Bay Rays.",
                        139L, "Tampa Bay Rays", ATLANTA, "Atlanta Braves", Level.MLB)), NAMES);

        assertThat(moves.getFirst().fromTeam()).isEqualTo("탬파베이");
        assertThat(moves.getFirst().toTeam()).isEqualTo("애틀랜타");
    }

    @Test
    void gameLinesKeepThePlaceWithoutTheLevel() {
        // 경기 줄은 단계 칸(levelKo)이 따로 있어 이름에는 붙이지 않는다
        assertThat(NAMES.name(GWINNETT, null, "Gwinnett Stripers")).isEqualTo("귀넷");
        assertThat(NAMES.name(COLUMBUS_DOUBLE_A, "Columbus", "Columbus Clingstones")).isEqualTo("콜럼버스");
    }

    @Test
    void unknownLevelFailsAtStartup() {
        MinorTeamProperties typo = new MinorTeamProperties(List.of(
                new MinorTeamProperties.Team(GWINNETT, "AAAA", "귀넷", "Gwinnett Stripers")));

        assertThatThrownBy(() -> new MinorTeamNames(typo)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void teamWithoutALevelHasNoSuffix() {
        MinorTeamNames names = new MinorTeamNames(new MinorTeamProperties(List.of(
                new MinorTeamProperties.Team(GWINNETT, null, "귀넷", "Gwinnett Stripers"))));

        assertThat(names.moveName(GWINNETT, "Gwinnett Stripers")).isEqualTo("귀넷");
    }

    private PlayerMove rehab(String date, long toTeamId, String toTeamName) {
        return move(date, "ASG", "Atlanta Braves sent SS Ha-Seong Kim on a rehab assignment to " + toTeamName + ".",
                ATLANTA, "Atlanta Braves", toTeamId, toTeamName, Level.MINORS);
    }

    private PlayerMove move(String date, String typeCode, String description, Long fromId, String fromName, Long toId,
                            String toName, Level toLevel) {
        PlayerMove move = new PlayerMove(nextId++, PLAYER);
        move.update(PLAYER, LocalDate.parse(date), typeCode, null, description, fromId, fromName, null, toId, toName,
                toLevel);
        return move;
    }
}
