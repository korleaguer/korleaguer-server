package com.korleaguer.api.game;

import com.korleaguer.api.player.RosterProfiles;
import com.korleaguer.config.CurrentSeason;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.Batting;
import com.korleaguer.domain.Game;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerMoveRepository;
import com.korleaguer.domain.SeasonType;
import com.korleaguer.domain.Team;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 뛴 경기 줄의 점수와 승패. 뛴 기록 하나로 줄을 만드는 playedLine은 DB를 묻지 않아 리포지토리는 자리만 채운다 */
class GameLineAssemblerTest {

    private static final Team MIN = team(142L, "MIN");
    private static final Team DET = team(116L, "DET");

    private final GameLineAssembler assembler = new GameLineAssembler(mock(GameRepository.class),
            mock(AppearanceRepository.class), mock(PlayerMoveRepository.class), mock(RosterProfiles.class),
            mock(CurrentSeason.class));

    @Test
    @DisplayName("진행 중인 경기는 점수가 있어도 승패를 붙이지 않고, 끝난 경기에만 승패를 붙인다")
    void onlyFinishedGamesGetAWinOrLoss() {
        // given
        Appearance live = played(game(7001L, Game.STATUS_IN_PROGRESS, 3, 2));
        Appearance finished = played(game(7002L, Game.STATUS_FINAL, 3, 2));

        // when
        GameLine liveLine = assembler.playedLine(live);
        GameLine finishedLine = assembler.playedLine(finished);

        // then
        assertThat(liveLine).extracting(GameLine::teamScore, GameLine::opponentScore, GameLine::result)
                .containsExactly(3, 2, null);
        assertThat(finishedLine).extracting(GameLine::teamScore, GameLine::opponentScore, GameLine::result)
                .containsExactly(3, 2, "W");
    }

    /** MIN 홈 경기 */
    private static Game game(long gamePk, String status, int homeScore, int awayScore) {
        Game game = new Game(gamePk);
        game.update(2026, SeasonType.REGULAR.stored(), Instant.parse("2026-08-10T23:10:00Z"), MIN, DET, homeScore,
                awayScore, status, null);
        return game;
    }

    private static Appearance played(Game game) {
        Player player = new Player("김혜성");
        ReflectionTestUtils.setField(player, "id", 3L);
        Appearance appearance = new Appearance(player, game);
        appearance.update(MIN, new Batting(4, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4, 1), null);
        return appearance;
    }

    private static Team team(long id, String abbreviation) {
        Team team = new Team(id);
        team.update(abbreviation, abbreviation);
        return team;
    }
}
