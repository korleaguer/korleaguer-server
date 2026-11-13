package com.korleaguer.ingest;

import com.korleaguer.client.MlbTransaction;
import com.korleaguer.client.SampleMlbClient;
import com.korleaguer.domain.IngestLog;
import com.korleaguer.domain.Level;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerMove;
import com.korleaguer.domain.PlayerMoveRepository;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.support.MutableClock;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 샘플 모드 수집이 MLB 단계·이동 기록을 저장하는지, 하루 한 번만 묻는지, 같은 줄을 다시 받아도 늘지 않는지(upsert) 확인한다.
 * 앱이 뜰 때(2026-11-01) 따라잡기 수집이 한 번 돈다.
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:moves-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlayerMovesIngestTest {

    private static final Instant START = Instant.parse("2026-11-01T11:00:00Z");

    @Autowired
    private IngestService ingestService;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private PlayerMoveRepository moves;

    @Autowired
    private EditableMlbClient client;

    @Autowired
    private MutableClock clock;

    /** 이동 기록 설명 글 뒤에 글자를 붙일 수 있는 샘플 클라이언트(MLB가 같은 번호의 줄을 고쳐 준 상황) */
    static class EditableMlbClient extends SampleMlbClient {

        volatile String suffix = "";

        EditableMlbClient(JsonMapper jsonMapper) {
            super(jsonMapper);
        }

        @Override
        public List<MlbTransaction> getTransactions(long personId, LocalDate startDate, LocalDate endDate) {
            return super.getTransactions(personId, startDate, endDate).stream()
                    .map(t -> new MlbTransaction(t.id(), t.person(), t.date(), t.effectiveDate(), t.typeCode(),
                            t.typeDesc(), t.description() + suffix, t.fromTeam(), t.toTeam()))
                    .toList();
        }
    }

    @TestConfiguration
    static class Scenario {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(START);
        }

        @Bean
        @Primary
        EditableMlbClient editableMlbClient(JsonMapper jsonMapper) {
            return new EditableMlbClient(jsonMapper);
        }
    }

    @Test
    @Order(1)
    void startupSavesLevelsAndAllMoves() {
        Player go = players.findByNameKo("고우석").orElseThrow();
        assertThat(go.getLevel()).isEqualTo(Level.MINORS);
        assertThat(go.getLevelTeamName()).isEqualTo("St. Paul Saints");
        assertThat(go.getLevelCheckedAt()).isEqualTo(START);
        Player lee = players.findByNameKo("이정후").orElseThrow();
        assertThat(lee.getLevel()).isEqualTo(Level.MLB);
        assertThat(lee.getLevelTeamName()).isEqualTo("San Francisco Giants");

        // 받은 줄은 모두 저장한다(화면에서 빼는 상태 변경 줄 포함).
        assertThat(moves.count()).isEqualTo(10);
        PlayerMove outright = moves.findById(990105L).orElseThrow();
        assertThat(outright.getPlayer().getId()).isEqualTo(go.getId());
        assertThat(outright.getMoveDate()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(outright.getFromTeamName()).isEqualTo("Minnesota Twins");
        assertThat(outright.getFromLevel()).isEqualTo(Level.MLB);
        assertThat(outright.getToTeamId()).isEqualTo(1960L);
        assertThat(outright.getToLevel()).isEqualTo(Level.MINORS);
        PlayerMove designated = moves.findById(990104L).orElseThrow();
        assertThat(designated.getFromTeamId()).isNull();
        assertThat(designated.getFromLevel()).isNull();
    }

    @Test
    @Order(2)
    void sameDayIsNotAskedAgain() {
        long before = client.requestCount();

        IngestLog log = ingestService.catchUp(false);

        assertThat(client.requestCount()).isEqualTo(before);
        assertThat(log.getMessage()).isEqualTo("할 일 없음");
    }

    @Test
    @Order(3)
    void nextDayUpsertsByMlbId() {
        clock.set(START.plus(Duration.ofDays(1)));
        client.suffix = " (edited)";
        long before = client.requestCount();

        IngestLog log = ingestService.catchUp(false);

        // 선수 정보 1회(6명 한 번에) + 이동 기록 6회 + 마이너 경기 수 6회. 마이너 팀 단계는 어제 물어 기억하므로
        // 다시 묻지 않는다. 시즌은 이미 받았으므로 다시 받지 않는다.
        // MLB 안 위치(하루 한 번): 리그 전체 타격·투구 4회 + yearByYear 6회. 지난 시즌 지표는 어제 물었으므로 다시 묻지 않는다.
        assertThat(client.requestCount() - before).isEqualTo(13 + 10);
        assertThat(log.getMessage()).isEqualTo("선수 정보·이동 기록 6명 · MLB 안 위치 6명");
        assertThat(moves.count()).isEqualTo(10);
        assertThat(moves.findById(990105L).orElseThrow().getDescription())
                .isEqualTo("Minnesota Twins sent RHP Woo-Suk Go outright to St. Paul Saints. (edited)");
        assertThat(players.findByNameKo("고우석").orElseThrow().getLevelCheckedAt()).isEqualTo(clock.instant());
    }
}
