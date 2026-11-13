package com.korleaguer.api;

import com.korleaguer.api.SeasonReports.GameDay;
import com.korleaguer.api.SeasonReports.Input;
import com.korleaguer.api.SeasonReports.Move;
import com.korleaguer.api.SeasonVerdict.Line;
import com.korleaguer.domain.Batting;
import com.korleaguer.domain.Level;
import com.korleaguer.domain.Pitching;
import com.korleaguer.domain.Role;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시즌 성적표 글: 2026 시즌 우리 선수 6명과 비슷하게 만든 재료로 판정·한 줄 요약·이유·자랑거리·겨울 이동을 확인한다.
 * (경기별 기록은 실제 기록이 아니라 합계가 맞게 지어낸 값이다.)
 */
class SeasonReportsTest {

    private static final LocalDate OPENING = LocalDate.of(2026, 3, 25);
    private static final LocalDate LAST_DAY = LocalDate.of(2026, 9, 27);

    @Test
    void leeJungHoo() {
        // 143경기: 6월까지 76경기 304타수 96안타(.316), 7월부터 67경기 269타수 62안타(.230)
        List<GameDay> games = new ArrayList<>();
        int[] firstHits = spread(96, 76);
        int[] secondHits = spread(62, 67);
        for (int i = 0; i < 143; i++) {
            LocalDate day = OPENING.plusDays(1 + i * 184L / 143);
            boolean first = i < 76;
            int atBats = !first && i == 76 ? 5 : 4;
            games.add(hitting(day, atBats, first ? firstHits[i] : secondHits[i - 76], 0, 0, 0));
        }
        assertThat(games.get(75).date()).isBefore(LocalDate.of(2026, 7, 1));
        assertThat(games.get(76).date()).isAfter(LocalDate.of(2026, 6, 30));

        // 짧은 부상자 명단 두 번보다 전·후반 흐름을 쓴다. 정규시즌 이틀 뒤 해제는 겨울 이동이 아니다
        List<Move> moves = List.of(
                move("2026-05-22", "SC", "San Francisco Giants placed RF Jung Hoo Lee on the 10-day injured list "
                        + "retroactive to May 19, 2026. Mid-back strain."),
                move("2026-09-21", "SC", "San Francisco Giants placed RF Jung Hoo Lee on the 10-day injured list. "
                        + "Groin contusion."),
                move("2026-09-29", "SC", "San Francisco Giants activated RF Jung Hoo Lee from the 10-day injured list."));
        SeasonReport report = build(hitter(808982, new Line(2026, 579, 0, 101.8, 0.97), games, List.of(), moves,
                false, new Line(2025, 617, 0, 107.0, 2.0)));

        assertThat(report.tier()).isEqualTo(SeasonTier.SOLID);
        assertThat(report.label()).isEqualTo("제 몫 한 시즌");
        assertThat(report.headline()).isEqualTo("143경기 완주, 타격은 리그 평균");
        assertThat(report.reason()).isEqualTo("6월까지 타율 .316 → 7월부터 .230");
        assertThat(report.level()).isEqualTo(Level.MLB);
        assertThat(report.war()).isEqualTo(1.0);
        assertThat(report.stories()).hasSizeLessThanOrEqualTo(2);
        assertThat(report.winterMoves()).isEmpty();
    }

