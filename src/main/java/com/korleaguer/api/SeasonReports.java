package com.korleaguer.api;

import com.korleaguer.domain.Batting;
import com.korleaguer.domain.Level;
import com.korleaguer.domain.Pitching;
import com.korleaguer.domain.Role;
import com.korleaguer.support.BaseballMath;
import com.korleaguer.support.MoveLabels;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 선수 한 명의 시즌 성적표(SeasonReport)를 만든다(이슈 #118). DB를 읽지 않는 계산만 있다(읽기는 SeasonReportFinder).
 * <ul>
 *   <li>판정: SeasonVerdict. 메이저 합계·지표를 아직 받지 못했으면 경기별 기록으로 양만 세어 판정한다.</li>
 *   <li>한 줄 요약(headline): 판정마다 정해진 틀에 경기 수·타율·평균자책점과 wRC+(ERA-) 말을 넣는다.</li>
 *   <li>이유(reason): 부상자 명단 등재, 트리플A 강등·콜업, 짧은 메이저 출전, 전·후반 타율 차이 중 두 개까지.
 *       부상이 가장 먼저고, 고른 뒤에는 날짜 순으로 잇는다. 없으면 null.</li>
 *   <li>자랑거리(stories): 좋은·짧았지만 좋았던·제 몫 한·마이너 시즌만. 시즌 최장 연속 안타(8경기 이상)·무실점 등판(5번 이상),
 *       최고 경기(2홈런 이상·3안타 이상, 투수는 7이닝 이상 승리·무실점 또는 10탈삼진 이상). 두 개까지.</li>
 *   <li>겨울 이동: 정규시즌 마지막 날 뒤의 이동 기록, 최신순 세 개까지.</li>
 * </ul>
 * 글은 사실만 짧게 쓴다. 부진·실패 같은 평가 말(FORBIDDEN_WORDS)은 쓰지 않는다.
 */
final class SeasonReports {

    /** 성적표 어디에도 쓰지 않는 말(테스트가 만든 글을 모두 훑어 확인한다) */
    static final List<String> FORBIDDEN_WORDS = List.of("부진", "실패", "최악", "몰락", "추락", "망");

    static final int FULL_SEASON_GAMES = 140;
    static final int MIN_HIT_STREAK = 8;
    static final int MIN_SCORELESS_STREAK = 5;
    static final int MAX_STORIES = 2;
    static final int MAX_REASONS = 2;
    static final int MAX_WINTER_MOVES = 3;
    /** 전·후반 타율이 이만큼 이상 다르면 이유로 쓴다 */
    static final double HALF_GAP = 0.050;
    /** 메이저 경기가 이보다 적은 마이너 시즌은 '메이저 n경기'를 이유로 쓴다 */
    static final int SHORT_STINT_GAMES = 10;
    /** 마지막 부상 뒤 경기가 없고, 시즌 끝보다 이만큼 이상 일찍 다쳤으면 '일찍 시즌 마감' */
    static final int EARLY_END_DAYS = 14;
    /** 마이너 양 중 트리플A 밖(더블A 등)이 이만큼 이상이면 '마이너에서 보낸 시즌' */
    static final double MIXED_MINORS_SHARE = 0.2;

    private static final String TRIPLE_A = "AAA";
    private static final Map<String, String> LEVEL_NAMES = Map.of(
            "AAA", "트리플A", "AA", "더블A", "A+", "하이싱글A", "A", "싱글A", "ROK", "루키");
    private static final List<String> LEVEL_ORDER = List.of("ROK", "A", "A+", "AA", "AAA");

    private SeasonReports() {
    }

    /**
     * 성적표 재료.
     *
     * @param openingDay     정규시즌 개막일(미국 날짜). 이보다 앞선 부상은 '개막 직전'
     * @param lastRegularDay 정규시즌 마지막 날(미국 날짜). 이 뒤 이동은 겨울 이동이다
     * @param firstMlbSeason 메이저 첫해인지(데뷔 연도가 이번 시즌)
     * @param mlb            이번 시즌 메이저 합계·지표. 아직 받지 못했으면 null(경기별 기록으로 센다)
     * @param previous       앞 시즌들의 메이저 합계·지표
     * @param majors         이번 정규시즌 메이저 경기
     * @param minors         이번 시즌 마이너 경기
     * @param moves          이동 기록(순서 무관)
     */
    record Input(long playerId, Role role, int season, LocalDate openingDay, LocalDate lastRegularDay,
                 boolean firstMlbSeason, SeasonVerdict.Line mlb, List<SeasonVerdict.Line> previous,
                 List<GameDay> majors, List<GameDay> minors, List<Move> moves) {
    }

    /**
     * 경기 한 번.
     *
     * @param level      메이저면 null, 마이너면 단계 약어(AAA·AA 등)
     * @param teamKo     뛴 팀 한글 이름(메이저만)
     * @param opponentKo 상대 팀 한글 이름(메이저만)
     * @param started    선발 투수로 나왔는지
     * @param win        팀이 이겼는지. 모르면 null
     */
    record GameDay(LocalDate date, String level, String teamKo, String opponentKo, Batting batting, Pitching pitching,
                   boolean started, Boolean win) {
    }

    /** 이동 기록 한 줄 */
    record Move(LocalDate date, String typeCode, String typeDesc, String description) {
    }

    /** 성적표 한 장과 정렬에 쓰는 메이저 경기 수 */
    record Built(SeasonReport report, int majorGames) {
    }

    static Built build(Input in) {
        boolean pitcher = in.role() == Role.PITCHER;
        List<GameDay> majors = byDate(in.majors());
        List<GameDay> minors = byDate(in.minors());
        SeasonVerdict.Line mlb = in.mlb() != null ? in.mlb() : lineFromGames(in.season(), pitcher, majors);
        double minorVolume = pitcher ? outs(minors) / 3.0 : plateAppearances(onLevel(minors, TRIPLE_A));
        SeasonTier tier = SeasonVerdict.judge(in.role(), mlb, minorVolume, in.previous());
        boolean mixedMinors = mixedMinors(pitcher, minors);
        String label = tier == SeasonTier.MINORS && mixedMinors ? SeasonTier.MINORS_MIXED_LABEL : tier.label();

        String headline = headline(in, tier, mlb, majors, minors, mixedMinors);
        String reason = reason(in, tier, majors, minors);
        List<String> stories = tier.hasStories()
                ? stories(pitcher, tier == SeasonTier.MINORS ? minors : majors, tier != SeasonTier.MINORS)
                : List.of();
        Double war = mlb.war() == null ? null : Math.round(mlb.war() * 10) / 10.0;
        SeasonReport report = new SeasonReport(in.playerId(), tier, label, headline, reason,
                tier == SeasonTier.MINORS ? Level.MINORS : Level.MLB, war, stories, winterMoves(in));
        return new Built(report, majors.size());
    }

    // ---- 판정 재료 ----

    /** 합계를 받지 못했을 때: 경기별 기록으로 양(타석·이닝·선발)만 센다. 지표는 모른다. */
    private static SeasonVerdict.Line lineFromGames(int season, boolean pitcher, List<GameDay> majors) {
        if (pitcher) {
            int started = (int) majors.stream().filter(GameDay::started).count();
            return new SeasonVerdict.Line(season, outs(majors) / 3.0, started, null, null);
        }
        return new SeasonVerdict.Line(season, plateAppearances(majors), 0, null, null);
    }

    private static boolean mixedMinors(boolean pitcher, List<GameDay> minors) {
        double all = pitcher ? outs(minors) : plateAppearances(minors);
        if (all == 0) {
            return false;
        }
        List<GameDay> tripleA = onLevel(minors, TRIPLE_A);
        double outside = all - (pitcher ? outs(tripleA) : plateAppearances(tripleA));
        return outside / all >= MIXED_MINORS_SHARE;
    }

    // ---- 한 줄 요약 ----

    private static String headline(Input in, SeasonTier tier, SeasonVerdict.Line mlb, List<GameDay> majors,
                                   List<GameDay> minors, boolean mixedMinors) {
        boolean pitcher = in.role() == Role.PITCHER;
        int games = majors.size();
        return switch (tier) {
            case GOOD, SHORT_GOOD, SOLID -> {
                String quality = pitcher ? pitchingWords(mlb.quality()) : hittingWords(mlb.quality());
                yield volumeText(pitcher, majors, mlb) + ", " + (quality != null ? quality : rateText(pitcher, majors));
            }
            case MINORS -> minorsHeadline(pitcher, games, minors, mixedMinors);
            case SHORT -> games == 0 ? "메이저 출전 없음" : volumeText(pitcher, majors, mlb) + ", " + rateText(pitcher, majors);
            case POOR -> poorHeadline(in, majors);
        };
    }

    /** wRC+ 말. 80 미만이거나 모르면 null */
    static String hittingWords(Double wrcPlus) {
        if (wrcPlus == null) {
            return null;
        }
        if (wrcPlus >= 130) {
            return "리그 상위급 타격";
        }
        if (wrcPlus >= 110) {
            return "평균보다 좋은 타격";
        }
        if (wrcPlus >= 90) {
            return "타격은 리그 평균";
        }
        if (wrcPlus >= 80) {
            return "타격은 평균보다 조금 아래";
        }
        return null;
    }

    /** ERA- 말(낮을수록 좋다). 120 이상이거나 모르면 null */
    static String pitchingWords(Double eraMinus) {
        if (eraMinus == null) {
            return null;
        }
        if (eraMinus <= 70) {
            return "리그 상위급 투구";
        }
        if (eraMinus <= 90) {
            return "평균보다 좋은 투구";
        }
        if (eraMinus <= 110) {
            return "투구는 리그 평균";
        }
        if (eraMinus < 120) {
            return "투구는 평균보다 조금 아래";
        }
        return null;
    }

    /** 타자 "143경기 완주"·"98경기 출전", 투수 "선발 28경기 160⅓이닝"·"55경기 52⅔이닝" */
    private static String volumeText(boolean pitcher, List<GameDay> majors, SeasonVerdict.Line mlb) {
        int games = majors.size();
        if (!pitcher) {
            return games + (games >= FULL_SEASON_GAMES ? "경기 완주" : "경기 출전");
        }
        int outs = (int) Math.round(mlb.volume() * 3);
        String innings = BaseballMath.inningsText(outs) + "이닝";
        int started = mlb.gamesStarted();
        return started >= 5 && started * 2 >= games ? "선발 " + started + "경기 " + innings
                : games + "경기 " + innings;
    }

    /** "타율 .191" / "평균자책점 4.50". 기록이 없으면 "기록 없음" */
    private static String rateText(boolean pitcher, List<GameDay> games) {
        if (pitcher) {
            Double era = BaseballMath.era(earnedRuns(games), outs(games));
            return era == null ? "기록 없음" : "평균자책점 " + eraText(era);
        }
        Double avg = BaseballMath.avg(hits(games), atBats(games));
        return avg == null ? "기록 없음" : "타율 " + avgText(avg);
    }

    private static String minorsHeadline(boolean pitcher, int majorGames, List<GameDay> minors, boolean mixed) {
        String minorWord = mixed ? "마이너" : "트리플A";
        if (majorGames >= SHORT_STINT_GAMES) {
            return "메이저 " + majorGames + "경기, 나머지는 " + minorWord;
        }
        if (pitcher) {
            // 이닝이 많은 단계 두 곳까지, 낮은 단계부터: "더블A 평균자책점 0.66, 트리플A 5.11"
            List<String> levels = minors.stream().map(GameDay::level).filter(Objects::nonNull).distinct()
                    .filter(level -> outs(onLevel(minors, level)) > 0)
                    .sorted(Comparator.comparingInt((String level) -> outs(onLevel(minors, level))).reversed())
                    .limit(2)
                    .sorted(Comparator.comparingInt(SeasonReports::levelRank))
                    .toList();
            if (levels.isEmpty()) {
                return minors.size() + "경기 마이너 등판";
            }
            if (levels.size() == 1) {
                List<GameDay> games = onLevel(minors, levels.getFirst());
                return levelName(levels.getFirst()) + " " + games.size() + "경기 " + rateText(true, games);
            }
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < levels.size(); i++) {
                Double era = BaseballMath.era(earnedRuns(onLevel(minors, levels.get(i))),
                        outs(onLevel(minors, levels.get(i))));
                parts.add(levelName(levels.get(i)) + (i == 0 ? " 평균자책점 " : " ") + eraText(era));
            }
            return String.join(", ", parts);
        }
        List<GameDay> games = mixed ? minors : onLevel(minors, TRIPLE_A);
        int homeRuns = sum(games, b -> b.homeRuns());
        int steals = sum(games, b -> b.stolenBases());
        String highlight;
        if (homeRuns >= 15) {
            highlight = minorWord + " 홈런 " + homeRuns + "개";
        } else if (steals >= 20) {
            highlight = minorWord + " 도루 " + steals + "개";
        } else {
            highlight = minorWord + " " + games.size() + "경기";
        }
        return highlight + ", " + rateText(false, games);
    }

    private static String poorHeadline(Input in, List<GameDay> majors) {
        boolean pitcher = in.role() == Role.PITCHER;
        int games = majors.size();
        if (in.firstMlbSeason()) {
            return "메이저 첫해 " + games + "경기, " + rateText(pitcher, majors);
        }
        List<Move> placements = placements(in);
        if (placements.size() >= 2) {
            Set<String> parts = new LinkedHashSet<>();
            boolean known = true;
            for (Move move : placements) {
                String part = InjuryParts.korean(move.description());
                if (part == null) {
                    known = false;
                } else {
                    parts.add(part);
                }
            }
            String times = switch (placements.size()) {
                case 2 -> "두 번";
                case 3 -> "세 번";
                default -> "여러 번";
            };
            String cause = known && parts.size() == 1 ? parts.iterator().next() + " 부상으로" : "부상으로";
            return cause + " " + times + " 빠지며 " + games + "경기";
        }
        return games + "경기, " + rateText(pitcher, majors);
    }

    // ---- 이유 ----

    /** 이유 한 가지. 고를 때 priority(작을수록 먼저), 이을 때 날짜 순 */
    private record Fact(LocalDate date, int priority, String text) {
    }

    private static String reason(Input in, SeasonTier tier, List<GameDay> majors, List<GameDay> minors) {
        List<Fact> facts = new ArrayList<>(injuryFacts(in, majors, minors));
        Fact stint = tier == SeasonTier.MINORS ? stintFact(majors) : null;
        if (stint != null) {
            facts.add(stint);
        } else {
            facts.addAll(moveFacts(in));
        }
        if (in.role() == Role.HITTER && (tier == SeasonTier.GOOD || tier == SeasonTier.SOLID)) {
            Fact half = halfFact(majors);
            if (half != null) {
                facts.add(half);
            }
        }
        List<String> chosen = facts.stream()
                .sorted(Comparator.comparingInt(Fact::priority).thenComparing(Fact::date))
                .limit(MAX_REASONS)
                .sorted(Comparator.comparing(Fact::date))
                .map(Fact::text)
                .toList();
        return chosen.isEmpty() ? null : String.join(" · ", chosen);
    }

    /** 이번 시즌(2월 ~ 정규시즌 마지막 날) 부상자 명단 등재, 날짜 순(같은 날은 하나) */
    private static List<Move> placements(Input in) {
        LocalDate from = LocalDate.of(in.season(), 2, 1);
        Set<LocalDate> days = new HashSet<>();
        return in.moves().stream()
                .filter(m -> m.date() != null && !m.date().isBefore(from) && !m.date().isAfter(in.lastRegularDay()))
                .filter(m -> InjuryParts.isPlacement(m.typeCode(), m.description()))
                .sorted(Comparator.comparing(Move::date))
                .filter(m -> days.add(m.date()))
                .toList();
    }

    private static List<Fact> injuryFacts(Input in, List<GameDay> majors, List<GameDay> minors) {
        List<Move> placements = placements(in);
        LocalDate lastGame = Stream.concat(majors.stream(), minors.stream())
                .map(GameDay::date).max(Comparator.naturalOrder()).orElse(null);
        Set<String> seen = new HashSet<>();
        List<Fact> facts = new ArrayList<>();
        for (int i = 0; i < placements.size(); i++) {
            Move move = placements.get(i);
            String part = InjuryParts.korean(move.description());
            LocalDate day = move.date();
            String text;
            if (part != null && seen.contains(part)) {
                text = day.getMonthValue() + "월 같은 곳 다시 이탈";
            } else if (day.isBefore(in.openingDay())) {
                text = "개막 직전 " + (part == null ? "부상" : part + " 부상");
            } else if (i == placements.size() - 1 && (lastGame == null || !lastGame.isAfter(day))
                    && !day.isAfter(in.lastRegularDay().minusDays(EARLY_END_DAYS))) {
                text = monthPart(day) + " 부상으로 일찍 시즌 마감";
            } else {
                text = day.getMonthValue() + "월 " + (part == null ? "부상으로 이탈" : part + " 부상");
            }
            if (part != null) {
                seen.add(part);
            }
            facts.add(new Fact(day, 0, text));
        }
        return facts;
    }

    /** 마이너 시즌에 메이저를 잠깐(10경기 미만) 뛰었으면 "7월 미네소타에서 메이저 4경기" 또는 "메이저 4경기" */
    private static Fact stintFact(List<GameDay> majors) {
        if (majors.isEmpty() || majors.size() >= SHORT_STINT_GAMES) {
            return null;
        }
        Set<Integer> months = new HashSet<>();
        Set<String> teams = new HashSet<>();
        majors.forEach(g -> {
            months.add(g.date().getMonthValue());
            teams.add(g.teamKo());
        });
        LocalDate first = majors.getFirst().date();
        String text = months.size() == 1 && teams.size() == 1 && first != null && majors.getFirst().teamKo() != null
                ? first.getMonthValue() + "월 " + majors.getFirst().teamKo() + "에서 메이저 " + majors.size() + "경기"
                : "메이저 " + majors.size() + "경기";
        return new Fact(first, 1, text);
    }

    /** 정규시즌 중 트리플A 강등(옵션·아웃라이트)과 메이저 콜업 */
    private static List<Fact> moveFacts(Input in) {
        List<Move> inSeason = in.moves().stream()
                .filter(m -> m.date() != null && !m.date().isBefore(in.openingDay())
                        && !m.date().isAfter(in.lastRegularDay()))
                .sorted(Comparator.comparing(Move::date))
                .toList();
        List<Move> down = inSeason.stream().filter(m -> isCode(m, "OPT", "OUT")).toList();
        List<Move> up = inSeason.stream().filter(m -> isCode(m, "CU", "SE")).toList();
        List<Fact> facts = new ArrayList<>();
        if (down.size() >= 2) {
            facts.add(new Fact(down.getFirst().date(), 2, "트리플A 오가며" + (in.firstMlbSeason() ? " 적응" : "")));
        } else if (down.size() == 1) {
            LocalDate day = down.getFirst().date();
            facts.add(new Fact(day, 2, monthPart(day) + " 트리플A로"));
            up.stream().filter(m -> m.date().isAfter(day)).findFirst()
                    .ifPresent(m -> facts.add(new Fact(m.date(), 2, monthPart(m.date()) + " 다시 메이저")));
        } else if (!up.isEmpty()) {
            LocalDate day = up.getFirst().date();
            facts.add(new Fact(day, 2, monthPart(day) + " 메이저로"));
        }
        return facts;
    }

    /** 전·후반 타율 차이(.050 이상). 경기 수 절반에 가장 가까운 달 경계에서 나눈다. 반마다 50타수는 있어야 한다. */
    private static Fact halfFact(List<GameDay> majors) {
        List<GameDay> games = majors.stream().filter(g -> g.batting() != null).toList();
        if (games.size() < 2) {
            return null;
        }
        LocalDate firstDay = games.getFirst().date();
        LocalDate lastDay = games.getLast().date();
        LocalDate split = null;
        double best = Double.MAX_VALUE;
        for (LocalDate b = firstDay.withDayOfMonth(1).plusMonths(1); !b.isAfter(lastDay); b = b.plusMonths(1)) {
            LocalDate boundary = b;
            long before = games.stream().filter(g -> g.date().isBefore(boundary)).count();
            double distance = Math.abs(before - games.size() / 2.0);
            if (distance < best) {
                best = distance;
                split = boundary;
            }
        }
        if (split == null) {
            return null;
        }
        LocalDate boundary = split;
        List<GameDay> first = games.stream().filter(g -> g.date().isBefore(boundary)).toList();
        List<GameDay> second = games.stream().filter(g -> !g.date().isBefore(boundary)).toList();
        if (atBats(first) < 50 || atBats(second) < 50) {
            return null;
        }
        double before = (double) hits(first) / atBats(first);
        double after = (double) hits(second) / atBats(second);
        if (Math.abs(before - after) < HALF_GAP) {
            return null;
        }
        String text = boundary.minusMonths(1).getMonthValue() + "월까지 타율 " + avgText(BaseballMath.avg(hits(first),
                atBats(first))) + " → " + boundary.getMonthValue() + "월부터 " + avgText(BaseballMath.avg(hits(second),
                atBats(second)));
        return new Fact(boundary, 3, text);
    }

    // ---- 자랑거리 ----

    private static List<String> stories(boolean pitcher, List<GameDay> games, boolean majors) {
        List<String> stories = new ArrayList<>();
        int streak = pitcher ? scorelessStreak(games) : hitStreak(games);
        if (pitcher && streak >= MIN_SCORELESS_STREAK) {
            stories.add("시즌 최장 " + streak + "번 연속 무실점 등판");
        } else if (!pitcher && streak >= MIN_HIT_STREAK) {
            stories.add("시즌 최장 " + streak + "경기 연속 안타");
        }
        String best = pitcher ? bestPitchingGame(games, majors) : bestHittingGame(games, majors);
        if (best != null) {
            stories.add(best);
        }
        return stories.stream().limit(MAX_STORIES).toList();
    }

    /** 가장 긴 연속 안타 경기. 타수 없는 경기(볼넷만 등)는 끊지도 늘리지도 않는다. */
    static int hitStreak(List<GameDay> games) {
        int best = 0;
        int current = 0;
        for (GameDay game : games) {
            Batting b = game.batting();
            if (b == null) {
                continue;
            }
            if (orZero(b.hits()) > 0) {
                current++;
                best = Math.max(best, current);
            } else if (orZero(b.atBats()) > 0) {
                current = 0;
            }
        }
        return best;
    }

    /** 가장 긴 연속 무실점 등판 */
    static int scorelessStreak(List<GameDay> games) {
        int best = 0;
        int current = 0;
        for (GameDay game : games) {
            Pitching p = game.pitching();
            if (p == null || p.pitchingOuts() == null) {
                continue;
            }
            if (orZero(p.runsAllowed()) == 0) {
                current++;
                best = Math.max(best, current);
            } else {
                current = 0;
            }
        }
        return best;
    }

    /** "최고 경기 8/4 텍사스전 2홈런 3안타". 2홈런 이상이나 3안타 이상인 경기 중 가장 큰 경기(같으면 먼저 것) */
    private static String bestHittingGame(List<GameDay> games, boolean majors) {
        GameDay best = null;
        int bestScore = -1;
        for (GameDay game : games) {
            Batting b = game.batting();
            if (b == null || (orZero(b.homeRuns()) < 2 && orZero(b.hits()) < 3)) {
                continue;
            }
            int score = orZero(b.homeRuns()) * 3 + orZero(b.hits()) * 2 + orZero(b.rbi());
            if (score > bestScore) {
                bestScore = score;
                best = game;
            }
        }
        if (best == null) {
            return null;
        }
        Batting b = best.batting();
        List<String> parts = new ArrayList<>();
        if (orZero(b.homeRuns()) > 0) {
            parts.add(b.homeRuns() + "홈런");
        }
        parts.add(orZero(b.hits()) + "안타");
        if (orZero(b.rbi()) >= 3) {
            parts.add(b.rbi() + "타점");
        }
        return bestGameText(best, majors, String.join(" ", parts));
    }

    /** "최고 경기 6/12 시애틀전 7이닝 무실점 9탈삼진 · 승". 7이닝 이상 승리·무실점이거나 10탈삼진 이상인 경기 */
    private static String bestPitchingGame(List<GameDay> games, boolean majors) {
        GameDay best = null;
        int bestScore = Integer.MIN_VALUE;
        for (GameDay game : games) {
            Pitching p = game.pitching();
            if (p == null || p.pitchingOuts() == null) {
                continue;
            }
            int outs = p.pitchingOuts();
            int runs = orZero(p.runsAllowed());
            int strikeouts = orZero(p.pitcherStrikeouts());
            boolean qualifies = (outs >= 21 && (orZero(p.wins()) > 0 || runs == 0)) || strikeouts >= 10;
            if (!qualifies) {
                continue;
            }
            int score = outs + 3 * strikeouts - 5 * runs;
            if (score > bestScore) {
                bestScore = score;
                best = game;
            }
        }
        if (best == null) {
            return null;
        }
        Pitching p = best.pitching();
        List<String> parts = new ArrayList<>();
        parts.add(BaseballMath.inningsText(p.pitchingOuts()) + "이닝");
        parts.add(orZero(p.runsAllowed()) == 0 ? "무실점" : p.runsAllowed() + "실점");
        if (orZero(p.pitcherStrikeouts()) >= 5) {
            parts.add(p.pitcherStrikeouts() + "탈삼진");
        }
        String text = String.join(" ", parts) + (orZero(p.wins()) > 0 ? " · 승" : "");
        return bestGameText(best, majors, text);
    }

    private static String bestGameText(GameDay game, boolean majors, String summary) {
        String day = game.date().getMonthValue() + "/" + game.date().getDayOfMonth();
        String opponent = majors && game.opponentKo() != null ? " " + game.opponentKo() + "전" : "";
        return "최고 경기 " + day + opponent + " " + summary;
    }

    // ---- 겨울 이동 ----

    private static List<SeasonReport.WinterMove> winterMoves(Input in) {
        record Key(LocalDate date, String typeCode, String description) {
        }
        Set<Key> seen = new HashSet<>();
        List<SeasonReport.WinterMove> result = new ArrayList<>();
        List<Move> newestFirst = in.moves().stream()
                .filter(m -> m.date() != null && m.date().isAfter(in.lastRegularDay()))
                .sorted(Comparator.comparing(Move::date).reversed())
                .toList();
        for (Move move : newestFirst) {
            String label = MoveLabels.korean(move.typeCode(), move.typeDesc(), move.description());
            if (label == null || !seen.add(new Key(move.date(), move.typeCode(), move.description()))) {
                continue;
            }
            result.add(new SeasonReport.WinterMove(move.date(), label));
            if (result.size() == MAX_WINTER_MOVES) {
                break;
            }
        }
        return result;
    }

    // ---- 작은 도우미 ----

    private static List<GameDay> byDate(List<GameDay> games) {
        return games.stream().filter(g -> g.date() != null).sorted(Comparator.comparing(GameDay::date)).toList();
    }

    private static List<GameDay> onLevel(List<GameDay> games, String level) {
        return games.stream().filter(g -> level.equals(g.level())).toList();
    }

    private static int levelRank(String level) {
        int rank = LEVEL_ORDER.indexOf(level);
        return rank < 0 ? -1 : rank;
    }

    private static String levelName(String level) {
        return LEVEL_NAMES.getOrDefault(level, level);
    }

    private static boolean isCode(Move move, String... codes) {
        String code = move.typeCode() == null ? "" : move.typeCode().trim().toUpperCase(Locale.ROOT);
        for (String c : codes) {
            if (c.equals(code)) {
                return true;
            }
        }
        return false;
    }

    /** "5월 초"(1~15일) / "5월 말"(16일~) */
    private static String monthPart(LocalDate day) {
        return day.getMonthValue() + "월 " + (day.getDayOfMonth() <= 15 ? "초" : "말");
    }

    /** 타율 .316 (1 이상이면 1.000) */
    static String avgText(Double avg) {
        String text = String.format(Locale.ROOT, "%.3f", avg);
        return text.startsWith("0") ? text.substring(1) : text;
    }

    private static String eraText(Double era) {
        return era == null ? "-" : String.format(Locale.ROOT, "%.2f", era);
    }

    private static int plateAppearances(List<GameDay> games) {
        return games.stream().filter(g -> g.batting() != null)
                .mapToInt(g -> g.batting().plateAppearancesOrComputed()).sum();
    }

    private static int outs(List<GameDay> games) {
        return games.stream().filter(g -> g.pitching() != null).mapToInt(g -> orZero(g.pitching().pitchingOuts())).sum();
    }

    private static int earnedRuns(List<GameDay> games) {
        return games.stream().filter(g -> g.pitching() != null).mapToInt(g -> orZero(g.pitching().earnedRuns())).sum();
    }

    private static int hits(List<GameDay> games) {
        return sum(games, Batting::hits);
    }

    private static int atBats(List<GameDay> games) {
        return sum(games, Batting::atBats);
    }

    private static int sum(List<GameDay> games, Function<Batting, Integer> field) {
        return games.stream().filter(g -> g.batting() != null).mapToInt(g -> orZero(field.apply(g.batting()))).sum();
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
