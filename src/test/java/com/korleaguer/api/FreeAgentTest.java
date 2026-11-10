package com.korleaguer.api;

import com.korleaguer.client.MlbPerson;
import com.korleaguer.client.MlbScheduleGame;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.client.SampleMlbClient;
import com.korleaguer.domain.AffiliationRepository;
import com.korleaguer.ingest.IngestService;
import com.korleaguer.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.korleaguer.client.MlbFixtures.batting;
import static com.korleaguer.client.MlbFixtures.game;
import static com.korleaguer.client.MlbFixtures.hitter;
import static com.korleaguer.client.MlbFixtures.team;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * MLB 구단 소속이 없어진 선수(FA 등)는 마지막 소속으로 홈 시즌 성적에 남고, 그 구단의 경기는 결장으로 보지 않는다.
 * <ul>
 *   <li>이정후(SF): 계속 SF</li>
 *   <li>김하성(ATL): 9/28에는 ATL, 11/10에 FA가 된다. ATL 7002(9/26)에는 뛰지 않았다.</li>
 *   <li>송성문: 처음 받을 때부터 FA. 시즌 기록은 SD 8001(9/24)뿐이고 SD 8002(9/26)에는 뛰지 않았다.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:free-agent-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "roster.baseball[0].name-ko=이정후",
        "roster.baseball[0].mlb-id=808982",
        "roster.baseball[1].name-ko=김하성",
        "roster.baseball[1].mlb-id=673490",
        "roster.baseball[2].name-ko=송성문",
        "roster.baseball[2].mlb-id=823550"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FreeAgentTest {

    private static final MlbTeam SF = team(137, "San Francisco Giants");
    private static final MlbTeam COL = team(115, "Colorado Rockies");
    private static final MlbTeam ATL = team(144, "Atlanta Braves");
    private static final MlbTeam NYM = team(121, "New York Mets");
    private static final MlbTeam SD = team(135, "San Diego Padres");
    private static final MlbTeam LAD = team(119, "Los Angeles Dodgers");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IngestService ingestService;

    @Autowired
    private FreeAgencyClient client;

    @Autowired
    private MutableClock clock;

    @Autowired
    private AffiliationRepository affiliations;

    /** freeAgents에 넣은 선수는 지금 소속(currentTeam) 없이 답하는 샘플 클라이언트 */
    static class FreeAgencyClient extends SampleMlbClient {

        final Set<Long> freeAgents = ConcurrentHashMap.newKeySet();

        FreeAgencyClient(Data data) {
            super(data);
        }

        @Override
        public List<MlbPerson> getPeople(Collection<Long> personIds) {
            return super.getPeople(personIds).stream()
                    .map(p -> freeAgents.contains(p.id()) ? hitter(p.id(), p.fullName(), null) : p)
                    .toList();
        }
    }

    @TestConfiguration
    static class Scenario {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-09-28T12:00:00Z"));
        }

        @Bean
        @Primary
        FreeAgencyClient freeAgencyClient() {
            List<MlbScheduleGame> games = List.of(
                    game(9001, COL, SF, "2026-09-26T20:05:00Z", "R"),   // 이정후 출전: 한국 9월 27일
                    game(7001, NYM, ATL, "2026-09-20T23:10:00Z", "R"),  // 김하성 출전
                    game(7002, NYM, ATL, "2026-09-26T18:05:00Z", "R"),  // 김하성 결장: 한국 9월 27일
                    game(8001, LAD, SD, "2026-09-24T02:10:00Z", "R"),   // 송성문 출전
                    game(8002, LAD, SD, "2026-09-26T22:10:00Z", "R"));  // 송성문 안 뜀: 한국 9월 27일
            FreeAgencyClient client = new FreeAgencyClient(SampleMlbClient.Data.empty()
                    .withPeople(List.of(hitter(808982, "Jung Hoo Lee", SF), hitter(673490, "Ha-Seong Kim", ATL),
                            hitter(823550, "Sung-Mun Song", SD)))
                    .withSchedule(games)
                    .withHittingLogs(List.of(
                            batting(808982, SF, games.get(0), 4, 1),
                            batting(673490, ATL, games.get(1), 4, 2),
                            batting(823550, SD, games.get(3), 3, 1))));
            client.freeAgents.add(823550L);
            return client;
        }
    }

    @Test
    void freeAgentKeepsLastTeamWithoutAbsences() throws Exception {
        // 9/28: 처음부터 FA인 송성문도 시즌 기록의 마지막 팀(SD)으로 홈 시즌 성적에 나온다(타율순: 김하성, 송성문, 이정후).
        // 마지막 경기일(한국 9/27) 줄: 이정후 출전, 김하성 결장(ATL 소속). 송성문은 SD 경기를 결장으로 보지 않는다.
        mockMvc.perform(get("/api/home"))
                .andExpect(jsonPath("$.hitters", hasSize(3)))
                .andExpect(jsonPath("$.hitters[1].nameKo").value("송성문"))
                .andExpect(jsonPath("$.hitters[1].teamAbbr").value("SD"))
                .andExpect(jsonPath("$.recent.dateKst").value("2026-09-27"))
                .andExpect(jsonPath("$.recent.lines", hasSize(2)));
        mockMvc.perform(get("/api/players/2"))
                .andExpect(jsonPath("$.games", hasSize(2)))
                .andExpect(jsonPath("$.games[0].gameId").value(7002))
                .andExpect(jsonPath("$.games[0].played").value(false));

        // 11/10: 김하성이 FA가 됐다.
        client.freeAgents.add(673490L);
        clock.set(Instant.parse("2026-11-10T03:00:00Z"));
        ingestService.catchUp(false);

        // 홈 시즌 성적에 마지막 소속(ATL)으로 남고, ATL 경기는 더 이상 결장 줄을 만들지 않는다.
        mockMvc.perform(get("/api/home"))
                .andExpect(jsonPath("$.hitters", hasSize(3)))
                .andExpect(jsonPath("$.hitters[0].nameKo").value("김하성"))
                .andExpect(jsonPath("$.hitters[0].teamAbbr").value("ATL"))
                .andExpect(jsonPath("$.recent.dateKst").value("2026-09-27"))
                .andExpect(jsonPath("$.recent.lines", hasSize(1)))
                .andExpect(jsonPath("$.recent.lines[0].nameKo").value("이정후"));
        mockMvc.perform(get("/api/players"))
                .andExpect(jsonPath("$[1].teamAbbr").value("ATL"))
                .andExpect(jsonPath("$[1].level").doesNotExist());
        mockMvc.perform(get("/api/players/2"))
                .andExpect(jsonPath("$.player.teamAbbr").value("ATL"))
                .andExpect(jsonPath("$.games", hasSize(1)))
                .andExpect(jsonPath("$.games[0].gameId").value(7001));

        // 경기 갱신은 지금 소속이 있는 선수의 팀만 본다(FA의 마지막 구단은 빠진다).
        assertThat(affiliations.findAllCurrent()).extracting(a -> a.getPlayer().getNameKo())
                .containsExactly("이정후");
    }
}
