package com.korleaguer.ingest;

import com.korleaguer.client.MlbClient;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.domain.IngestKind;
import com.korleaguer.domain.IngestLog;
import com.korleaguer.domain.IngestLogRepository;
import com.korleaguer.domain.Lineup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지난 경기의 선발·교체를 뒤에서 채운다(샘플 모드, 앱 시작 때 따라잡기에서).
 * 끝난 경기마다 박스스코어를 한 번만 받고, 기록(타격·투구 숫자)은 건드리지 않는다.
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:lineup-fill-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class LineupFillTest {

    @Autowired
    private IngestService ingestService;

    @Autowired
    private IngestLogRepository ingestLogs;

    @Autowired
    private AppearanceRepository appearances;

    @Autowired
    private GameRepository games;

    @Autowired
    private MlbClient client;

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-01T11:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    void startupFillsEveryPastGameOnce() {
        IngestLog startup = ingestLogs.findAll().stream()
                .filter(l -> l.getKind() == IngestKind.SCHEDULED)
                .findFirst()
                .orElseThrow();
        // 샘플 기록 15줄(경기 13개)을 경기마다 박스스코어 한 번으로 채운다.
        assertThat(startup.getMessage()).contains("선발·교체 15개(경기 13개)");
        assertThat(appearances.findAll()).allSatisfy(a -> assertThat(a.getLineup()).isNotNull());
        assertThat(games.findAllById(List.of(1001L, 2001L, 4003L)))
                .allSatisfy(g -> assertThat(g.isLineupsChecked()).isTrue());

        // 김혜성 2001: 교체(타순 901), 기록은 gameLog 그대로(3타수 무안타)
        Appearance kimSub = appearances.findByPlayersAndGames(List.of(2L), List.of(2001L)).getFirst();
        assertThat(kimSub.getLineup()).isEqualTo(Lineup.SUB);
        assertThat(kimSub.getBatting().atBats()).isEqualTo(3);
        // 이정후는 선발, 고우석(구원)은 교체
        assertThat(appearances.findByPlayersAndGames(List.of(1L), List.of(1003L)).getFirst().getLineup())
                .isEqualTo(Lineup.STARTER);
        assertThat(appearances.findByPlayersAndGames(List.of(5L), List.of(4002L, 4003L)))
                .extracting(Appearance::getLineup)
                .containsOnly(Lineup.SUB);
    }

    @Test
    void filledGamesAreNotFetchedAgain() {
        long before = client.requestCount();

        IngestLog log = ingestService.catchUp(false);

        assertThat(client.requestCount()).isEqualTo(before);
        assertThat(log.getMessage()).isEqualTo("할 일 없음");
    }
}
