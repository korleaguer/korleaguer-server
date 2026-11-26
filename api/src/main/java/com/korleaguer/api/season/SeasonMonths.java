package com.korleaguer.api.season;

import com.korleaguer.api.season.SeasonReport.Month;
import com.korleaguer.api.season.SeasonReports.GameDay;
import com.korleaguer.api.season.SeasonReports.Move;
import com.korleaguer.domain.Batting;
import com.korleaguer.domain.Role;
import com.korleaguer.support.BaseballMath;
import com.korleaguer.support.MoveLabels;
import com.korleaguer.support.Woba;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 시즌 성적표의 달별 기록(이슈 #170). 홈 카드가 달마다 막대 하나를 그린다(사용자 11-16 'B 보완' 시안).
 * 달마다 어디서 뛰었나(메이저·마이너 단계·부상·FA)와 그곳의 타율·wRC+(투수는 평균자책점·ERA+)를 준다.
 * 막대는 wRC+·ERA+(그 리그 평균 = 100)로 그린다(사용자 11-17: 타율로 시즌을 그리는 건 원시적, 이슈 #175).
 * <ul>
 *   <li>달: 정규시즌 개막일부터 마지막 날까지. 시즌 날이 MIN_MONTH_DAYS보다 적은 첫 달·끝 달은 옆 달에 붙인다
 *       (3월 26일 개막이면 3월 말은 4월 칸이다).</li>
 *   <li>날마다 부상자 명단에 있었나·재활 경기 중이었나·팀이 없었나를 이동 기록으로 따라간다. 같은 날 이동은
 *       해제 → 등재 → 재활 → 계약·트레이드 → 지명 할당 → 강등·이관·팀 배정 → 콜업 → 방출 → FA 선언 순으로 본다.</li>
 *   <li>재활 경기(부상자 명단에 있으면서 재활 경기 배정 뒤 치른 마이너 경기)는 그 달 성적에서 뺀다. 마이너 부상자 명단은
 *       해제 기록이 없을 때가 많아, 재활 배정이 없으면 부상자 명단 중 경기도 보통 경기로 센다.</li>
 *   <li>그 달 경기가 가장 많은 곳(메이저 또는 마이너 단계, 같으면 메이저·높은 단계)의 기록을 쓴다. 재활 경기가 더 많거나
 *       경기가 없으면 부상(재활 경기가 있었거나 부상자 명단 날이 팀 없는 날보다 많으면)·FA(팀 없는 날이 더 많으면)·
 *       없음 중 하나다.</li>
 *   <li>wRC+: 그 단계 리그 평균(LeagueEnvironments) 대비. 메이저 달은 선수의 MLB 공식 시즌 wRC+와 우리 계산의 차이만큼
 *       옮긴다(구장·해마다 다른 가중치 보정, 웹 '타격 흐름'과 같은 방식). 마이너 달은 옮기지 않는다.</li>
 *   <li>ERA+: 100 × 리그 평균자책점 ÷ 그 달 평균자책점(높을수록 잘함, ERA_PLUS_CAP까지, 무자책이면 CAP). 메이저 달은
 *       공식 시즌 ERA-로 낸 ERA+(10000 ÷ ERA-)와 우리 계산의 비율만큼 곱한다.</li>
 * </ul>
 * 성적표의 시즌 wRC+·ERA+(SeasonRates)와 전·후반 이유(SeasonReports)도 같은 계산(wrcPlus·eraPlus·scale)을 쓴다.
 * 그래서 한 카드의 달 막대·시즌 숫자·이유 줄이 같은 잣대로 읽힌다(이슈 #179).
 */
final class SeasonMonths {

    /** 시즌 날이 이보다 적은 첫 달·끝 달은 옆 달에 붙인다 */
    static final int MIN_MONTH_DAYS = 10;
    /** 경기가 이보다 적은 달은 '경기 적은 달'(화면이 막대를 옅게 그린다). 타자 경기 수 */
    static final int FEW_HITTER_GAMES = 10;
    /** 투수 등판 수 */
    static final int FEW_PITCHER_GAMES = 5;
    /**
     * 아주 잘한 달(화면이 숫자를 주황으로): 메이저 타자 이 경기 수 이상에 wRC+ HOT_WRC 이상.
     * 성적표 문구의 '리그 상위급 타격'(wRC+ 130, SeasonReports.hittingWords)과 같은 선
     */
    static final int HOT_HITTER_GAMES = 15;
    static final int HOT_WRC = 130;
    /** 메이저 투수 이 아웃 수(15이닝) 이상에 ERA+ HOT_ERA_PLUS 이상('리그 상위급 투구' ERA- 70 = ERA+ 143) */
    static final int HOT_PITCHER_OUTS = 45;
    static final int HOT_ERA_PLUS = 143;
    /** ERA+ 최댓값(무자책 달 포함). 화면 막대는 200에서 꽉 찬다 */
    static final int ERA_PLUS_CAP = 300;

    private static final String MLB = LeagueEnv.MLB;

    private SeasonMonths() {
    }

    /** 날마다 상태 */
    private record DayState(boolean injured, boolean rehab, boolean gone) {
    }

    /** 칸 하나가 덮는 날(시작·끝 포함)과 칸에 적을 달 */
    private record Span(int month, LocalDate from, LocalDate to) {
    }

    /** 리그 평균 없이(모든 단계를 LeagueEnv.STANDARD로) */
    static List<Month> build(SeasonReports.Input in) {
        return build(in, Map.of());
    }

    /**
     * @param leagues 단계(MLB·AAA 등)별 리그 평균. 없는 단계는 LeagueEnv.STANDARD로 잰다
     */
    static List<Month> build(SeasonReports.Input in, Map<String, LeagueEnv> leagues) {
        LocalDate opening = in.openingDay();
        LocalDate last = in.lastRegularDay();
        if (opening == null || last == null || last.isBefore(opening)) {
            return List.of();
        }
        boolean pitcher = in.role() == Role.PITCHER;
        Map<LocalDate, DayState> days = dayStates(in);
        Scale scale = scale(in, pitcher, league(leagues, MLB));
        List<Month> months = new ArrayList<>();
        for (Span span : spans(opening, last)) {
            months.add(month(span, pitcher, days, in.majors(), in.minors(), leagues, scale));
        }
        return months;
    }

    /** 메이저 달을 공식 시즌 값에 맞추는 보정: 타자 wRC+에 더할 값, 투수 ERA+에 곱할 값 */
    record Scale(double wrcShift, double eraFactor) {
        static final Scale NONE = new Scale(0, 1);
    }

    /**
     * 공식 시즌 지표(in.mlb().quality(): 타자 wRC+, 투수 ERA-)와 경기별 기록으로 낸 시즌 값의 차이.
     * 공식 값이 없거나 경기 기록이 비면 보정하지 않는다. 전·후반 이유(SeasonReports)도 이 보정으로 두 반을 잰다
     */
    static Scale scale(SeasonReports.Input in, boolean pitcher, LeagueEnv mlb) {
        Double official = in.mlb() == null ? null : in.mlb().quality();
        if (official == null) {
            return Scale.NONE;
        }
        List<GameDay> majors = in.majors().stream().filter(g -> hasStat(g, pitcher)).toList();
        if (pitcher) {
            int outs = majors.stream().mapToInt(g -> orZero(g.pitching().pitchingOuts())).sum();
            int earnedRuns = majors.stream().mapToInt(g -> orZero(g.pitching().earnedRuns())).sum();
            if (official <= 0 || outs == 0 || earnedRuns == 0) {
                return Scale.NONE;
            }
            double ours = EraMath.eraPlus(earnedRuns, outs, mlb);
            return new Scale(0, EraMath.fromMinus(official) / ours);
        }
        Double woba = woba(majors);
        return woba == null ? Scale.NONE : new Scale(official - WrcMath.wrcPlus(woba, mlb), 1);
    }

    /** 그 단계 리그 평균. 받지 못한 단계(단계를 모르는 경기 포함)는 LeagueEnv.STANDARD */
    static LeagueEnv league(Map<String, LeagueEnv> leagues, String stage) {
        return stage == null ? LeagueEnv.STANDARD : leagues.getOrDefault(stage, LeagueEnv.STANDARD);
    }

    /** 경기들의 wRC+(그 리그 평균 대비에 shift를 더해 반올림). 타격 기록이 있는 경기만 넘긴다. 타석이 없으면 null */
    static Integer wrcPlus(List<GameDay> games, LeagueEnv league, double shift) {
        Double woba = woba(games);
        return woba == null ? null : (int) Math.round(WrcMath.wrcPlus(woba, league) + shift);
    }

    /** 경기들의 wOBA. 타격 기록이 있는 경기만 넘긴다. 타석이 없으면 null */
    static Double woba(List<GameDay> games) {
        Woba.Sum sum = new Woba.Sum();
        for (GameDay g : games) {
            Batting b = g.batting();
            sum.add(orZero(b.atBats()), orZero(b.hits()), orZero(b.doubles()), orZero(b.triples()),
                    orZero(b.homeRuns()), orZero(b.walks()), orZero(b.hitByPitch()), orZero(b.sacFlies()));
        }
        return sum.woba();
    }

    // ---- 칸 나누기 ----

    private static List<Span> spans(LocalDate opening, LocalDate last) {
        List<Span> spans = new ArrayList<>();
        for (YearMonth ym = YearMonth.from(opening); !ym.isAfter(YearMonth.from(last)); ym = ym.plusMonths(1)) {
            LocalDate from = ym.atDay(1).isBefore(opening) ? opening : ym.atDay(1);
            LocalDate to = ym.atEndOfMonth().isAfter(last) ? last : ym.atEndOfMonth();
            spans.add(new Span(ym.getMonthValue(), from, to));
        }
        if (spans.size() >= 2 && days(spans.getFirst()) < MIN_MONTH_DAYS) {
            Span first = spans.removeFirst();
            Span next = spans.removeFirst();
            spans.addFirst(new Span(next.month(), first.from(), next.to()));
        }
        if (spans.size() >= 2 && days(spans.getLast()) < MIN_MONTH_DAYS) {
            Span end = spans.removeLast();
            Span previous = spans.removeLast();
            spans.addLast(new Span(previous.month(), previous.from(), end.to()));
        }
        return spans;
    }

    private static long days(Span span) {
        return span.to().toEpochDay() - span.from().toEpochDay() + 1;
    }

    // ---- 날마다 상태 ----

    /** 이동이 상태를 어떻게 바꾸나. order는 같은 날 이동을 보는 순서 */
    private enum Change {
        ACTIVATED(0), INJURED(1), REHAB(2), JOINED(3), DESIGNATED(4), ASSIGNED(5), CALLED_UP(6), RELEASED(7),
        FREE_AGENT(8), OTHER(5);

        final int order;

        Change(int order) {
            this.order = order;
        }
    }

    private static Change change(Move move) {
        String code = move.typeCode() == null ? "" : move.typeCode().trim().toUpperCase(Locale.ROOT);
        String label = MoveLabels.korean(move.typeCode(), move.typeDesc(), move.description());
        if (label == null) {
            return Change.OTHER;
        }
        return switch (label) {
            case "부상자 명단 해제" -> Change.ACTIVATED;
            case "재활 경기 배정" -> Change.REHAB;
            case "부상자 명단 등재", "부상자 명단 이동" -> Change.INJURED;
            case "메이저 콜업" -> Change.CALLED_UP;
            case "방출" -> Change.RELEASED;
            case "FA 선언" -> Change.FREE_AGENT;
            case "지명 할당(DFA)" -> Change.DESIGNATED;
            default -> switch (code) {
                case "TR", "SFA", "SGN", "CLW" -> Change.JOINED;
                case "OPT", "OUT", "ASG" -> Change.ASSIGNED;
                default -> Change.OTHER;
            };
        };
    }

    /**
     * 개막일 ~ 마지막 날의 날마다 상태. 개막 전 이동으로 시작 상태를 정한다(부상자 명단은 그해 2월부터만 본다.
     * 지난 시즌 부상자 명단은 해제 기록 없이 끝나는 일이 많다).
     */
    private static Map<LocalDate, DayState> dayStates(SeasonReports.Input in) {
        LocalDate springStart = LocalDate.of(in.season(), 2, 1);
        List<Move> moves = in.moves().stream()
                .filter(m -> m.date() != null && !m.date().isAfter(in.lastRegularDay()))
                .sorted(Comparator.comparing(Move::date).thenComparingInt(m -> change(m).order))
                .toList();
        boolean injured = false;
        boolean rehab = false;
        boolean gone = false;
        int next = 0;
        Map<LocalDate, DayState> days = new HashMap<>();
        for (LocalDate day = in.openingDay(); !day.isAfter(in.lastRegularDay()); day = day.plusDays(1)) {
            while (next < moves.size() && !moves.get(next).date().isAfter(day)) {
                Move move = moves.get(next++);
                boolean counts = !move.date().isBefore(springStart);
                switch (change(move)) {
                    case ACTIVATED -> {
                        injured = false;
                        rehab = false;
                    }
                    case REHAB -> rehab = counts && injured;
                    case INJURED -> {
                        if (counts) {
                            // 새 등재는 새 부상이라 재활을 다시 센다(해제 기록이 없는 마이너 부상자 명단 뒤 재활 상태가
                            // 넘어오지 않게). 10일 → 60일 옮김은 같은 부상이라 재활을 그대로 둔다
                            if (InjuryParts.isPlacement(move.typeCode(), move.description())) {
                                rehab = false;
                            }
                            injured = true;
                        }
                    }
                    case JOINED, ASSIGNED -> gone = false;
                    case CALLED_UP -> {
                        gone = false;
                        injured = false;
                        rehab = false;
                    }
                    case RELEASED, FREE_AGENT -> {
                        gone = true;
                        injured = false;
                        rehab = false;
                    }
                    case DESIGNATED, OTHER -> {
                    }
                }
            }
            days.put(day, new DayState(injured, rehab, gone));
        }
        return days;
    }

    // ---- 칸 하나 ----

    private static Month month(Span span, boolean pitcher, Map<LocalDate, DayState> days, List<GameDay> majors,
                               List<GameDay> minors, Map<String, LeagueEnv> leagues, Scale scale) {
        Map<String, List<GameDay>> byStage = new HashMap<>();
        int rehabGames = 0;
        for (GameDay game : majors) {
            if (inSpan(game, span) && hasStat(game, pitcher)) {
                byStage.computeIfAbsent(MLB, k -> new ArrayList<>()).add(game);
            }
        }
        for (GameDay game : minors) {
            if (!inSpan(game, span) || !hasStat(game, pitcher)) {
                continue;
            }
            DayState state = days.get(game.date());
            if (state != null && state.injured() && state.rehab()) {
                rehabGames++;
            } else if (game.level() != null) {
                byStage.computeIfAbsent(game.level(), k -> new ArrayList<>()).add(game);
            }
        }
        String stage = byStage.entrySet().stream()
                .max(Comparator.comparingInt((Map.Entry<String, List<GameDay>> e) -> e.getValue().size())
                        .thenComparingInt(e -> stageRank(e.getKey())))
                .map(Map.Entry::getKey)
                .orElse(null);
        if (stage == null || rehabGames > byStage.get(stage).size()) {
            return new Month(span.month(), restKind(span, days, rehabGames), null, 0, null, null, null, null, false,
                    false);
        }
        List<GameDay> games = byStage.get(stage);
        boolean majorsStage = MLB.equals(stage);
        String levelKo = majorsStage ? null : MinorRecords.levelKo(stage);
        Month.Kind kind = majorsStage ? Month.Kind.MLB : Month.Kind.MINORS;
        LeagueEnv league = league(leagues, stage);
        if (pitcher) {
            int outs = games.stream().mapToInt(g -> orZero(g.pitching().pitchingOuts())).sum();
            int earnedRuns = games.stream().mapToInt(g -> orZero(g.pitching().earnedRuns())).sum();
            Double era = BaseballMath.era(earnedRuns, outs);
            Integer eraPlus = eraPlus(earnedRuns, outs, league, majorsStage ? scale.eraFactor() : 1);
            boolean hot = majorsStage && outs >= HOT_PITCHER_OUTS && eraPlus != null && eraPlus >= HOT_ERA_PLUS;
            return new Month(span.month(), kind, levelKo, games.size(), null, null, era, eraPlus,
                    games.size() < FEW_PITCHER_GAMES, hot);
        }
        int hits = games.stream().mapToInt(g -> orZero(g.batting().hits())).sum();
        int atBats = games.stream().mapToInt(g -> orZero(g.batting().atBats())).sum();
        Double avg = BaseballMath.avg(hits, atBats);
        Integer wrcPlus = wrcPlus(games, league, majorsStage ? scale.wrcShift() : 0);
        boolean hot = majorsStage && games.size() >= HOT_HITTER_GAMES && wrcPlus != null && wrcPlus >= HOT_WRC;
        return new Month(span.month(), kind, levelKo, games.size(), avg, wrcPlus, null, null,
                games.size() < FEW_HITTER_GAMES, hot);
    }

    /** ERA+(높을수록 잘함). 아웃이 없으면 null, 무자책이면 ERA_PLUS_CAP */
    static Integer eraPlus(int earnedRuns, int outs, LeagueEnv league, double factor) {
        if (outs == 0) {
            return null;
        }
        if (earnedRuns == 0) {
            return ERA_PLUS_CAP;
        }
        double value = EraMath.eraPlus(earnedRuns, outs, league) * factor;
        return (int) Math.min(ERA_PLUS_CAP, Math.round(value));
    }

    /** 뛴 기록이 없는 달: 재활 경기가 있었거나 부상자 명단 날이 팀 없는 날 이상이면 부상, 팀 없는 날이 더 많으면 FA */
    private static Month.Kind restKind(Span span, Map<LocalDate, DayState> days, int rehabGames) {
        int injured = 0;
        int gone = 0;
        for (LocalDate day = span.from(); !day.isAfter(span.to()); day = day.plusDays(1)) {
            DayState state = days.get(day);
            if (state == null) {
                continue;
            }
            if (state.injured()) {
                injured++;
            } else if (state.gone()) {
                gone++;
            }
        }
        if (rehabGames > 0 || (injured > 0 && injured >= gone)) {
            return Month.Kind.INJURED;
        }
        return gone > 0 ? Month.Kind.FREE_AGENT : Month.Kind.NONE;
    }

    private static boolean inSpan(GameDay game, Span span) {
        return game.date() != null && !game.date().isBefore(span.from()) && !game.date().isAfter(span.to());
    }

    private static boolean hasStat(GameDay game, boolean pitcher) {
        return pitcher ? game.pitching() != null && game.pitching().pitchingOuts() != null : game.batting() != null;
    }

    /** 메이저가 가장 높고, 마이너는 트리플A → 루키 순(SeasonReports와 같은 단계 순서) */
    private static int stageRank(String stage) {
        return MLB.equals(stage) ? Integer.MAX_VALUE : SeasonReports.levelRank(stage);
    }

    private static int orZero(Integer value) {
        return SeasonReports.orZero(value);
    }
}
