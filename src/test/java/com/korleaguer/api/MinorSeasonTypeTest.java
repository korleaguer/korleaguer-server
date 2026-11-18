package com.korleaguer.api;

import com.korleaguer.domain.Batting;
import com.korleaguer.domain.MinorGameLog;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.Role;
import com.korleaguer.domain.SeasonType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 마이너 경기 줄의 경기 종류(이슈 #190). 마이너 경기 기록은 정규시즌만 받으므로(MlbClient.getMinorGameLog) 선수 화면의 마이너 경기
 * 줄(MinorGameLine)과 내 선수 카드의 마이너 경기 줄(GameLine) 모두 REGULAR다.
 */
class MinorSeasonTypeTest {

    private static final Player KIM = new Player("김혜성");
    private static final Batting ONE_HIT = new Batting(4, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4, 1);

    static {
        // 내 선수 카드 줄(GameLine)은 선수 번호가 있어야 만든다(DB에 넣지 않은 선수라 직접 둔다)
        ReflectionTestUtils.setField(KIM, "id", 2L);
    }

    @Test
    void minorLinesAreRegularSeason() {
        List<MinorGameLog> games = List.of(game(1, LocalDate.of(2026, 9, 19)), game(2, LocalDate.of(2026, 9, 20)));

        List<MinorGameLine> pageLines = MinorRecords.gameLines(Role.HITTER, games,
                new MinorTeamNames(new MinorTeamProperties(List.of())));
        List<GameLine> cardLines = MinorRecords.lines(KIM, null, games);

        assertThat(pageLines).extracting(MinorGameLine::seasonType).containsExactly(SeasonType.REGULAR, SeasonType.REGULAR);
        assertThat(cardLines).extracting(GameLine::seasonType).containsExactly(SeasonType.REGULAR, SeasonType.REGULAR);
    }

    private static MinorGameLog game(long gamePk, LocalDate date) {
        MinorGameLog game = new MinorGameLog(KIM, 2026, gamePk);
        game.updateGame(date, "AAA", "Oklahoma City Comets", "Albuquerque Isotopes", true, true);
        game.updateTeamIds(238L, 342L);
        game.updateBatting(ONE_HIT);
        return game;
    }
}
