package com.korleaguer.ingest;

import com.korleaguer.client.MlbBoxscore;
import com.korleaguer.client.MlbScheduleGame;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.client.SampleMlbClient;
import com.korleaguer.domain.AppearanceRepository;
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
import java.util.ArrayList;
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
 * 포스트시즌 라운드 사이 쉬는 날이 길어 DB만 보면 비시즌일 때, 스케줄이 하루 한 번 앞 일정을 받아 시즌 중으로 돌아오는지 본다.
 * 김하성(ATL) 한 명. ATL은 9/26 정규시즌 마지막 경기(7001) 뒤 쉬고, 디비전 시리즈 1차전(7201)이 10/4다.
 * 7201은 앱이 시즌을 받은 9/30에는 일정에 없었다(포스트시즌 경기는 대진이 정해진 뒤 잡힌다).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:look-ahead-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "roster.baseball[0].name-ko=김하성",
        "roster.baseball[0].mlb-id=673490"
})
@ActiveProfiles("test")
class OffSeasonLookAheadTest {

    private static final MlbTeam ATL = team(144, "Atlanta Braves");
    private static final MlbTeam NYM = team(121, "New York Mets");
    private static final Instant START = Instant.parse("2026-09-30T12:00:00Z");

    @Autowired
    private IngestService ingestService;

    @Autowired
    private CatchUpGapTest.RecordingClient client;

    @Autowired
    private MutableClock clock;

    @Autowired
    private GameRepository games;

    @Autowired
    private AppearanceRepository appearances;

    @TestConfiguration
    static class Scenario {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(START);
        }

        @Bean
        @Primary
        CatchUpGapTest.RecordingClient recordingClient() {
            return new CatchUpGapTest.RecordingClient(data(List.of(), Map.of()));
        }
    }

    @Test
    void offSeasonLooksAheadOncePerDayAndComesBackForPostseason() {
        List<Object> published = new ArrayList<>();
        IngestScheduler scheduler = new IngestScheduler(ingestService, published::add, clock);

        // 10/1 00:00Z: 앞뒤 3일에 DB 경기가 없어 비시즌이다. 그사이 MLB 일정에 7201이 잡혔다.
        clock.set(Instant.parse("2026-10-01T00:00:00Z"));
        client.use(data(List.of(scheduled7201()), Map.of()));
        assertThat(ingestService.inSeason(clock.instant())).isFalse();
        client.takeSchedulesAsked();
        scheduler.tick();
        // 따라잡기가 끝났다고 알린다(화면용 응답을 캐시에 미리 채운다).
        assertThat(published).containsExactly(new CatchUpFinished());
        // 앞으로 7일 일정(미국 날짜 9/30~10/7)을 한 번 받아 7201을 DB에 넣는다.
        assertThat(client.takeSchedulesAsked()).containsExactly("2026-09-30~2026-10-07");
        // 박스스코어는 선발·교체를 모르는 지난 경기 7001만 선발·교체 채우기(fillLineups)로 한 번 받는다.
        assertThat(client.takeBoxscoresAsked()).containsExactly(7001L);
        assertThat(games.findById(7201L)).isPresent();

        // 같은 날(UTC) 다시 깨어나면 앞 일정을 또 받지 않는다(비시즌에는 하루 1번).
        clock.set(Instant.parse("2026-10-01T05:00:00Z"));
        scheduler.tick();
        assertThat(client.takeSchedulesAsked()).isEmpty();

        // 10/2 00:00Z: DB의 7201이 앞뒤 3일 안에 들어 시즌 중으로 돌아온다.
        // 앞 일정 대신 경기 갱신(마지막으로 끝난 경기 9/26부터 오늘까지)을 받는다.
        clock.set(Instant.parse("2026-10-02T00:00:00Z"));
        assertThat(ingestService.inSeason(clock.instant())).isTrue();
        scheduler.tick();
        assertThat(client.takeSchedulesAsked()).containsExactly("2026-09-26~2026-10-01");
        assertThat(client.takeBoxscoresAsked()).isEmpty();

        // 10/5 02:00Z: 7201이 끝났다. 경기 갱신에서 박스스코어를 받아 기록이 들어온다.
        clock.set(Instant.parse("2026-10-05T02:00:00Z"));
        client.use(data(List.of(final7201()), Map.of(7201L, boxscore(NYM, ATL, true, 673490, "300",
                hittingStat(4, 2), null, List.of(1L), 1, 3))));
        scheduler.tick();
        assertThat(client.takeBoxscoresAsked()).containsExactly(7201L);
        assertThat(games.findById(7201L).orElseThrow().getStatusState()).isEqualTo("final");
        assertThat(appearances.findByPlayersAndGames(List.of(1L), List.of(7201L))).hasSize(1);
    }

    private static MlbScheduleGame game7001() {
        return game(7001, NYM, ATL, "2026-09-26T23:10:00Z", "R");
    }

    private static MlbScheduleGame scheduled7201() {
        return game(7201, NYM, ATL, "2026-10-04T22:08:00Z", "D", "Preview", "S", "Scheduled", null, null);
    }

    private static MlbScheduleGame final7201() {
        return game(7201, NYM, ATL, "2026-10-04T22:08:00Z", "D", "Final", "F", "Final", 1, 3);
    }

    // 정규시즌 7001(김하성 출전)에 postseason 경기들을 더한 데이터
    private static SampleMlbClient.Data data(List<MlbScheduleGame> postseason, Map<Long, MlbBoxscore> boxscores) {
        MlbScheduleGame game7001 = game7001();
        List<MlbScheduleGame> schedule = new ArrayList<>(List.of(game7001));
        schedule.addAll(postseason);
        return SampleMlbClient.Data.empty()
                .withPeople(List.of(hitter(673490, "Ha-Seong Kim", ATL)))
                .withSchedule(schedule)
                .withHittingLogs(List.of(batting(673490, ATL, game7001, 3, 1)))
                .withBoxscores(boxscores);
    }
}
