package com.korleaguer.api.card;

import com.korleaguer.api.game.GameLine;
import com.korleaguer.api.game.MinorGameLine;
import com.korleaguer.api.player.MinorTeamNames;
import com.korleaguer.api.season.MinorRecords;

import com.korleaguer.config.MinorTeamProperties;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.Game;
import com.korleaguer.domain.MinorGameLog;
import com.korleaguer.domain.MinorGameSchedule;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.Team;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 내 선수 카드의 마지막 경기(MyPlayersService.lastGame, 이슈 #183). 응답 lastGameDate로 나가는 한국 날짜는 그 경기 시작
 * 시각의 한국 날짜이고(선수 화면 경기 달력과 같은 날), 시즌 끝 판단에 쓰는 미국 날짜도 함께 둔다. 카드의 마이너 경기 줄·기간도
 * 달력과 같은 시작 시각(MinorRecords.startTimeKst)을 쓴다.
 */
class LastGameTest {

    private static final Player PLAYER = new Player("선수");
    private static final Team HOME = new Team(137L);
    private static final Team AWAY = new Team(115L);

    @Test
    @DisplayName("미국 밤 경기는 마지막 경기 날짜가 한국 날짜로 다음 날이 된다")
    void usNightGameIsTheNextDayInKorea() {
        // when
        // 미국 9월 27일 밤 9시 10분(동부) = 한국 9월 28일 오전 10시 10분
        CardStatuses.LastGame last = MyPlayersService.lastGame(List.of(major(1, "2026-09-28T01:10:00Z")), List.of());

        // then
        assertThat(last.kstDate()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(last.usDate()).isEqualTo(LocalDate.of(2026, 9, 27));
        assertThat(last.level()).isEqualTo("MLB");
    }

    @Test
    @DisplayName("한국에서 저녁에 연 경기는 두 나라 날짜가 같다")
    void gameOnTheSameDayInKoreaKeepsItsDate() {
        // when
        // 한국에서 연 경기(서울 시리즈처럼 한국 저녁 7시 5분 = 미국 동부 오전 6시 5분): 두 나라 날짜가 같다
        CardStatuses.LastGame last = MyPlayersService.lastGame(List.of(major(1, "2026-09-27T10:05:00Z")), List.of());

        // then
        assertThat(last.kstDate()).isEqualTo(LocalDate.of(2026, 9, 27));
        assertThat(last.usDate()).isEqualTo(LocalDate.of(2026, 9, 27));
    }

    @Test
    @DisplayName("마이너 마지막 경기 날짜는 일정의 시작 시각을 한국 날짜로 바꾼 날이다")
    void minorGameUsesTheScheduledStartTimeLikeTheCalendar() {
        // when
        // 공식 날짜 9월 20일, 시작 미국 저녁 8시 5분(동부) = 한국 9월 21일
        CardStatuses.LastGame evening = MyPlayersService.lastGame(List.of(),
                List.of(minor(1, "2026-09-20", "2026-09-21T00:05:00Z")));

        // then
        assertThat(evening.kstDate()).isEqualTo(LocalDate.of(2026, 9, 21));
        assertThat(evening.usDate()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(evening.level()).isEqualTo("AAA");

        // when
        // 오전 경기(미국 동부 10시 35분 = 한국 같은 날 밤 11시 35분): 달력처럼 공식 날짜와 같은 날이다
        // (예전처럼 공식 날짜 다음 날로 셌으면 달력과 하루 어긋났다)
        CardStatuses.LastGame morning = MyPlayersService.lastGame(List.of(),
                List.of(minor(1, "2026-08-05", "2026-08-05T14:35:00Z")));

        // then
        assertThat(morning.kstDate()).isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(morning.kstDate())
                .isEqualTo(MinorRecords.gameLines(PLAYER.role(), List.of(minor(1, "2026-08-05", "2026-08-05T14:35:00Z")),
                        new MinorTeamNames(new MinorTeamProperties(List.of()))).getFirst().startTimeKst().toLocalDate());
    }

    @Test
    @DisplayName("시작 시각을 모르는 마이너 경기는 공식 날짜 다음 날을 한국 날짜로 쓴다")
    void minorGameWithoutScheduleIsTheNextDayAfterTheOfficialDate() {
        // when
        // 시작 시각을 아직 모른다(일정을 받기 전): 달력과 같이 공식 날짜 다음 날(미국 저녁 경기 = 한국 다음 날 아침)
        CardStatuses.LastGame last = MyPlayersService.lastGame(List.of(), List.of(minor(1, "2026-09-20", null)));

        // then
        assertThat(last.kstDate()).isEqualTo(LocalDate.of(2026, 9, 21));
        assertThat(last.usDate()).isEqualTo(LocalDate.of(2026, 9, 20));
    }

    @Test
    @DisplayName("내 선수 카드의 마이너 경기 시작 시각은 경기 달력과 같다")
    void myCardMinorLinesUseTheSameStartTimeAsTheCalendar() {
        // given
        // 내 선수 카드의 마이너 경기 줄·기간(kstDate)도 선수 화면 달력과 같은 시작 시각을 쓴다(같은 경기가 같은 날)
        Player withId = new Player("선수");
        ReflectionTestUtils.setField(withId, "id", 1L);
        MinorGameLog morning = minor(1, "2026-08-05", "2026-08-05T14:35:00Z"); // 미국 동부 10시 35분 = 한국 같은 날 밤
        MinorGameLog unknown = minor(2, "2026-08-04", null); // 일정을 아직 받지 못했다
        List<MinorGameLog> games = List.of(unknown, morning);

        // when
        List<GameLine> card = MinorRecords.lines(withId, null, null, games);
        List<MinorGameLine> calendar = MinorRecords.gameLines(withId.role(), games,
                new MinorTeamNames(new MinorTeamProperties(List.of())));

        // then
        assertThat(card).extracting(GameLine::startTimeKst)
                .containsExactlyElementsOf(calendar.stream().map(MinorGameLine::startTimeKst).toList());
        // 오전 경기는 공식 날짜와 같은 한국 날짜(예전에는 늘 다음 날이라 카드 기간에서 하루 밀렸다)
        assertThat(MinorRecords.kstDate(morning)).isEqualTo(LocalDate.of(2026, 8, 5));
        // 시작 시각을 모르면 공식 날짜 다음 날 08:00(한국)으로 어림한다
        assertThat(MinorRecords.kstDate(unknown)).isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(card.getLast().startTimeKst()).isEqualTo(OffsetDateTime.parse("2026-08-05T08:00:00+09:00"));
    }

    @Test
    @DisplayName("가장 늦게 시작한 경기를 마지막 경기로 고르고 시각이 같으면 메이저를 고른다")
    void theGameThatStartedLaterIsTheLastGame() {
        // given
        Appearance major = major(1, "2026-09-20T23:10:00Z"); // 한국 9월 21일 08:10
        MinorGameLog minor = minor(2, "2026-09-21", "2026-09-21T17:05:00Z"); // 한국 9월 22일 02:05

        // when // then
        assertThat(MyPlayersService.lastGame(List.of(major), List.of(minor)).level()).isEqualTo("AAA");
        assertThat(MyPlayersService.lastGame(List.of(major(3, "2026-09-22T23:10:00Z")), List.of(minor)).level())
                .isEqualTo("MLB");

        // given
        // 마이너는 공식 날짜 순이 아니라 늦게 시작한 경기(더블헤더 둘째 경기가 번호는 작아도 늦다)
        MinorGameLog second = minor(4, "2026-09-25", "2026-09-25T23:05:00Z");
        MinorGameLog first = minor(5, "2026-09-25", "2026-09-25T17:05:00Z");
        first.updateGame(first.getGameDate(), "AA", "Team", "Opponent", true, true);

        // when // then
        assertThat(MyPlayersService.lastGame(List.of(), List.of(second, first)).level()).isEqualTo("AAA");
        // 같은 시각이면 메이저
        assertThat(MyPlayersService.lastGame(List.of(major(6, "2026-09-25T23:05:00Z")), List.of(second)).level())
                .isEqualTo("MLB");
        assertThat(MyPlayersService.lastGame(List.of(), List.of())).isNull();
    }

    private static Appearance major(long gamePk, String startUtc) {
        Game game = new Game(gamePk);
        game.update(2026, "regular", Instant.parse(startUtc), HOME, AWAY, 4, 2, "final", "Test Park");
        return new Appearance(PLAYER, game);
    }

    /** 트리플A 경기. startUtc가 null이면 일정을 아직 받지 못한 경기 */
    private static MinorGameLog minor(long gamePk, String officialDate, String startUtc) {
        MinorGameLog game = new MinorGameLog(PLAYER, 2026, gamePk);
        game.updateGame(LocalDate.parse(officialDate), "AAA", "Team", "Opponent", true, true);
        if (startUtc != null) {
            game.updateSchedule(new MinorGameSchedule(Instant.parse(startUtc), "final", 3, 1, null, null));
        }
        return game;
    }
}
