package com.korleaguer.api;

import com.korleaguer.config.ResponseCaches;
import com.korleaguer.domain.AffiliationRepository;
import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.domain.NewsDestination;
import com.korleaguer.domain.NewsDestinationRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerLeaguePositionRepository;
import com.korleaguer.domain.PlayerMoveRepository;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.PlayerSeasonMetricRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 성적표의 실패해도 되는 조회(성적표 재료, 선수 한 명의 이동 기록, MLB 안 위치, 겨울 기사, 거론 구단)와 포스팅 선수의
 * 실패해도 되는 조회(기사, 거론 구단, 이슈 #281)가 DB에서 실패해도
 * 홈(GET /api/home)은 200이고 그 부분만 빠진다(이슈 #179 리뷰). 홈 조회 트랜잭션 안에서 조회가 실패하면 Hibernate가 그
 * 트랜잭션을 rollback-only로 표시해서, 예외를 삼켜도 커밋 때 UnexpectedRollbackException이 나고 홈 전체가 500이 됐다.
 * <p>
 * 실패는 저장소 spy가 없는 표를 읽게 해서 만든다. 실제 조회 실패처럼 Hibernate가 그때의 트랜잭션을 표시한다. Mockito의
 * doThrow만 쓰면 예외가 저장소·Hibernate를 거치지 않아 트랜잭션이 표시되지 않으므로 이 버그가 재현되지 않는다.
 * 트랜잭션을 거는 시험(@Transactional)이면 홈 조회가 시험 트랜잭션에 참여해 역시 재현되지 않는다.
 * <p>
 * 샘플 데이터, 지금 2026-11-01(비시즌). 이 시험 DB에만 김하성을 FA로 바꾸고, 이정후·김하성·노시환 기사와 김하성·노시환
 * 기사에서 거론된 구단(PHI·PIT)을 넣는다(기사 내용은 지어낸 값).
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:season-report-read-failures;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SeasonReportReadFailuresTest {

    private static final String LEE_WINTER_URL = "https://www.mydaily.co.kr/lee-winter";
    private static final String KIM_LINKED_URL = "https://www.mydaily.co.kr/kim-phi";
    private static final String ROH_LINKED_URL = "https://www.mydaily.co.kr/roh-pit";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ResponseCaches responseCaches;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private AffiliationRepository affiliations;

    @MockitoSpyBean
    private PlayerSeasonMetricRepository metrics;

    @MockitoSpyBean
    private PlayerMoveRepository moves;

    @MockitoSpyBean
    private PlayerLeaguePositionRepository leaguePositions;

    @MockitoSpyBean
    private NewsArticleRepository news;

    @MockitoSpyBean
    private NewsDestinationRepository destinations;

    private long leeId;
    private long kimId;

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-01T11:00:00Z"), ZoneOffset.UTC);
        }
    }

    @BeforeEach
    void winterWithAFreeAgent() {
        transactions.executeWithoutResult(status -> {
            Player kim = players.findByNameKo("김하성").orElseThrow();
            affiliations.findByPlayer(kim).orElseThrow().end(LocalDate.of(2026, 10, 30));
            if (!news.existsBySubjectAndUrlHash("이정후", "lee-winter")) {
                news.save(new NewsArticle("이정후", LEE_WINTER_URL, "lee-winter", "이정후 다음 시즌 준비", "마이데일리",
                        Instant.parse("2026-10-20T00:00:00Z"), Instant.parse("2026-10-20T00:10:00Z")));
            }
            if (!news.existsBySubjectAndUrlHash("김하성", "kim-phi")) {
                NewsArticle linked = news.save(new NewsArticle("김하성", KIM_LINKED_URL, "kim-phi", "김하성 필라델피아행 거론",
                        "마이데일리", Instant.parse("2026-10-30T00:00:00Z"), Instant.parse("2026-10-30T00:10:00Z")));
                destinations.save(new NewsDestination(linked.getId(), "PHI", NewsDestination.Stance.LINKED));
            }
            if (!news.existsBySubjectAndUrlHash("노시환", "roh-pit")) {
                NewsArticle linked = news.save(new NewsArticle("노시환", ROH_LINKED_URL, "roh-pit", "노시환 MLB 피츠버그행 거론",
                        "마이데일리", Instant.parse("2026-10-30T00:00:00Z"), Instant.parse("2026-10-30T00:10:00Z")));
                destinations.save(new NewsDestination(linked.getId(), "PIT", NewsDestination.Stance.LINKED));
            }
        });
        leeId = players.findByNameKo("이정후").orElseThrow().getId();
        kimId = players.findByNameKo("김하성").orElseThrow().getId();
        // 앱 시작 때 채운 응답(실패를 넣기 전)을 비운다
        responseCaches.clearAll("시험 시작");
    }

    @Test
    @DisplayName("조회가 모두 되면 성적표와 포스팅 카드에 기사·거론 구단이 모두 나온다")
    void everyPartIsThereWhenNothingFails() throws Exception {
        // when // then
        mockMvc.perform(get("/api/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offseason.report[*].playerId", hasItem((int) leeId)))
                .andExpect(jsonPath(report(leeId) + ".saber.wrcPlus").value(contains(101.8)))
                .andExpect(jsonPath(report(leeId) + ".news[*].url").value(contains(LEE_WINTER_URL)))
                .andExpect(jsonPath(report(kimId) + ".news[*].url").value(contains(KIM_LINKED_URL)))
                .andExpect(jsonPath(report(kimId) + ".linkedTeams.teams[*].abbreviation").value(contains("PHI")))
                .andExpect(jsonPath("$.posting.players[*].nameKo").value(contains("노시환")))
                .andExpect(jsonPath("$.posting.players[0].news[*].url").value(contains(ROH_LINKED_URL)))
                .andExpect(jsonPath("$.posting.players[0].linkedTeams.teams[*].abbreviation").value(hasItem("PIT")));
        mockMvc.perform(get("/api/players/" + leeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.news[*].url").value(contains(LEE_WINTER_URL)));
    }

    @Test
    @DisplayName("성적표용 시즌 지표를 못 읽으면 성적표만 비고 시즌 성적 표는 그대로 나온다")
    void seasonRecordsFailSoOnlyTheReportIsEmpty() throws Exception {
        // given
        doAnswer(failsInDatabase()).when(metrics).findSeasons(any(), anyInt(), anyInt());

        // when // then
        mockMvc.perform(get("/api/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offseason.playerIds", hasSize(6)))
                .andExpect(jsonPath("$.offseason.report", hasSize(0)))
                // 홈의 나머지(시즌 성적 표)는 그대로다
                .andExpect(jsonPath("$.hitters", hasSize(5)));
    }

    @Test
    @DisplayName("한 선수의 이동 기록을 못 읽으면 성적표에서 그 선수만 빠진다")
    void onePlayersMovesFailSoOnlyHeIsLeftOut() throws Exception {
        // given
        doAnswer(failsInDatabase()).when(moves).findNewestFirst(leeId);

        // when // then
        mockMvc.perform(get("/api/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offseason.report[*].playerId", not(hasItem((int) leeId))))
                .andExpect(jsonPath("$.offseason.report[*].playerId", hasItem((int) kimId)))
                .andExpect(jsonPath(report(kimId) + ".linkedTeams.teams[*].abbreviation").value(contains("PHI")));
    }

    @Test
    @DisplayName("MLB 백분위를 못 읽으면 성적표의 세부 기록만 비고 기사는 그대로 나온다")
    void leaguePositionsFailSoOnlyTheSaberIsNull() throws Exception {
        // given
        doAnswer(failsInDatabase()).when(leaguePositions).findSeason(any(), anyInt());

        // when // then
        mockMvc.perform(get("/api/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offseason.report[*].playerId", hasItem((int) leeId)))
                .andExpect(jsonPath("$.offseason.report[*].saber", everyItem(nullValue())))
                .andExpect(jsonPath(report(leeId) + ".news[*].url").value(contains(LEE_WINTER_URL)));
    }

    @Test
    @DisplayName("한 선수의 겨울 기사를 못 읽으면 그 선수의 기사만 빈다")
    void winterNewsFailsSoOnlyThatPlayersNewsIsEmpty() throws Exception {
        // given
        doAnswer(failsInDatabase()).when(news).findLatest(eq("이정후"), any());

        // when // then
        mockMvc.perform(get("/api/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(report(leeId) + ".saber.wrcPlus").value(contains(101.8)))
                .andExpect(jsonPath(report(leeId) + ".news[*]", empty()))
                .andExpect(jsonPath(report(kimId) + ".news[*].url").value(contains(KIM_LINKED_URL)));
        // 선수 화면의 기사도 같은 조회(SeasonReportFinder.news)다
        mockMvc.perform(get("/api/players/" + leeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saber.wrcPlus").value(101.8))
                .andExpect(jsonPath("$.news", hasSize(0)));
    }

    @Test
    @DisplayName("거론 구단을 못 읽으면 거론 구단만 비고 기사는 그대로 나온다")
    void linkedTeamsFailSoOnlyTheyAreNull() throws Exception {
        // given
        doAnswer(failsInDatabase()).when(destinations).findMentions(eq("김하성"), any(), any());

        // when // then
        mockMvc.perform(get("/api/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(report(kimId) + ".linkedTeams").value(contains(nullValue())))
                .andExpect(jsonPath(report(kimId) + ".news[*].url").value(contains(KIM_LINKED_URL)));
    }

    @Test
    @DisplayName("포스팅 선수의 기사를 못 읽으면 그 기사만 비고 포스팅 카드와 홈은 그대로 나온다")
    void postingNewsFailsSoOnlyTheArticlesAreEmpty() throws Exception {
        // given
        doAnswer(failsInDatabase()).when(news).findLatest(eq("노시환"), argThat(page -> page.getPageSize() < 300));

        // when // then
        mockMvc.perform(get("/api/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posting.players[*].nameKo").value(contains("노시환")))
                .andExpect(jsonPath("$.posting.players[0].status").value("UPCOMING"))
                .andExpect(jsonPath("$.posting.players[0].news", hasSize(0)))
                .andExpect(jsonPath("$.posting.players[0].linkedTeams.teams[*].abbreviation").value(hasItem("PIT")))
                .andExpect(jsonPath(report(leeId) + ".news[*].url").value(contains(LEE_WINTER_URL)))
                .andExpect(jsonPath("$.hitters", hasSize(5)));
    }

    @Test
    @DisplayName("포스팅 선수의 거론 구단을 못 읽으면 거론 구단만 비고 기사는 그대로 나온다")
    void postingLinkedTeamsFailSoOnlyTheyAreEmpty() throws Exception {
        // given
        doAnswer(failsInDatabase()).when(destinations).findMentions(eq("노시환"), any(), any());

        // when // then
        mockMvc.perform(get("/api/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posting.players[0].linkedTeams.teams", hasSize(0)))
                .andExpect(jsonPath("$.posting.players[0].news[*].url").value(contains(ROH_LINKED_URL)))
                .andExpect(jsonPath(report(kimId) + ".linkedTeams.teams[*].abbreviation").value(contains("PHI")));
    }

    /** 홈 응답에서 그 선수의 성적표 줄(JsonPath 거르기라 결과는 목록이다) */
    private static String report(long playerId) {
        return "$.offseason.report[?(@.playerId == " + playerId + ")]";
    }

    /** 저장소 조회가 DB에서 실패한 것처럼: 없는 표를 읽는다(Hibernate가 그때의 트랜잭션을 rollback-only로 표시하고 예외를 던진다) */
    private Answer<Object> failsInDatabase() {
        return invocation -> entityManager.createNativeQuery("select * from no_such_table").getResultList();
    }
}