    @Test
    void baeJiHwan() {
        // 메이저 5월 밀워키 2경기(4타석), 트리플A 92경기 374타석 330타수 86안타(.261) 36도루, 8월 25일 무릎 부상 뒤 경기 없음
        List<GameDay> majors = List.of(hitting(LocalDate.of(2026, 5, 2), 2, 0, 0, 0, 0, "밀워키", "신시내티"),
                hitting(LocalDate.of(2026, 5, 3), 2, 1, 0, 0, 0, "밀워키", "신시내티"));
        int[] hits = spread(86, 92);
        List<GameDay> minors = IntStream.range(0, 92)
                .mapToObj(i -> minorHitting(LocalDate.of(2026, 4, 1).plusDays(i * 146L / 92), "AAA",
                        i < 54 ? 4 : 3, hits[i], i < 36 ? 1 : 0, i < 54 ? 0 : 1))
                .toList();
        List<Move> moves = List.of(
                move("2026-04-30", "CU", "Milwaukee Brewers recalled CF Ji Hwan Bae from Nashville Sounds."),
                move("2026-05-04", "OPT", "Milwaukee Brewers optioned CF Ji Hwan Bae to Nashville Sounds."),
                move("2026-08-25", "SC", "Nashville Sounds placed CF Ji Hwan Bae on the 7-day injured list. Left knee sprain."));

        SeasonReport report = build(hitter(678225, new Line(2026, 4, 0, 249.4, 0.09), majors, minors, moves, false,
                new Line(2023, 371, 0, 77.0, 0.3)));

        assertThat(report.tier()).isEqualTo(SeasonTier.MINORS);
        assertThat(report.label()).isEqualTo("트리플A에서 보낸 시즌");
        assertThat(report.headline()).isEqualTo("트리플A 도루 36개, 타율 .261");
        assertThat(report.reason()).isEqualTo("5월 밀워키에서 메이저 2경기 · 8월 말 부상으로 일찍 시즌 마감");
        assertThat(report.level()).isEqualTo(Level.MINORS);
    }

    @Test
    void kimHyeseong() {
        List<GameDay> majors = IntStream.range(0, 43)
                .mapToObj(i -> hitting(OPENING.plusDays(1 + i), 3, i % 4 == 0 ? 0 : 1, 0, 0, 0)).toList();
        List<GameDay> minors = IntStream.range(0, 86)
                .mapToObj(i -> minorHitting(LocalDate.of(2026, 5, 29).plusDays(i * 110L / 86), "AAA", 4, 1, 0, 1))
                .toList();
        List<Move> moves = List.of(
                move("2026-05-28", "OPT", "Los Angeles Dodgers optioned 2B Hyeseong Kim to Oklahoma City Comets."),
                // 정규시즌(9/27) 다음 날 포스트시즌 명단 콜업도 시즌 이야기다
                move("2026-09-28", "CU", "Los Angeles Dodgers recalled 2B Hyeseong Kim from Oklahoma City Comets."));

        SeasonReport report = build(hitter(808975, new Line(2026, 131, 0, 83.0, 0.35), majors, minors, moves, false,
                new Line(2025, 170, 0, 90.0, 0.4)));

        assertThat(report.tier()).isEqualTo(SeasonTier.MINORS);
        assertThat(report.headline()).isEqualTo("메이저 43경기, 나머지는 트리플A");
        assertThat(report.reason()).isEqualTo("5월 말 트리플A로 · 9월 말 다시 메이저");
        assertThat(report.winterMoves()).isEmpty();
    }

    @Test
    void goWooSuk() {
        // 메이저 7월 미네소타 4경기 4이닝. 마이너: 더블A 8경기 13⅔이닝 1자책(0.66), 트리플A 28경기 37이닝 21자책(5.11)
        List<GameDay> majors = IntStream.range(0, 4)
                .mapToObj(i -> pitching(LocalDate.of(2026, 7, 10 + i * 3), 3, i == 3 ? 3 : 0, i == 3 ? 3 : 0, 1,
                        false, "미네소타", "디트로이트"))
                .toList();
        List<GameDay> minors = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            minors.add(minorPitching(LocalDate.of(2026, 4, 5).plusDays(i * 3L), "AA", i == 0 ? 6 : 5, i == 0 ? 1 : 0));
        }
        for (int i = 0; i < 28; i++) {
            int outs = i < 27 ? 4 : 3;
            minors.add(minorPitching(LocalDate.of(2026, 5, 1).plusDays(i * 4L), "AAA", outs, i < 21 ? 1 : 0));
        }
        List<Move> moves = List.of(
                move("2026-03-24", "OPT", "Minnesota Twins optioned RHP Woo-Suk Go to St. Paul Saints."),
                move("2026-07-09", "SE", "Minnesota Twins selected the contract of RHP Woo-Suk Go from St. Paul Saints."),
                move("2026-07-22", "OPT", "Minnesota Twins optioned RHP Woo-Suk Go to St. Paul Saints."),
                move("2026-10-06", "OUT", "Minnesota Twins sent RHP Woo-Suk Go outright to St. Paul Saints."),
                new Move(LocalDate.of(2026, 11, 3), "DFA", "Declared Free Agency", "RHP Woo-Suk Go elected free agency."));

