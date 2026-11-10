package com.korleaguer.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 박스스코어를 받을 때와 기록이 굳는(settled) 때 */
class GameTest {

    private static final Instant START = Instant.parse("2026-08-09T23:10:00Z");

    @Test
    void inProgressIsFetchedEveryTime() {
        Game game = game("in_progress");
        Instant now = START.plus(Duration.ofHours(1));
        game.markChecked(now);

        assertThat(game.needsBoxscore(now.plusSeconds(720))).isTrue();
    }

    @Test
    void scheduledIsNotFetched() {
        assertThat(game("scheduled").needsBoxscore(START)).isFalse();
    }

    @Test
    void finalIsFetchedOnceAfterFinalThenOncePerDay() {
        Game game = game("in_progress");
        Instant during = START.plus(Duration.ofHours(1));
        game.markChecked(during);

        // 끝난 것을 처음 봄 → 경기 중에 받은 것보다 늦으니 한 번 더 받는다.
        Instant end = START.plus(Duration.ofHours(3));
        game.update(2026, "regular", START, null, null, 2, 4, "final", null);
        game.markFinalIfNew(end);
        assertThat(game.needsBoxscore(end)).isTrue();
        game.markChecked(end);

        // 같은 날(UTC)에는 다시 받지 않고, 다음 날 한 번
        assertThat(game.needsBoxscore(end.plus(Duration.ofMinutes(30)))).isFalse();
        assertThat(game.needsBoxscore(Instant.parse("2026-08-11T00:30:00Z"))).isTrue();
        assertThat(game.isSettled()).isFalse();
    }

    @Test
    void settlesThreeDaysAfterStartOnCheck() {
        Game game = game("final");
        game.markFinalIfNew(START.plus(Duration.ofHours(3)));

        game.markChecked(START.plus(Duration.ofDays(2)));
        assertThat(game.isSettled()).isFalse();

        game.markChecked(START.plus(Duration.ofDays(3)));
        assertThat(game.isSettled()).isTrue();
        assertThat(game.needsBoxscore(START.plus(Duration.ofDays(4)))).isFalse();
    }

    @Test
    void oldFinalSettlesRightAway() {
        Game game = game("final");
        game.settleIfDue(START.plus(Duration.ofDays(30)));

        assertThat(game.isSettled()).isTrue();
    }

    private static Game game(String status) {
        Game game = new Game(7001L);
        game.update(2026, "regular", START, null, null, null, null, status, null);
        return game;
    }
}
