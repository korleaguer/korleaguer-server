package com.korleaguer.ingest;

import com.korleaguer.client.MlbGameLogSplit;
import com.korleaguer.client.MlbPerson;
import com.korleaguer.client.MlbScheduleGame;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.domain.Affiliation;
import com.korleaguer.domain.AffiliationRepository;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.support.CommittedDbTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static com.korleaguer.client.MlbFixtures.batting;
import static com.korleaguer.client.MlbFixtures.game;
import static com.korleaguer.client.MlbFixtures.team;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시즌 백필의 gameLog 저장(GameWriter.saveSeason). 저장한 뒤 DB에서 다시 읽어 본다.
 * 함께 쓰는 DB에는 샘플 수집이 남긴 경기·기록이 있어, 표를 통째로 비우지 않고 이 클래스가 쓰는 시험 선수와 경기 줄만 지운다.
 */
@CommittedDbTest
class GameWriterGameLogTest {

    private static final String NAME = "경기기록시험타자";
    private static final long GAME_PK = 990401L;
    private static final MlbTeam ATL = team(144, "Atlanta Braves");
    private static final MlbTeam NYM = team(121, "New York Mets");
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Autowired
    private GameWriter writer;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private GameRepository games;

    @Autowired
    private AppearanceRepository appearances;

    @Autowired
    private AffiliationRepository affiliations;

    private Player player;

    @BeforeEach
    void setUp() {
        player = players.findByNameKo(NAME).orElseGet(() -> players.save(new Player(NAME)));
        removeTestGame();
    }

    /** 커밋한 시험 경기·출전 줄이 뒤에 도는 다른 커밋 DB 테스트에 보이지 않게 지운다(선수 줄은 다음 실행이 다시 쓴다) */
    @AfterEach
    void tearDown() {
        removeTestGame();
    }

    private void removeTestGame() {
        affiliations.findByPlayer(player).ifPresent(affiliations::delete);
        appearances.deleteAll(appearances.findByPlayersAndGames(List.of(player.getId()), List.of(GAME_PK)));
        games.deleteById(GAME_PK);
    }

    @Test
    @DisplayName("같은 경기에 빈 타격 줄이 먼저 와도 기록이 있는 줄을 저장한다")
    void emptyLineOfTheSameGameDoesNotHideTheRealOne() {
        // given
        MlbScheduleGame played = game(GAME_PK, NYM, ATL, "2026-08-09T23:10:00Z", "R");
        MlbGameLogSplit empty = new MlbGameLogSplit(played.season(), played.officialDate(), "R", null, null, ATL, null,
                new MlbGameLogSplit.GameRef(GAME_PK), MlbPerson.of(1L, null, null), null, null);
        MlbGameLogSplit real = batting(1L, ATL, played, 4, 2);

        // when
        writer.saveSeason(2026, List.of(played),
                List.of(new GameWriter.PlayerLogs(player.getId(), List.of(empty, real), List.of())), NOW);

        // then
        List<Appearance> saved = appearances.findByPlayersAndGames(List.of(player.getId()), List.of(GAME_PK));
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getBatting().atBats()).isEqualTo(4);
        assertThat(saved.getFirst().getBatting().hits()).isEqualTo(2);
    }

    @Test
    @DisplayName("소속 기록 없이 선수 정보를 받은 선수는 시즌 기록을 저장한 날(UTC 날짜)로 끝난 마지막 팀 소속이 남는다")
    void unaffiliatedPlayerKeepsTheLastTeamEndedOnTheUtcDay() {
        // given
        // 한국 시간으로는 8월 21일 오전이지만 수집의 하루는 UTC 날짜라 8월 20일이다
        Instant lateUtc = Instant.parse("2026-08-20T20:00:00Z");
        player.updateLevel(null, null, lateUtc);
        player = players.save(player);
        MlbScheduleGame played = game(GAME_PK, NYM, ATL, "2026-08-09T23:10:00Z", "R");

        // when
        writer.saveSeason(2026, List.of(played),
                List.of(new GameWriter.PlayerLogs(player.getId(), List.of(batting(1L, ATL, played, 4, 2)), List.of())),
                lateUtc);

        // then
        Affiliation kept = affiliations.findByPlayer(player).orElseThrow();
        assertThat(kept.getTeam().getMlbTeamId()).isEqualTo(144L);
        assertThat(kept.getEndDate()).isEqualTo(LocalDate.of(2026, 8, 20));
    }
}
