package com.korleaguer.api;

import com.korleaguer.config.ResponseCaches;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.Batting;
import com.korleaguer.domain.Game;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.domain.Pitching;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.SeasonType;
import com.korleaguer.domain.Team;
import com.korleaguer.domain.TeamRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/records(한국인 메이저리거 역대 기록, 이슈 #177). 샘플 모드: 명단 6명의 시즌별 기록(sample/mlb.json yearByYear)만 있고
 * 명단 밖 선수(korean-mlb.yml)는 샘플에 기록이 없어 순위에 나오지 않는다. 지금을 2026-11-01로 고정한다.
 * 샘플 이정후 안타 38 + 149 + 149 = 336, 김하성 130 + 140 + 94 + 15 = 379(샘플에 2021·2025 줄이 없다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecordsApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private TeamRepository teams;

    @Autowired
    private GameRepository games;

    @Autowired
    private AppearanceRepository appearances;

    @Autowired
    private ResponseCaches responseCaches;

    /** 되돌린 시험 선수로 채운 응답 캐시가 다른 시험에 남지 않게 */
    @AfterEach
    void clearCaches() {
        responseCaches.clearAll("시험: 기록 응답 캐시 비움");
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-01T11:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    @DisplayName("시즌 기록을 더해 한국인 통산 순위와 다음에 넘을 기록을 보여 준다")
    void ranksTheRosterPlayersFromTheirSeasonLines() throws Exception {
        // when // then
        mockMvc.perform(get("/api/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataMode").value("sample"))
                .andExpect(jsonPath("$.season").value(2026))
                .andExpect(jsonPath("$.categories[0].key").value("hits"))
                .andExpect(jsonPath("$.categories[0].label").value("안타"))
                .andExpect(jsonPath("$.categories[0].group").value("HITTING"))
                .andExpect(jsonPath("$.categories[0].leaders[0].nameKo").value("김하성"))
                .andExpect(jsonPath("$.categories[0].leaders[0].value").value(379))
                .andExpect(jsonPath("$.categories[0].leaders[0].firstSeason").value(2022))
                .andExpect(jsonPath("$.categories[0].leaders[1].nameKo").value("이정후"))
                .andExpect(jsonPath("$.categories[0].leaders[1].value").value(336))
                .andExpect(jsonPath("$.categories[0].leaders[1].rank").value(2))
                .andExpect(jsonPath("$.categories[0].leaders[1].active").value(true))
                .andExpect(jsonPath("$.categories[0].leaders[1].photoUrl").value(org.hamcrest.Matchers.matchesPattern("/photos/mlb-808982(\\?v=[0-9a-f]+)?")))
                // 고우석은 국내 리그로 돌아가(명단 returned-ko) 현역이 아니다. 투수라 승·탈삼진 쪽에만 나온다
                .andExpect(jsonPath("$.categories[?(@.key == 'strikeouts')].leaders[0].nameKo").value("고우석"))
                .andExpect(jsonPath("$.categories[?(@.key == 'strikeouts')].leaders[0].active").value(false))
                // 이정후 안타 336 → 김하성 379를 넘으려면 44개, 넘으면 1위
                .andExpect(jsonPath("$.next[?(@.nameKo == '이정후' && @.category == 'hits')].targetNameKo").value("김하성"))
                .andExpect(jsonPath("$.next[?(@.nameKo == '이정후' && @.category == 'hits')].toPass").value(44))
                .andExpect(jsonPath("$.next[?(@.nameKo == '이정후' && @.category == 'hits')].rankAfter").value(1))
                // 샘플에는 현역이 아닌 타자가 없어 넘은 선수가 없다
                .andExpect(jsonPath("$.passed", hasSize(0)))
                .andExpect(jsonPath("$.singleSeason[0].key").value("hits"))
                .andExpect(jsonPath("$.singleSeason[0].rows[0].nameKo").value("이정후"))
                .andExpect(jsonPath("$.singleSeason[0].rows[0].season").value(2025));
    }

    @Test
    @DisplayName("올해 메이저 경기의 승·세이브·탈삼진과 안타·홈런·타점·도루·경기 수가 통산 순위에 더해진다")
    @Transactional
    void thisSeasonGamesAddToTheCareerTotals() throws Exception {
        // given
        // 시즌 줄(yearByYear)이 아직 없는 현역 투수·타자. 통산 값은 올해 경기 기록에서만 나온다
        Team home = team(990001L, "HOM");
        Team away = team(990002L, "AWY");
        Player pitcher = player("시험투수", 990101L, "Pitcher");
        Player hitter = player("시험타자", 990102L, "Outfielder");
        Game first = game(990201L, "2026-06-01T23:00:00Z", home, away);
        Game second = game(990202L, "2026-06-02T23:00:00Z", home, away);
        Game third = game(990203L, "2026-06-03T23:00:00Z", home, away);
        // 투수: 승리 7탈삼진, 세이브 3탈삼진, 타석에만 선 날(투구 기록 없음)
        appear(pitcher, first, home, null, pitching(7, 1, 0));
        appear(pitcher, second, home, null, pitching(3, 0, 1));
        appear(pitcher, third, home, batting(0, 0, 0, 0), null);
        // 타자: 2안타 1홈런 3타점, 1안타 1도루, 대수비로만 나온 날(타격 기록 없음)
        appear(hitter, first, home, batting(2, 1, 3, 0), null);
        appear(hitter, second, home, batting(1, 0, 0, 1), null);
        appear(hitter, third, home, null, null);
        responseCaches.clearAll("시험: 올해 경기 기록을 넣음");

        // when // then
        mockMvc.perform(get("/api/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[?(@.key == 'wins')].leaders[?(@.nameKo == '시험투수')].value").value(1))
                .andExpect(jsonPath("$.categories[?(@.key == 'saves')].leaders[?(@.nameKo == '시험투수')].value").value(1))
                .andExpect(jsonPath("$.categories[?(@.key == 'strikeouts')].leaders[?(@.nameKo == '시험투수')].value").value(10))
                .andExpect(jsonPath("$.categories[?(@.key == 'hits')].leaders[?(@.nameKo == '시험타자')].value").value(3))
                .andExpect(jsonPath("$.categories[?(@.key == 'homeRuns')].leaders[?(@.nameKo == '시험타자')].value").value(1))
                .andExpect(jsonPath("$.categories[?(@.key == 'rbi')].leaders[?(@.nameKo == '시험타자')].value").value(3))
                .andExpect(jsonPath("$.categories[?(@.key == 'stolenBases')].leaders[?(@.nameKo == '시험타자')].value").value(1))
                // 타격 기록 없는 대수비 출전은 경기로 세지 않는다
                .andExpect(jsonPath("$.categories[?(@.key == 'games')].leaders[?(@.nameKo == '시험타자')].value").value(2));
    }

    private Team team(long mlbTeamId, String abbreviation) {
        Team team = new Team(mlbTeamId);
        team.update(abbreviation, abbreviation);
        return teams.save(team);
    }

    private Player player(String nameKo, long mlbId, String position) {
        Player player = new Player(nameKo);
        player.changeMlbId(mlbId);
        player.update(null, position, null, true);
        return players.save(player);
    }

    private Game game(long gamePk, String startUtc, Team home, Team away) {
        Game game = new Game(gamePk);
        game.update(2026, SeasonType.REGULAR.stored(), Instant.parse(startUtc), home, away, 5, 3, "final", null);
        return games.save(game);
    }

    private void appear(Player player, Game game, Team team, Batting batting, Pitching pitching) {
        Appearance appearance = new Appearance(player, game);
        appearance.update(team, batting, pitching);
        appearances.save(appearance);
    }

    private static Pitching pitching(int strikeouts, int wins, int saves) {
        return new Pitching(3, 0, 0, 0, 0, strikeouts, 0, 15, wins, 0, saves);
    }

    private static Batting batting(int hits, int homeRuns, int rbi, int stolenBases) {
        return new Batting(4, 0, hits, 0, 0, homeRuns, rbi, 0, 0, 0, stolenBases, 0, 4, hits + 3 * homeRuns);
    }
}
