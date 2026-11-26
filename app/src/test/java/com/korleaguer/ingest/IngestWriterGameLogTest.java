package com.korleaguer.ingest;

import com.korleaguer.client.MlbGameLogSplit;
import com.korleaguer.client.MlbPerson;
import com.korleaguer.client.MlbScheduleGame;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.support.CommittedDbTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static com.korleaguer.client.MlbFixtures.batting;
import static com.korleaguer.client.MlbFixtures.game;
import static com.korleaguer.client.MlbFixtures.team;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시즌 백필의 gameLog 저장(IngestWriter.saveSeason). 저장한 뒤 DB에서 다시 읽어 본다.
 * 함께 쓰는 DB에는 샘플 수집이 남긴 경기·기록이 있어, 표를 통째로 비우지 않고 이 클래스가 쓰는 시험 선수와 경기 줄만 지운다.
 */
@CommittedDbTest
class IngestWriterGameLogTest {

    private static final String NAME = "경기기록시험타자";
    private static final long GAME_PK = 990401L;
    private static final MlbTeam ATL = team(144, "Atlanta Braves");
    private static final MlbTeam NYM = team(121, "New York Mets");
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Autowired
    private IngestWriter writer;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private GameRepository games;

    @Autowired
    private AppearanceRepository appearances;

    private Player player;

    @BeforeEach
    void setUp() {
        player = players.findByNameKo(NAME).orElseGet(() -> players.save(new Player(NAME)));
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
                List.of(new IngestWriter.PlayerLogs(player.getId(), List.of(empty, real), List.of())), NOW);

        // then
        List<Appearance> saved = appearances.findByPlayersAndGames(List.of(player.getId()), List.of(GAME_PK));
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getBatting().atBats()).isEqualTo(4);
        assertThat(saved.getFirst().getBatting().hits()).isEqualTo(2);
    }
}
