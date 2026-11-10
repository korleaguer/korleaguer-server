package com.korleaguer.ingest;

import com.korleaguer.client.MlbApiException;
import com.korleaguer.client.MlbBoxscore;
import com.korleaguer.client.MlbGameLogSplit;
import com.korleaguer.client.MlbScheduleGame;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.client.SampleMlbClient;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.domain.Lineup;
import com.korleaguer.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.korleaguer.client.MlbFixtures.batting;
import static com.korleaguer.client.MlbFixtures.boxscore;
import static com.korleaguer.client.MlbFixtures.game;
import static com.korleaguer.client.MlbFixtures.hitter;
import static com.korleaguer.client.MlbFixtures.hittingStat;
import static com.korleaguer.client.MlbFixtures.team;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 선발·교체를 이미 채워 본 경기에 나중에 새 기록이 생기면(시즌 백필이 늦은 선수) 그 경기를 다시 채운다.
 * 김하성(ATL)과 이정후(SF)가 둘 다 뛴 지난 경기 7001(SF@ATL, 굳은 경기). 앱 시작 때 이정후의 gameLog만 실패해
 * 김하성 기록으로 7001을 먼저 채우고, 다음 따라잡기에서 이정후 시즌을 받는다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:lineup-refill-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "roster.baseball[0].name-ko=김하성",
        "roster.baseball[0].mlb-id=673490",
        "roster.baseball[1].name-ko=이정후",
        "roster.baseball[1].mlb-id=808982"
})
@ActiveProfiles("test")
class LineupRefillTest {

    private static final MlbTeam ATL = team(144, "Atlanta Braves");
    private static final MlbTeam SF = team(137, "San Francisco Giants");
    private static final long KIM = 673490;
    private static final long LEE = 808982;
    /** 선수 id(명단 순서) */
    private static final long KIM_ID = 1L;
    private static final long LEE_ID = 2L;

    @Autowired
    private IngestService ingestService;

    @Autowired
    private FlakyGameLogClient client;

    @Autowired
    private AppearanceRepository appearances;

    @Autowired
    private GameRepository games;

    /** failing에 넣은 선수의 gameLog는 MLB 오류(5xx 등)로 답하는 클라이언트 */
    static class FlakyGameLogClient extends GameIngestTest.SwitchableClient {

        final Set<Long> failing = ConcurrentHashMap.newKeySet();

        FlakyGameLogClient(SampleMlbClient.Data data) {
            super(data);
        }

        @Override
        public List<MlbGameLogSplit> getGameLog(long personId, Group group, int season) {
            if (failing.contains(personId)) {
                throw new MlbApiException("test: gameLog failed", null);
            }
            return super.getGameLog(personId, group, season);
        }
    }

    @TestConfiguration
    static class Scenario {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-08-20T12:00:00Z"));
        }

        @Bean
        @Primary
        FlakyGameLogClient flakyGameLogClient() {
            MlbScheduleGame game7001 = game(7001, SF, ATL, "2026-08-09T23:10:00Z", "R");
            // 7001 박스스코어: 이정후는 SF 교체(타순 301), 김하성은 ATL 선발(타순 600)
            MlbBoxscore lee = boxscore(SF, ATL, false, LEE, "301", hittingStat(1, 0), null, List.of(), 2, 4);
            MlbBoxscore kim = boxscore(SF, ATL, true, KIM, "600", hittingStat(3, 1), null, List.of(), 2, 4);
            MlbBoxscore box = new MlbBoxscore(new MlbBoxscore.Teams(lee.teams().away(), kim.teams().home()));
            FlakyGameLogClient client = new FlakyGameLogClient(SampleMlbClient.Data.empty()
                    .withPeople(List.of(hitter(KIM, "Ha-Seong Kim", ATL), hitter(LEE, "Jung Hoo Lee", SF)))
                    .withSchedule(List.of(game7001))
                    .withHittingLogs(List.of(batting(KIM, ATL, game7001, 3, 1), batting(LEE, SF, game7001, 1, 0)))
                    .withBoxscores(Map.of(7001L, box)));
            client.failing.add(LEE);
            return client;
        }
    }

    @Test
    void newAppearanceInCheckedGameIsFilledAgain() {
        // 앱 시작 때: 이정후 gameLog 실패. 김하성 기록으로 7001을 박스스코어 한 번에 채우고 "채워 봤음"으로 둔다.
        assertThat(client.takeBoxscoresAsked()).containsExactly(7001L);
        assertThat(line(KIM_ID).getLineup()).isEqualTo(Lineup.STARTER);
        assertThat(games.findById(7001L).orElseThrow().isLineupsChecked()).isTrue();
        assertThat(appearances.findByPlayersAndGames(List.of(LEE_ID), List.of(7001L))).isEmpty();

        // 다음 따라잡기: 이정후 시즌을 받아 7001에 새 기록이 생긴다. 선발·교체를 모르므로 7001을 다시 채운다.
        client.failing.clear();
        ingestService.catchUp(false);
        assertThat(client.takeBoxscoresAsked()).containsExactly(7001L);
        assertThat(line(LEE_ID).getLineup()).isEqualTo(Lineup.SUB);
        assertThat(line(LEE_ID).getBatting().atBats()).isEqualTo(1); // 기록은 gameLog 그대로
        assertThat(line(KIM_ID).getLineup()).isEqualTo(Lineup.STARTER);

        // 다 채웠으니 다시 받지 않는다.
        ingestService.catchUp(false);
        assertThat(client.takeBoxscoresAsked()).isEmpty();
    }

    private Appearance line(long playerId) {
        return appearances.findByPlayersAndGames(List.of(playerId), List.of(7001L)).getFirst();
    }
}
