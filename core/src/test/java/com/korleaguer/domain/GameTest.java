package com.korleaguer.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 박스스코어를 받을 때와 기록이 굳는(settled) 때 */
class GameTest {

    private static final Instant START = Instant.parse("2026-08-09T23:10:00Z");

    @Test
    @DisplayName("경기 중이면 12분 전에 확인했어도 박스스코어를 다시 받는다")
    void inProgressIsFetchedEveryTime() {
        // given
        Game game = game("in_progress");
        Instant now = START.plus(Duration.ofHours(1));
        game.markChecked(now);

        // when // then
        assertThat(game.needsBoxscore(now.plusSeconds(720))).isTrue();
    }

    @Test
    @DisplayName("아직 예정인 경기는 박스스코어를 받지 않는다")
    void scheduledIsNotFetched() {
        // when // then
        assertThat(game("scheduled").needsBoxscore(START)).isFalse();
    }

    @Test
    @DisplayName("끝난 경기는 곧바로 한 번 받고, 같은 날은 건너뛰고 다음 날 다시 받는다")
    void finalIsFetchedOnceAfterFinalThenOncePerDay() {
        // given
        Game game = game("in_progress");
        Instant during = START.plus(Duration.ofHours(1));
        game.markChecked(during);

        // when
        // 끝난 것을 처음 봄 → 경기 중에 받은 것보다 늦으니 한 번 더 받는다.
        Instant end = START.plus(Duration.ofHours(3));
        game.update(2026, "regular", START, null, null, 2, 4, "final", null);
        game.markFinalIfNew(end);

        // then
        assertThat(game.needsBoxscore(end)).isTrue();

        // when
        game.markChecked(end);

        // then
        // 같은 날(UTC)에는 다시 받지 않고, 다음 날 한 번
        assertThat(game.needsBoxscore(end.plus(Duration.ofMinutes(30)))).isFalse();
        assertThat(game.needsBoxscore(Instant.parse("2026-08-11T00:30:00Z"))).isTrue();
        assertThat(game.isSettled()).isFalse();
    }

    @Test
    @DisplayName("끝난 경기 기록은 시작 사흘 뒤에 확인하면 굳고, 그 뒤로는 다시 받지 않는다")
    void settlesThreeDaysAfterStartOnCheck() {
        // given
        Game game = game("final");
        game.markFinalIfNew(START.plus(Duration.ofHours(3)));

        // when
        game.markChecked(START.plus(Duration.ofDays(2)));

        // then
        assertThat(game.isSettled()).isFalse();

        // when
        game.markChecked(START.plus(Duration.ofDays(3)));

        // then
        assertThat(game.isSettled()).isTrue();
        assertThat(game.needsBoxscore(START.plus(Duration.ofDays(4)))).isFalse();
    }

    @Test
    @DisplayName("시작한 지 30일 지난 끝난 경기는 기록을 바로 굳힌다")
    void oldFinalSettlesRightAway() {
        // given
        Game game = game("final");

        // when
        game.settleIfDue(START.plus(Duration.ofDays(30)));

        // then
        assertThat(game.isSettled()).isTrue();
    }

    private static Game game(String status) {
        Game game = new Game(7001L);
        game.update(2026, "regular", START, null, null, null, null, status, null);
        return game;
    }
}
