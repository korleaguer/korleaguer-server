package com.korleaguer.api.season;

import com.korleaguer.api.game.GameBatting;
import com.korleaguer.api.game.GamePitching;

import com.korleaguer.api.game.GameLineAssembler;
import com.korleaguer.api.game.MinorGameLine;
import com.korleaguer.api.player.MinorTeamNames;

import com.korleaguer.config.MinorTeamProperties;
import com.korleaguer.domain.Batting;
import com.korleaguer.domain.MinorGameLog;
import com.korleaguer.domain.MinorGameSchedule;
import com.korleaguer.domain.Pitching;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.Role;
import com.korleaguer.support.GameSummary;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 선수 화면의 마이너 경기 줄(이슈 #145): 순서, 팀 이름, 일정(시작 시각·점수), 메이저와 같은 한 줄 기록 */
public class MinorGameLinesTest {

    private static final Player KIM = new Player("김혜성");
    private static final MinorTeamProperties TEAMS = readTeams();
    private static final MinorTeamNames NAMES = new MinorTeamNames(TEAMS);
    private static final long OKLAHOMA_CITY = 238L;
    private static final long ALBUQUERQUE = 342L;
    private static final long DSL_LAD_BAUTISTA = 611L; // 루키 도미니칸 서머 리그: 표에 없다
    private static final Batting TWO_HITS = BattingLine.atBats(4).runs(1).hits(2).doubles(1).homeRuns(1)
            .rbi(3).walks(1).strikeouts(1).build();
    private static final Pitching RELIEF_WIN = new Pitching(4, 1, 1, 0, 1, 3, 0, 22, 1, 0, 0);

    @Test
    void newestGameFirstAndDoubleHeaderByStartTime() {
        MinorGameLog first = game(1, LocalDate.of(2026, 9, 18));
        MinorGameLog early = game(2, LocalDate.of(2026, 9, 20));
        early.updateSchedule(finalGame("2026-09-20T17:05:00Z", 3, 1));
        MinorGameLog late = game(3, LocalDate.of(2026, 9, 20));
        late.updateSchedule(finalGame("2026-09-20T23:05:00Z", 2, 5));

        List<MinorGameLine> lines = MinorRecords.gameLines(Role.HITTER, List.of(first, late, early), NAMES);

        assertThat(lines).extracting(MinorGameLine::gamePk).containsExactly(3L, 2L, 1L);
    }

    @Test
    void scheduleFillsStartTimeScoresAndResult() {
        MinorGameLog won = game(1, LocalDate.of(2026, 9, 20));
        won.updateSchedule(finalGame("2026-09-21T00:05:00Z", 7, 4));
        MinorGameLog tied = game(2, LocalDate.of(2026, 9, 19));
        tied.updateSchedule(finalGame("2026-09-20T00:05:00Z", 3, 3));

        List<MinorGameLine> lines = MinorRecords.gameLines(Role.HITTER, List.of(won, tied), NAMES);

        MinorGameLine line = lines.getFirst();
        assertThat(line.startTimeKst()).isEqualTo(OffsetDateTime.parse("2026-09-21T09:05:00+09:00"));
        assertThat(line.teamScore()).isEqualTo(7);
        assertThat(line.opponentScore()).isEqualTo(4);
        assertThat(line.result()).isEqualTo("W");
        assertThat(lines.get(1).result()).isEqualTo("T");
    }

    @Test
    void withoutScheduleTheTimeIsGuessedAndResultComesFromTheGameLog() {
        // 일정을 아직 받지 못한 경기: 공식 날짜 다음 날 08:00(한국), 점수는 null, 승패는 경기별 기록(win=false)
        MinorGameLog game = game(1, LocalDate.of(2026, 9, 20));

        MinorGameLine line = MinorRecords.gameLines(Role.HITTER, List.of(game), NAMES).getFirst();

        assertThat(line.startTimeKst()).isEqualTo(OffsetDateTime.parse("2026-09-21T08:00:00+09:00"));
        assertThat(line.teamScore()).isNull();
        assertThat(line.opponentScore()).isNull();
        assertThat(line.result()).isEqualTo("L");
    }

    @Test
    void gameNotStartedOrInProgressHasNoResult() {
        MinorGameLog postponed = game(1, LocalDate.of(2026, 9, 20));
        postponed.updateSchedule(new MinorGameSchedule(Instant.parse("2026-09-20T23:05:00Z"), "postponed", 0, 0,
                null, null));
        MinorGameLog live = game(2, LocalDate.of(2026, 9, 19));
        live.updateSchedule(new MinorGameSchedule(Instant.parse("2026-09-19T23:05:00Z"), "in_progress", 2, 1,
                null, null));

        List<MinorGameLine> lines = MinorRecords.gameLines(Role.HITTER, List.of(postponed, live), NAMES);

        assertThat(lines.get(0).teamScore()).isNull();
        assertThat(lines.get(0).result()).isNull();
        assertThat(lines.get(1).teamScore()).isEqualTo(2);
        assertThat(lines.get(1).result()).isNull();
    }

    @Test
    void unknownStateFallsBackToTheGameLogButSuspendedHasNoResult() {
        // 일정을 물었지만 응답에 없던 경기: 시각은 어림, 승패는 경기별 기록(win=false)
        MinorGameLog notFound = game(1, LocalDate.of(2026, 9, 20));
        notFound.updateSchedule(MinorGameSchedule.notFound());
        // 중단된 경기: 경기별 기록에 승패가 있어도 아직 승패가 나지 않았다
        MinorGameLog suspended = game(2, LocalDate.of(2026, 9, 19));
        suspended.updateSchedule(new MinorGameSchedule(Instant.parse("2026-09-19T23:05:00Z"), "suspended", 1, 1,
                null, null));

        List<MinorGameLine> lines = MinorRecords.gameLines(Role.HITTER, List.of(notFound, suspended), NAMES);

        assertThat(lines.get(0).startTimeKst()).isEqualTo(OffsetDateTime.parse("2026-09-21T08:00:00+09:00"));
        assertThat(lines.get(0).teamScore()).isNull();
        assertThat(lines.get(0).teamKo()).isEqualTo("오클라호마시티");
        assertThat(lines.get(0).result()).isEqualTo("L");
        assertThat(lines.get(1).result()).isNull();
    }

    @Test
    void koreanCityNameThenEnglishPlaceThenEnglishName() {
        MinorGameLog known = game(1, LocalDate.of(2026, 9, 20));
        MinorGameLog placeOnly = game(2, LocalDate.of(2026, 5, 2));
        placeOnly.updateTeamIds(DSL_LAD_BAUTISTA, 999_999L);
        placeOnly.updateSchedule(new MinorGameSchedule(Instant.parse("2026-05-02T23:05:00Z"), "final", 1, 2,
                "DSL LAD Bautista", null));

        List<MinorGameLine> lines = MinorRecords.gameLines(Role.HITTER, List.of(known, placeOnly), NAMES);

        assertThat(lines.get(0).teamId()).isEqualTo(OKLAHOMA_CITY);
        assertThat(lines.get(0).teamKo()).isEqualTo("오클라호마시티");
        assertThat(lines.get(0).opponentId()).isEqualTo(ALBUQUERQUE);
        assertThat(lines.get(0).opponentKo()).isEqualTo("앨버커키");
        assertThat(lines.get(1).teamKo()).isEqualTo("DSL LAD Bautista");   // 표에 없는 팀: 영어 지명
        assertThat(lines.get(1).opponentKo()).isEqualTo("Albuquerque Isotopes"); // 지명도 모르면 영어 팀 이름
    }

    @Test
    void levelNames() {
        MinorGameLog game = game(1, LocalDate.of(2026, 9, 20));
        game.updateGame(game.getGameDate(), "A+", game.getTeamName(), game.getOpponentName(), true, true);

        MinorGameLine line = MinorRecords.gameLines(Role.HITTER, List.of(game), NAMES).getFirst();

        assertThat(line.level()).isEqualTo("A+");
        assertThat(line.levelKo()).isEqualTo("하이싱글A");
        assertThat(line.home()).isTrue();
    }

    @Test
    void summaryAndNumbersFollowTheMajorLeagueRules() {
        MinorGameLog game = game(1, LocalDate.of(2026, 9, 20));
        game.updateBatting(TWO_HITS);
        game.updatePitching(RELIEF_WIN, 0, 0);

        MinorGameLine hitter = MinorRecords.gameLines(Role.HITTER, List.of(game), NAMES).getFirst();
        MinorGameLine pitcher = MinorRecords.gameLines(Role.PITCHER, List.of(game), NAMES).getFirst();

        // 메이저 줄과 같은 문장(GameSummary)·같은 숫자 모양(GameLineAssembler)
        assertThat(hitter.summary()).isEqualTo("4타수 2안타(2루타 1) 1홈런 3타점 1볼넷");
        assertThat(hitter.summary()).isEqualTo(GameSummary.played(Role.HITTER, TWO_HITS, null));
        assertThat(hitter.batting()).isEqualTo(GameBatting.of(TWO_HITS));
        assertThat(hitter.batting().plateAppearances()).isEqualTo(5);
        assertThat(pitcher.summary()).isEqualTo("1⅓이닝 1피안타 1실점(0자책) 3탈삼진 1볼넷 · 승");
        assertThat(pitcher.summary()).isEqualTo(GameSummary.played(Role.PITCHER, null, RELIEF_WIN));
        assertThat(pitcher.pitching()).isEqualTo(GamePitching.of(RELIEF_WIN));
        assertThat(pitcher.pitching().win()).isTrue();
    }

    @Test
    void noMinorGamesIsAnEmptyList() {
        assertThat(MinorRecords.gameLines(Role.HITTER, List.of(), NAMES)).isEmpty();
    }

    @Test
    void teamListHasEveryTripleAAndDoubleATeam() {
        Map<String, Long> byLevel = TEAMS.teams().stream()
                .collect(Collectors.groupingBy(MinorTeamProperties.Team::level, Collectors.counting()));
        // 트리플A·더블A는 30팀 모두라 수를 지킨다. 하위 단계는 새 팀이 보이면 더해 가므로(yml 머리 설명·README) 수는 정하지
        // 않고 아는 단계 약어인지만 본다(이슈 #191 리뷰)
        assertThat(byLevel).containsEntry("AAA", 30L).containsEntry("AA", 30L);
        assertThat(byLevel.keySet()).allSatisfy(level -> assertThat(MinorTeamNames.LEVEL_KO).containsKey(level));
        assertThat(TEAMS.teams()).extracting(MinorTeamProperties.Team::id).doesNotHaveDuplicates();
        assertThat(TEAMS.teams()).allSatisfy(team -> assertThat(team.nameKo()).isNotBlank());

        assertThat(NAMES.name(238L, null, null)).isEqualTo("오클라호마시티");
        assertThat(NAMES.name(342L, null, null)).isEqualTo("앨버커키");
        assertThat(NAMES.name(4904L, null, null)).isEqualTo("엘패소");
        assertThat(NAMES.name(552L, null, null)).isEqualTo("시러큐스");
        assertThat(NAMES.name(512L, null, null)).isEqualTo("톨레도");
        assertThat(NAMES.name(106L, null, null)).isEqualTo("이리");
        assertThat(NAMES.name(1960L, null, null)).isEqualTo("세인트폴");
        assertThat(NAMES.name(556L, null, null)).isEqualTo("내슈빌");
    }

    @Test
    void realLowerLevelGamesAreKoreanOnBothSides() {
        // 명단 6명의 2024~2026 하위 단계 경기 23경기(MLB 마이너 gameLog, 11-19)의 우리 팀·상대 팀. 하위 단계는 리그 단위로
        // 넣어서 두 팀 모두 한국어다(우리 팀만 넣으면 '주피터 vs Fort Myers'처럼 한 줄에 섞인다, 이슈 #191 리뷰)
        long[][] games = {
                {464, 474}, {464, 471},             // 김하성 2026 FCL 브레이브스 vs FCL 트윈스·FCL 레드삭스
                {3390, 566}, {3390, 587}, {3390, 424}, // 배지환 2024·2025 브레이든턴 vs 클리어워터·탬파·더니든
                {467, 1370},                         // 고우석 2025 FCL 말린스 vs FCL 카디널스
                {479, 509}, {479, 507}, {479, 450},  // 고우석 2025 주피터 vs 포트마이어스·세인트루시·데이토나
                {554, 499}, {554, 584},              // 고우석 2025 벌로이트 vs 랜싱·포트웨인
                {582, 499}, {582, 456}, {582, 584}}; // 고우석 2025 웨스트미시간 vs 랜싱·그레이트레이크스·포트웨인
        for (long[] game : games) {
            assertThat(NAMES.name(game[0], null, null)).as("team %d", game[0]).isNotNull();
            assertThat(NAMES.name(game[1], null, null)).as("opponent %d", game[1]).isNotNull();
        }
        assertThat(NAMES.name(509L, "Fort Myers", "Fort Myers Mighty Mussels")).isEqualTo("포트마이어스");
        assertThat(NAMES.name(1370L, "FCL Cardinals", "FCL Cardinals")).isEqualTo("FCL 카디널스");
        assertThat(NAMES.moveName(456L, "Great Lakes Loons")).isEqualTo("그레이트레이크스(하이싱글A)");
    }

    @Test
    void rehabTeamsOfMajorLeaguersAreInTheList() {
        // 지금(11-19) 메이저에 있는 명단 선수 구단의 하위 단계 팀(재활 경기를 하는 곳). 아직 기록에 안 나온 리그지만 미리 리그째
        // 넣어 두어 재활 경기가 생겨도 표를 고치지 않는다(이슈 #191 리뷰): 이정후 샌프란시스코, 김혜성 다저스, 송성문 샌디에이고
        assertThat(NAMES.moveName(408L, "ACL Giants")).isEqualTo("ACL 자이언츠(루키)");
        assertThat(NAMES.moveName(476L, "San Jose Giants")).isEqualTo("새너제이(싱글A)");
        assertThat(NAMES.moveName(461L, "Eugene Emeralds")).isEqualTo("유진(하이싱글A)");
        assertThat(NAMES.moveName(5427L, "ACL Dodgers")).isEqualTo("ACL 다저스(루키)");
        assertThat(NAMES.moveName(6482L, "Ontario Tower Buzzers")).isEqualTo("온타리오(싱글A)");
        assertThat(NAMES.moveName(456L, "Great Lakes Loons")).isEqualTo("그레이트레이크스(하이싱글A)");
        assertThat(NAMES.moveName(410L, "ACL Padres")).isEqualTo("ACL 파드리스(루키)");
        assertThat(NAMES.moveName(103L, "Lake Elsinore Storm")).isEqualTo("레이크엘시노어(싱글A)");
        assertThat(NAMES.moveName(584L, "Fort Wayne TinCaps")).isEqualTo("포트웨인(하이싱글A)");
        // 경기 줄의 상대도 같은 리그 팀이라 한국어다(예: 새너제이 vs 스톡턴)
        assertThat(NAMES.name(524L, "Stockton", "Stockton Ports")).isEqualTo("스톡턴");
    }

    @Test
    void duplicateTeamFailsAtStartup() {
        MinorTeamProperties twice = new MinorTeamProperties(List.of(
                new MinorTeamProperties.Team(238L, "AAA", "오클라호마시티", null),
                new MinorTeamProperties.Team(238L, "AAA", "오클라호마", null)));

        assertThatThrownBy(() -> new MinorTeamNames(twice)).isInstanceOf(IllegalStateException.class);
    }

    /** 김혜성의 오클라호마시티(238) 홈 경기, 상대 앨버커키(342). 진 경기, 일정은 아직 없다 */
    private static MinorGameLog game(long gamePk, LocalDate date) {
        MinorGameLog game = new MinorGameLog(KIM, 2026, gamePk);
        game.updateGame(date, "AAA", "Oklahoma City Comets", "Albuquerque Isotopes", true, false);
        game.updateTeamIds(OKLAHOMA_CITY, ALBUQUERQUE);
        return game;
    }

    private static MinorGameSchedule finalGame(String startUtc, int teamScore, int opponentScore) {
        return new MinorGameSchedule(Instant.parse(startUtc), "final", teamScore, opponentScore, "Oklahoma City",
                "Albuquerque");
    }

    /** 실제 roster/minor-teams.yml(이동 기록 팀 이름 시험도 같이 쓴다) */
    public static MinorTeamProperties readTeams() {
        try {
            var sources = new YamlPropertySourceLoader().load("minor-teams",
                    new ClassPathResource("roster/minor-teams.yml"));
            return new Binder(ConfigurationPropertySources.from(sources))
                    .bind("minor-league", MinorTeamProperties.class)
                    .get();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
