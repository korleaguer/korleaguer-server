package com.korleaguer.ingest;

import com.korleaguer.client.MlbScheduleGame;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.domain.Game;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.support.CommittedDbTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.korleaguer.client.MlbFixtures.game;
import static com.korleaguer.client.MlbFixtures.team;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 일정 저장(GameWriter.saveGames)이 끝난 경기를 처음 본 시각을 남기는지. 저장한 뒤 DB에서 다시 읽어 본다.
 * 함께 쓰는 DB에는 샘플 수집이 남긴 경기가 있어, 표를 통째로 비우지 않고 이 클래스가 쓰는 시험 경기 줄만 지운다.
 */
@CommittedDbTest
class GameWriterFinalTest {

    private static final long GAME_PK = 990402L;
    private static final MlbTeam ATL = team(144, "Atlanta Braves");
    private static final MlbTeam NYM = team(121, "New York Mets");
    private static final String START = "2026-08-09T23:10:00Z";
    // 같은 UTC 날짜 안의 세 시각: 경기 중 수집, 끝난 뒤 수집, 그다음 수집
    private static final Instant LIVE_AT = Instant.parse("2026-08-10T01:00:00Z");
    private static final Instant FINAL_AT = Instant.parse("2026-08-10T02:30:00Z");
    private static final Instant LATER = Instant.parse("2026-08-10T03:00:00Z");

    @Autowired
    private GameWriter writer;

    @Autowired
    private GameRepository games;

    @BeforeEach
    void setUp() {
        games.deleteById(GAME_PK);
    }

    @AfterEach
    void tearDown() {
        games.deleteById(GAME_PK);
    }

    @Test
    @DisplayName("경기 중에 점수를 받은 경기가 같은 날 끝나면 끝난 점수를 한 번 더 받을 경기가 된다")
    void gameThatEndsAfterALiveCheckNeedsOneMoreBoxscoreTheSameDay() {
        // given
        writer.saveGames(List.of(live()), LIVE_AT);
        writer.saveBoxscore(GAME_PK, null, Map.of(), LIVE_AT);

        // when
        writer.saveGames(List.of(finished()), FINAL_AT);

        // then
        Game saved = games.findById(GAME_PK).orElseThrow();
        assertThat(saved.getFinalAt()).isEqualTo(FINAL_AT);
        assertThat(saved.needsBoxscore(FINAL_AT)).isTrue();
    }

    @Test
    @DisplayName("끝난 경기를 다시 받아도 처음 본 시각은 바뀌지 않는다")
    void firstFinalTimeStaysWhenTheFinishedGameComesAgain() {
        // given
        writer.saveGames(List.of(finished()), FINAL_AT);

        // when
        writer.saveGames(List.of(finished()), LATER);

        // then
        assertThat(games.findById(GAME_PK).orElseThrow().getFinalAt()).isEqualTo(FINAL_AT);
    }

    private static MlbScheduleGame live() {
        return game(GAME_PK, NYM, ATL, START, "R", "Live", "I", "In Progress", 1, 2);
    }

    private static MlbScheduleGame finished() {
        return game(GAME_PK, NYM, ATL, START, "R");
    }
}
