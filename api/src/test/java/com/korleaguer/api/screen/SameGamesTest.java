package com.korleaguer.api.screen;

import com.korleaguer.api.game.GameLine;

import com.korleaguer.domain.Role;
import com.korleaguer.domain.SeasonType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 같은 경기 짝의 전적·팀 이름 세기(SameGamesService, 이슈 #217) */
class SameGamesTest {

    private static GameLine line(long playerId, long gameId, String teamKo, String result) {
        return new GameLine(playerId, "선수" + playerId, null, Role.HITTER, gameId,
                OffsetDateTime.parse("2026-06-18T02:00:00+09:00").plusHours(gameId), teamKo, null, "상대", null, true,
                result == null ? null : 3, result == null ? null : 2, result, result == null ? "Live" : "Final", true,
                "3타수 1안타", null, null, null, SeasonType.REGULAR, null);
    }

    private static SameGamesResponse.Meeting meeting(long gameId, String teamKo, String result) {
        return new SameGamesResponse.Meeting(line(1, gameId, teamKo, result), line(2, gameId, "상대", null));
    }

    @Test
    @DisplayName("내 팀 전적은 끝난 경기의 승과 패만 세고 무승부와 진행 중은 뺀다")
    void countsOnlyFinishedWinsAndLossesOfMyTeam() {
        // given
        // 더블헤더 두 경기(같은 날)와 진행 중인 경기, 무승부
        List<SameGamesResponse.Meeting> meetings = List.of(meeting(1, "샌프란시스코", "W"),
                meeting(2, "샌프란시스코", "L"), meeting(3, "샌프란시스코", "W"), meeting(4, "샌프란시스코", null),
                meeting(5, "샌프란시스코", "T"));

        // when // then
        assertThat(SameGamesService.count(meetings, "W")).isEqualTo(2);
        assertThat(SameGamesService.count(meetings, "L")).isEqualTo(1);
        assertThat(SameGamesService.singleTeam(meetings)).isEqualTo("샌프란시스코");
    }

    @Test
    @DisplayName("두 선수가 같이 나온 경기들 사이에 팀을 옮겼으면 팀 이름을 비운다")
    void teamNameIsNullWhenThePlayerChangedTeamsBetweenMeetings() {
        // given
        List<SameGamesResponse.Meeting> meetings = List.of(meeting(1, "애틀랜타", "W"), meeting(2, "탬파베이", "L"));

        // when // then
        assertThat(SameGamesService.singleTeam(meetings)).isNull();
    }
}