        SeasonReport report = build(new Input(808970, Role.PITCHER, 2026, OPENING, LAST_DAY, false,
                new Line(2026, 4, 0, 216.1, -0.14), List.of(), majors, minors, moves));

        assertThat(report.tier()).isEqualTo(SeasonTier.MINORS);
        assertThat(report.label()).isEqualTo("마이너에서 보낸 시즌");
        assertThat(report.headline()).isEqualTo("더블A 평균자책점 0.66, 트리플A 5.11");
        assertThat(report.reason()).isEqualTo("7월 미네소타에서 메이저 4경기");
        assertThat(report.war()).isEqualTo(-0.1);
        assertThat(report.winterMoves()).containsExactly(
                new SeasonReport.WinterMove(LocalDate.of(2026, 11, 3), "FA 선언"),
                new SeasonReport.WinterMove(LocalDate.of(2026, 10, 6), "마이너 이관(아웃라이트)"));
    }

    @Test
    void songSungMun() {
        // 메이저 첫해 78경기 141타수 27안타(.191). 개막 직전 옆구리 부상, 트리플A를 두 번 오갔다
        int[] hits = spread(27, 78);
        List<GameDay> majors = IntStream.range(0, 78)
                .mapToObj(i -> hitting(LocalDate.of(2026, 4, 10).plusDays(i * 2L), i < 63 ? 2 : 1, hits[i], 0, 0, 0))
                .toList();
        List<Move> moves = List.of(
                // 개막일 당일 등재도 개막 직전 부상이다
                move("2026-03-25", "SC", "San Diego Padres placed 3B Sung-Mun Song on the 10-day injured list. "
                        + "Right oblique strain."),
                move("2026-04-09", "SC", "San Diego Padres activated 2B Sung-Mun Song from the 10-day injured list."),
                move("2026-05-10", "OPT", "San Diego Padres optioned 2B Sung-Mun Song to El Paso Chihuahuas."),
                move("2026-06-02", "CU", "San Diego Padres recalled 2B Sung-Mun Song from El Paso Chihuahuas."),
                move("2026-07-15", "OPT", "San Diego Padres optioned 2B Sung-Mun Song to El Paso Chihuahuas."),
                move("2026-08-20", "CU", "San Diego Padres recalled 2B Sung-Mun Song from El Paso Chihuahuas."));

        SeasonReport report = build(hitter(823550, new Line(2026, 154, 0, 57.9, 0.24), majors, List.of(), moves, true));

        assertThat(report.tier()).isEqualTo(SeasonTier.POOR);
        assertThat(report.label()).isEqualTo("아쉬운 시즌");
        assertThat(report.headline()).isEqualTo("메이저 첫해 78경기, 타율 .191");
        assertThat(report.reason()).isEqualTo("개막 직전 옆구리 부상 · 트리플A 오가며 적응");
        assertThat(report.stories()).isEmpty();
    }

    @Test
    void kimHaSeong() {
        // 51경기, 3월 손가락 부상 → 7월 같은 곳 염증으로 다시 부상자 명단
        List<GameDay> majors = IntStream.range(0, 51)
                .mapToObj(i -> hitting(LocalDate.of(2026, 5, 1).plusDays(i), 3, i % 3 == 0 ? 1 : 0, 0, 0, 0)).toList();
        List<Move> moves = List.of(
                move("2026-03-27", "SC", "Atlanta Braves placed SS Ha-Seong Kim on the 10-day injured list. "
                        + "Right middle finger sprain."),
                move("2026-04-30", "SC", "Atlanta Braves activated SS Ha-Seong Kim from the 10-day injured list."),
                move("2026-07-10", "SC", "Atlanta Braves placed SS Ha-Seong Kim on the 10-day injured list. "
                        + "Right finger inflammation."),
                move("2026-07-28", "SC", "Atlanta Braves transferred SS Ha-Seong Kim from the 10-day injured list "
                        + "to the 60-day injured list. Right finger inflammation."));

        SeasonReport report = build(hitter(673490, new Line(2026, 147, 0, 7.3, -1.17), majors, List.of(), moves, false,
                new Line(2023, 626, 0, 110.0, 4.4), new Line(2024, 470, 0, 101.0, 2.6)));

        assertThat(report.tier()).isEqualTo(SeasonTier.POOR);
        assertThat(report.headline()).isEqualTo("손가락 부상으로 두 번 빠지며 51경기");
        assertThat(report.reason()).isEqualTo("3월 손가락 부상 · 7월 같은 곳 다시 이탈");
        assertThat(report.war()).isEqualTo(-1.2);
        assertThat(report.stories()).isEmpty();
    }

    @Test
    void missingMetricsCountVolumesFromGames() {
        // 합계·지표를 받지 못했다: 경기별 타석으로 센다(150경기 × 4타석 = 600타석 → 제 몫 한 시즌)
        List<GameDay> majors = IntStream.range(0, 150)
                .mapToObj(i -> hitting(OPENING.plusDays(1 + i), 4, 1, 0, 0, 0)).toList();

        SeasonReport report = build(hitter(1, null, majors, List.of(), List.of(), false));

        assertThat(report.tier()).isEqualTo(SeasonTier.SOLID);
        assertThat(report.war()).isNull();
        assertThat(report.headline()).isEqualTo("150경기 완주, 타율 .250");
    }

    @Test
    void hitStreakNeedsEightGames() {
        List<GameDay> seven = streakThenOut(7);
        List<GameDay> eight = streakThenOut(8);
        assertThat(SeasonReports.hitStreak(seven)).isEqualTo(7);

        SeasonReport noStory = build(hitter(1, new Line(2026, 600, 0, 100.0, 1.0), seven, List.of(), List.of(), false));
        SeasonReport story = build(hitter(1, new Line(2026, 600, 0, 100.0, 1.0), eight, List.of(), List.of(), false));

        assertThat(noStory.stories()).isEmpty();
        assertThat(story.stories()).containsExactly("시즌 최장 8경기 연속 안타");
    }

    @Test
    void walkOnlyGamesDoNotBreakAHitStreak() {
        List<GameDay> games = List.of(hitting(day(1), 4, 1, 0, 0, 0), hitting(day(2), 0, 0, 0, 0, 0),
                hitting(day(3), 3, 2, 0, 0, 0), hitting(day(4), 4, 0, 0, 0, 0));
        assertThat(SeasonReports.hitStreak(games)).isEqualTo(2);
    }

    @Test
    void bestHittingGameNeedsTwoHomersOrThreeHits() {
        List<GameDay> games = List.of(hitting(day(1), 4, 2, 1, 3, 0, "샌프란시스코", "LA 다저스"),
                hitting(day(132), 5, 3, 2, 5, 0, "샌프란시스코", "텍사스"));
        SeasonReport report = build(hitter(1, new Line(2026, 600, 0, 120.0, 3.0), games, List.of(), List.of(), false));

        assertThat(report.tier()).isEqualTo(SeasonTier.GOOD);
        assertThat(report.stories()).containsExactly("최고 경기 8월 4일 텍사스전 3안타 2홈런 5타점");

        List<GameDay> ordinary = List.of(hitting(day(1), 4, 2, 1, 3, 0, "샌프란시스코", "LA 다저스"));
        assertThat(build(hitter(1, new Line(2026, 600, 0, 120.0, 3.0), ordinary, List.of(), List.of(), false))
                .stories()).isEmpty();
    }

    @Test
    void pitcherScorelessStreakAndBestGame() {
        List<GameDay> games = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            games.add(pitching(day(1 + i * 5), 18, 0, 0, 6, false, "토론토", "보스턴"));
        }
        games.add(pitching(day(30), 21, 0, 0, 9, true, "토론토", "시애틀"));
        games.add(pitching(day(35), 15, 4, 4, 3, false, "토론토", "뉴욕 양키스"));
        SeasonReport report = build(new Input(1, Role.PITCHER, 2026, OPENING, LAST_DAY, false,
                new Line(2026, 130, 25, 85.0, 2.8), List.of(), games, List.of(), List.of()));

        assertThat(report.tier()).isEqualTo(SeasonTier.GOOD);
        assertThat(report.headline()).isEqualTo("선발 25경기 130이닝, 평균보다 좋은 투구");
        assertThat(report.stories()).containsExactly("시즌 최장 6번 연속 무실점 등판",
                "최고 경기 4월 24일 시애틀전 7이닝 무실점 9탈삼진 · 승");

        assertThat(SeasonReports.scorelessStreak(games.subList(0, 4))).isEqualTo(4);
        SeasonReport four = build(new Input(1, Role.PITCHER, 2026, OPENING, LAST_DAY, false,
                new Line(2026, 130, 25, 85.0, 2.8), List.of(), games.subList(0, 4), List.of(), List.of()));
        assertThat(four.stories()).isEmpty();
    }

    @Test
    void shortAndPoorSeasonsNeverBrag() {
        List<GameDay> streak = streakThenOut(12);
        SeasonReport shortSeason = build(hitter(1, new Line(2026, 60, 0, 140.0, 0.5), streak, List.of(), List.of(),
                false));
        assertThat(shortSeason.tier()).isEqualTo(SeasonTier.SHORT);
        assertThat(shortSeason.label()).isEqualTo("출전이 적었던 시즌");
        assertThat(shortSeason.stories()).isEmpty();

        SeasonReport poor = build(hitter(1, new Line(2026, 400, 0, 70.0, -0.5), streak, List.of(), List.of(), false));
        assertThat(poor.tier()).isEqualTo(SeasonTier.POOR);
        assertThat(poor.stories()).isEmpty();
    }

    @Test
    void wrcPlusWords() {
        assertThat(SeasonReports.hittingWords(135.0)).isEqualTo("리그 상위급 타격");
        assertThat(SeasonReports.hittingWords(110.0)).isEqualTo("평균보다 좋은 타격");
        assertThat(SeasonReports.hittingWords(101.8)).isEqualTo("타격은 리그 평균");
        assertThat(SeasonReports.hittingWords(85.0)).isEqualTo("타격은 평균보다 조금 아래");
        assertThat(SeasonReports.hittingWords(70.0)).isNull();
        assertThat(SeasonReports.hittingWords(null)).isNull();
        assertThat(SeasonReports.avgText(0.316)).isEqualTo(".316");
        assertThat(SeasonReports.avgText(1.0)).isEqualTo("1.000");
    }

    @Test
    void injuryDictionary() {
        assertThat(InjuryParts.korean("Right oblique strain.")).isEqualTo("옆구리");
        assertThat(InjuryParts.korean("Left side tightness.")).isEqualTo("옆구리");
        assertThat(InjuryParts.korean("Right middle finger sprain.")).isEqualTo("손가락");
        assertThat(InjuryParts.korean("Left thumb sprain.")).isEqualTo("엄지");
        assertThat(InjuryParts.korean("Right hand contusion.")).isEqualTo("손");
        assertThat(InjuryParts.korean("Left wrist inflammation.")).isEqualTo("손목");
        assertThat(InjuryParts.korean("Right elbow UCL sprain.")).isEqualTo("팔꿈치");
        assertThat(InjuryParts.korean("Right shoulder impingement.")).isEqualTo("어깨");
        assertThat(InjuryParts.korean("Right forearm and elbow soreness.")).isEqualTo("팔뚝");
        assertThat(InjuryParts.korean("Lower back strain.")).isEqualTo("허리");
        assertThat(InjuryParts.korean("Left hamstring strain.")).isEqualTo("햄스트링");
        assertThat(InjuryParts.korean("Right knee sprain.")).isEqualTo("무릎");
        assertThat(InjuryParts.korean("Left ankle sprain.")).isEqualTo("발목");
        assertThat(InjuryParts.korean("Right foot fracture.")).isEqualTo("발");
        assertThat(InjuryParts.korean("Left hip flexor strain.")).isEqualTo("고관절");
        assertThat(InjuryParts.korean("Right groin strain.")).isEqualTo("사타구니");
        assertThat(InjuryParts.korean("Left calf strain.")).isEqualTo("종아리");
        assertThat(InjuryParts.korean("Right quad strain.")).isEqualTo("허벅지");
        assertThat(InjuryParts.korean("Left quadriceps strain.")).isEqualTo("허벅지");
        assertThat(InjuryParts.korean("Concussion.")).isEqualTo("뇌진탕");
        assertThat(InjuryParts.korean("Illness.")).isNull();

        assertThat(InjuryParts.isPlacement("SC", "X placed SS Y on the 10-day injured list. Right finger sprain."))
                .isTrue();
        assertThat(InjuryParts.isPlacement("SC", "X transferred SS Y from the 10-day injured list to the 60-day "
                + "injured list.")).isFalse();
        assertThat(InjuryParts.isPlacement("SC", "X activated SS Y from the 10-day injured list.")).isFalse();
        assertThat(InjuryParts.isPlacement("OPT", "X optioned SS Y to Z.")).isFalse();
    }

    @Test
    void injuryReasonsInOrder() {
        // 개막 직전 부상 → 같은 곳 재발 / 다른 곳 / 부위 모름
        List<Move> moves = List.of(
                move("2026-03-20", "SC", "A placed B on the 15-day injured list. Right elbow inflammation."),
                move("2026-06-03", "SC", "A placed B on the 15-day injured list. Right elbow soreness."),
                move("2026-08-01", "SC", "A placed B on the 15-day injured list. Illness."));
        List<GameDay> games = IntStream.range(0, 40)
                .mapToObj(i -> hitting(OPENING.plusDays(30 + i * 3L), 3, 1, 0, 0, 0)).toList();

        SeasonReport report = build(hitter(1, new Line(2026, 160, 0, 95.0, 0.3), games, List.of(), moves, false));

        assertThat(report.reason()).isEqualTo("개막 직전 팔꿈치 부상 · 6월 같은 곳 다시 이탈");
    }

    @Test
    void generatedTextNeverUsesForbiddenWords() {
        List<SeasonReport> reports = new ArrayList<>();
        reports.add(sampleReport(new Line(2026, 650, 0, 145.0, 6.0)));
        reports.add(sampleReport(new Line(2026, 200, 0, 125.0, 1.5)));
        reports.add(sampleReport(new Line(2026, 500, 0, 82.0, 0.0)));
        reports.add(sampleReport(new Line(2026, 400, 0, 60.0, -1.5)));
        reports.add(sampleReport(new Line(2026, 30, 0, null, null)));
        reports.add(sampleReport(null));
        reports.add(build(new Input(2, Role.PITCHER, 2026, OPENING, LAST_DAY, true, new Line(2026, 20, 0, 150.0, -0.5),
                List.of(), List.of(pitching(day(10), 3, 3, 3, 1, false, "토론토", "보스턴")), List.of(), injuries())));
        reports.add(build(new Input(3, Role.PITCHER, 2026, OPENING, LAST_DAY, false, new Line(2026, 180, 30, 60.0, 5.0),
                List.of(), List.of(pitching(day(10), 24, 0, 0, 11, true, "토론토", "보스턴")), List.of(), injuries())));

        List<String> texts = reports.stream()
                .flatMap(r -> Stream.concat(Stream.of(r.label(), r.headline(), r.reason()),
                        Stream.concat(r.stories().stream(), r.winterMoves().stream().map(SeasonReport.WinterMove::label))))
                .filter(text -> text != null)
                .toList();
        assertThat(texts).isNotEmpty();
        for (String text : texts) {
            for (String word : SeasonReports.FORBIDDEN_WORDS) {
                assertThat(text).as("'%s' in '%s'", word, text).doesNotContain(word);
            }
        }
        for (SeasonTier tier : SeasonTier.values()) {
            for (String word : SeasonReports.FORBIDDEN_WORDS) {
                assertThat(tier.label()).doesNotContain(word);
                assertThat(SeasonTier.MINORS_MIXED_LABEL).doesNotContain(word);
            }
        }
    }

    // ---- 재료 만들기 ----

    private static SeasonReport sampleReport(Line line) {
        List<GameDay> games = IntStream.range(0, 120)
                .mapToObj(i -> hitting(OPENING.plusDays(1 + i), 4, i % 10 == 0 ? 3 : 1, i % 40 == 0 ? 2 : 0, 1, 0,
                        "샌프란시스코", "콜로라도"))
                .toList();
        List<GameDay> minors = IntStream.range(0, 20)
                .mapToObj(i -> minorHitting(LocalDate.of(2026, 8, 1).plusDays(i), i % 2 == 0 ? "AAA" : "AA", 4, 1, 0, 0))
                .toList();
        return build(hitter(1, line, games, minors, injuries(), true, new Line(2025, 600, 0, 120.0, 4.0)));
    }

    private static List<Move> injuries() {
        return List.of(
                move("2026-03-01", "SC", "A placed B on the 60-day injured list. Right knee surgery."),
                move("2026-05-12", "OPT", "A optioned B to C."),
                move("2026-06-20", "CU", "A recalled B from C."),
                move("2026-07-01", "SC", "A placed B on the 10-day injured list. Right knee soreness."),
                move("2026-08-01", "SC", "A placed B on the 10-day injured list. Left calf strain."),
                move("2026-10-30", "REL", "A released B."),
                new Move(LocalDate.of(2026, 11, 3), "DFA", "Declared Free Agency", "B elected free agency."));
    }

    private static List<GameDay> streakThenOut(int streak) {
        List<GameDay> games = new ArrayList<>();
        for (int i = 0; i < streak; i++) {
            games.add(hitting(day(1 + i), 4, 1, 0, 0, 0));
        }
        games.add(hitting(day(1 + streak), 4, 0, 0, 0, 0));
        return games;
    }

    private static SeasonReport build(Input input) {
        return SeasonReports.build(input).report();
    }

    private static Input hitter(long id, Line line, List<GameDay> majors, List<GameDay> minors, List<Move> moves,
                                boolean firstSeason, Line... previous) {
        return new Input(id, Role.HITTER, 2026, OPENING, LAST_DAY, firstSeason, line, List.of(previous), majors, minors,
                moves);
    }

    private static LocalDate day(int afterOpening) {
        return OPENING.plusDays(afterOpening);
    }

    private static Move move(String date, String typeCode, String description) {
        return new Move(LocalDate.parse(date), typeCode, null, description);
    }

    private static GameDay hitting(LocalDate date, int atBats, int hits, int homeRuns, int rbi, int steals) {
        return hitting(date, atBats, hits, homeRuns, rbi, steals, "샌프란시스코", "콜로라도");
    }

    private static GameDay hitting(LocalDate date, int atBats, int hits, int homeRuns, int rbi, int steals,
                                   String team, String opponent) {
        return new GameDay(date, null, team, opponent, batting(atBats, hits, homeRuns, rbi, steals, 0), null, false,
                null);
    }

    private static GameDay minorHitting(LocalDate date, String level, int atBats, int hits, int steals, int walks) {
        return new GameDay(date, level, null, null, batting(atBats, hits, 0, 0, steals, walks), null, false, null);
    }

    private static Batting batting(int atBats, int hits, int homeRuns, int rbi, int steals, int walks) {
        return new Batting(atBats, 0, hits, 0, 0, homeRuns, rbi, walks, 0, 0, steals, 0, atBats + walks, null);
    }

    private static GameDay pitching(LocalDate date, int outs, int runs, int earnedRuns, int strikeouts, boolean win,
                                    String team, String opponent) {
        return new GameDay(date, null, team, opponent, null, pitchingLine(outs, runs, earnedRuns, strikeouts, win),
                outs >= 15, win);
    }

    private static GameDay minorPitching(LocalDate date, String level, int outs, int earnedRuns) {
        return new GameDay(date, level, null, null, null, pitchingLine(outs, earnedRuns, earnedRuns, 1, false), false,
                null);
    }

    private static Pitching pitchingLine(int outs, int runs, int earnedRuns, int strikeouts, boolean win) {
        return new Pitching(outs, 0, runs, earnedRuns, 0, strikeouts, 0, 80, win ? 1 : 0, 0, 0);
    }

    /** total을 n경기에 고르게 나눈다(경기마다 0 이상, 합이 total) */
    private static int[] spread(int total, int n) {
        int[] values = new int[n];
        for (int i = 0; i < n; i++) {
            values[i] = (int) ((long) (i + 1) * total / n - (long) i * total / n);
        }
        return values;
    }
}
