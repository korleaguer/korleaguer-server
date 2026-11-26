package com.korleaguer.api.game;

import com.korleaguer.api.player.RosterMember;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.Game;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.SeasonType;
import com.korleaguer.domain.Team;
import com.korleaguer.domain.TeamRepository;
import com.korleaguer.support.GameStatuses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 경기 종류를 모르는 경기는 경기 줄을 만들지 않는다(이슈 #190 리뷰). 수집은 정규시즌·포스트시즌만 저장하지만 game.season_type
 * 칸은 null을 허용한다. 그런 경기는 뛴 기록이 있어도(뛴 경기 줄), 팀 경기여도(결장 줄) 줄이 없어 응답 seasonType이 비지 않고,
 * 줄을 고르다 멈추지도 않는다(Set.of는 null을 물으면 예외였다). 읽기만 하는 테스트라 되돌린다.
 * 샘플 데이터와 겹치지 않게 팀·경기 번호는 쓰지 않는 값으로 둔다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UnknownSeasonTypeGameTest {

    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-31T00:00:00Z");

    @Autowired
    private GameLineAssembler assembler;

    @Autowired
    private TeamRepository teams;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private GameRepository games;

    @Autowired
    private AppearanceRepository appearances;

    @Test
    @DisplayName("경기 종류를 모르는 경기는 뛰었든 결장이든 경기 줄에서 빠진다")
    void gamesWithoutSeasonTypeHaveNoLine() {
        // given
        Team ours = team(9142L, "TMN");
        Team other = team(9116L, "TDT");
        Player player = players.save(new Player("시험투수"));
        played(player, ours, game(97001L, SeasonType.REGULAR.stored(), "2026-09-25T23:10:00Z", ours, other));
        played(player, ours, game(97999L, null, "2026-09-27T23:10:00Z", ours, other));
        played(player, ours, game(97201L, SeasonType.POSTSEASON.stored(), "2026-10-04T22:08:00Z", ours, other));
        game(97002L, SeasonType.REGULAR.stored(), "2026-09-26T23:10:00Z", ours, other);
        game(97998L, null, "2026-09-28T23:10:00Z", ours, other);

        // when
        List<GameLine> lines = assembler.lines(List.of(new RosterMember(player, ours, false)), FROM, TO);

        // then
        // 97201·97001은 뛴 경기, 97002는 결장. 종류가 빈 97999(뛴 경기)·97998(팀 경기)은 줄이 없다
        assertThat(lines).extracting(GameLine::gameId).containsExactly(97201L, 97002L, 97001L);
        assertThat(lines).extracting(GameLine::played).containsExactly(true, false, true);
        assertThat(lines).extracting(GameLine::seasonType)
                .containsExactly(SeasonType.POSTSEASON, SeasonType.REGULAR, SeasonType.REGULAR);
    }

    private Team team(long mlbTeamId, String abbreviation) {
        Team team = new Team(mlbTeamId);
        team.update(abbreviation, abbreviation);
        return teams.save(team);
    }

    private Game game(long gamePk, String seasonType, String start, Team home, Team away) {
        Game game = new Game(gamePk);
        game.update(2026, seasonType, Instant.parse(start), home, away, 3, 2, GameStatuses.FINAL, null);
        return games.save(game);
    }

    private void played(Player player, Team team, Game game) {
        Appearance appearance = new Appearance(player, game);
        appearance.update(team, null, null);
        appearances.save(appearance);
    }
}
