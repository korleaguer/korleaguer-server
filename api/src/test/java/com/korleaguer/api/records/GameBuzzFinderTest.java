package com.korleaguer.api.records;

import com.korleaguer.api.game.GameLine;
import com.korleaguer.domain.Role;
import com.korleaguer.domain.SeasonType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 넘은 기록을 경기 줄과 짝짓기(이슈 #282). 날짜는 이정후의 실제 더블헤더(한국 6/18)이고 넘은 기록은 지어낸 값이다. */
class GameBuzzFinderTest {

    private static final long LEE = 1;
    private static final long HYESEONG = 2;
    private static final GameLine FIRST_GAME = line(11, "2026-06-18T03:00:00+09:00");
    private static final GameLine SECOND_GAME = line(12, "2026-06-18T08:15:00+09:00");

    @Test
    @DisplayName("더블헤더에서는 기록을 넘은 경기에만 '넘음' 문구를 붙인다")
    void onlyTheGameThatCrossedGetsTheText() {
        // given
        List<RecordPassed> passed = List.of(passed(LEE, 11, "homeRuns", "홈런", "이대호", 14));

        // when // then
        assertThat(GameBuzzFinder.passed(FIRST_GAME, passed)).contains(new GameBuzz(LEE, 11, GameBuzz.PASSED,
                "homeRuns", "한국인 통산 홈런 이대호(14개) 넘음"));
        assertThat(GameBuzzFinder.passed(SECOND_GAME, passed)).isEmpty();
    }

    @Test
    @DisplayName("더블헤더 두 경기에서 하나씩 넘었으면 경기마다 자기 기록을 붙인다")
    void eachGameGetsItsOwnText() {
        // given
        List<RecordPassed> passed = List.of(passed(LEE, 11, "homeRuns", "홈런", "이대호", 14),
                passed(LEE, 12, "hits", "안타", "강정호", 233));

        // when // then
        assertThat(GameBuzzFinder.passed(FIRST_GAME, passed)).map(GameBuzz::stat).contains("homeRuns");
        assertThat(GameBuzzFinder.passed(SECOND_GAME, passed)).map(GameBuzz::stat).contains("hits");
    }

    @Test
    @DisplayName("같은 경기에서 다른 선수가 넘은 기록은 붙이지 않는다")
    void anotherPlayersRecordInTheSameGameIsNotHis() {
        // given
        List<RecordPassed> passed = List.of(passed(HYESEONG, 11, "hits", "안타", "이대호", 74));

        // when // then
        assertThat(GameBuzzFinder.passed(FIRST_GAME, passed)).isEmpty();
    }

    private static RecordPassed passed(long playerId, long gameId, String category, String label, String passedNameKo,
                                       int passedValue) {
        return new RecordPassed(playerId, 800000 + playerId, "선수", null, category, label, label, gameId,
                LocalDate.of(2026, 6, 18), passedNameKo, passedValue);
    }

    private static GameLine line(long gameId, String startTimeKst) {
        return new GameLine(LEE, "이정후", null, Role.HITTER, gameId, OffsetDateTime.parse(startTimeKst),
                "샌프란시스코", "SF", "애틀랜타", "ATL", true, 5, 3, "W", "final", true, "3타수 1안타 1홈런 2타점",
                null, null, null, SeasonType.REGULAR, "SF");
    }
}
