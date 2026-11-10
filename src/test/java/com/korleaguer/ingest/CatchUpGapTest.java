package com.korleaguer.ingest;

import com.korleaguer.client.MlbBoxscore;
import com.korleaguer.client.MlbScheduleGame;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.client.SampleMlbClient;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.Game;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.korleaguer.client.MlbFixtures.batting;
import static com.korleaguer.client.MlbFixtures.boxscore;
import static com.korleaguer.client.MlbFixtures.game;
import static com.korleaguer.client.MlbFixtures.hitter;
import static com.korleaguer.client.MlbFixtures.hittingStat;
import static com.korleaguer.client.MlbFixtures.team;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서버가 멈췄거나 MLB가 막아 며칠 경기 갱신을 못 했으면, 다음 따라잡기가 밀린 날부터 오늘까지 받는지 본다.
 * 김하성(ATL) 한 명. 8/10 18:00Z에 앱이 뜨며 시즌을 받았고(7001은 끝남, 7002~7004는 경기 전), 8/14 18:00Z까지 멈췄다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:catch-up-gap-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "roster.baseball[0].name-ko=김하성",
        "roster.baseball[0].mlb-id=673490"
})
@ActiveProfiles("test")
class CatchUpGapTest {

    private static final MlbTeam ATL = team(144, "Atlanta Braves");
    private static final MlbTeam NYM = team(121, "New York Mets");
    private static final Instant START = Instant.parse("2026-08-10T18:00:00Z");

    @Autowired
    private IngestService ingestService;

    @Autowired
    private RecordingClient client;

    @Autowired
    private MutableClock clock;

    @Autowired
    private GameRepository games;

    @Autowired
    private AppearanceRepository appearances;

    /** 일정을 물은 날짜 범위도 적어 두는 클라이언트 */
    static class RecordingClient extends GameIngestTest.SwitchableClient {

        final List<String> schedulesAsked = new ArrayList<>();

        RecordingClient(SampleMlbClient.Data data) {
            super(data);
        }

        List<String> takeSchedulesAsked() {
            List<String> asked = List.copyOf(schedulesAsked);
            schedulesAsked.clear();
            return asked;
        }

        @Override
        public List<MlbScheduleGame> getSchedule(Collection<Long> teamIds, LocalDate startDate, LocalDate endDate) {
            schedulesAsked.add(startDate + "~" + endDate);
            return super.getSchedule(teamIds, startDate, endDate);
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
        RecordingClient recordingClient() {
            return new RecordingClient(beforeGap());
        }
    }

    @Test
    void catchUpRefreshesFromMissedDays() {
        // 앱 시작 때 시즌 백필: 7001(8/6)은 끝나 굳었고, 7002~7004는 경기 전으로 저장됐다.
        assertThat(stored(7001).isSettled()).isTrue();
        assertThat(stored(7002).getStatusState()).isEqualTo("scheduled");
        assertThat(games.findById(7005L)).isEmpty();

        // 8/14 18:00Z에 다시 깨어남. 그사이 7002~7004가 끝났고, DB에 없던 7005(8/13, 나중에 잡힌 경기)도 끝났다.
        clock.set(Instant.parse("2026-08-14T18:00:00Z"));
        client.use(afterGap());
        client.takeSchedulesAsked();
        ingestService.catchUp(true);

        // 마지막으로 끝난 경기(7001)의 날부터 오늘까지 일정 1번, 결과를 못 받은 경기만 박스스코어로 받는다.
        assertThat(client.takeSchedulesAsked()).containsExactly("2026-08-06~2026-08-14");
        assertThat(client.takeBoxscoresAsked()).containsExactlyInAnyOrder(7002L, 7003L, 7004L, 7005L);
        for (long gamePk : List.of(7002L, 7003L, 7004L, 7005L)) {
            assertThat(stored(gamePk).getStatusState()).isEqualTo("final");
            assertThat(appearances.findByPlayersAndGames(List.of(1L), List.of(gamePk))).hasSize(1);
        }

        // 따라잡은 뒤에는 다시 어제·오늘만 받는다.
        clock.set(Instant.parse("2026-08-14T18:12:00Z"));
        ingestService.catchUp(true);
        assertThat(client.takeSchedulesAsked()).containsExactly("2026-08-13~2026-08-14");
        assertThat(client.takeBoxscoresAsked()).isEmpty();
    }

    private Game stored(long gamePk) {
        return games.findById(gamePk).orElseThrow();
    }

    private static MlbScheduleGame game7001() {
        return game(7001, NYM, ATL, "2026-08-06T23:10:00Z", "R");
    }

    private static MlbScheduleGame scheduled(long gamePk, String gameDate) {
        return game(gamePk, NYM, ATL, gameDate, "R", "Preview", "S", "Scheduled", null, null);
    }

    private static MlbScheduleGame finished(long gamePk, String gameDate) {
        return game(gamePk, NYM, ATL, gameDate, "R", "Final", "F", "Final", 1, 3);
    }

    private static MlbBoxscore box() {
        return boxscore(NYM, ATL, true, 673490, "600", hittingStat(4, 1), null, List.of(1L), 1, 3);
    }

    private static SampleMlbClient.Data beforeGap() {
        MlbScheduleGame game7001 = game7001();
        return SampleMlbClient.Data.empty()
                .withPeople(List.of(hitter(673490, "Ha-Seong Kim", ATL)))
                .withSchedule(List.of(game7001,
                        scheduled(7002, "2026-08-10T23:10:00Z"),
                        scheduled(7003, "2026-08-11T23:10:00Z"),
                        scheduled(7004, "2026-08-12T23:10:00Z")))
                .withHittingLogs(List.of(batting(673490, ATL, game7001, 3, 1)));
    }

    private static SampleMlbClient.Data afterGap() {
        return SampleMlbClient.Data.empty()
                .withPeople(List.of(hitter(673490, "Ha-Seong Kim", ATL)))
                .withSchedule(List.of(game7001(),
                        finished(7002, "2026-08-10T23:10:00Z"),
                        finished(7003, "2026-08-11T23:10:00Z"),
                        finished(7004, "2026-08-12T23:10:00Z"),
                        finished(7005, "2026-08-13T17:05:00Z"),
                        scheduled(7006, "2026-08-14T23:10:00Z")))
                .withBoxscores(Map.of(7002L, box(), 7003L, box(), 7004L, box(), 7005L, box()));
    }
}
