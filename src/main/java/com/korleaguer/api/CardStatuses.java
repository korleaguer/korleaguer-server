package com.korleaguer.api;

import com.korleaguer.domain.Level;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerMove;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * '내 선수' 카드의 상태 배지를 이동 기록과 선수 정보로 정한다(이슈 #33). 오늘(미국 날짜) 지금 상태만 본다.
 * <ol>
 *   <li>FA(선수 정보의 freeAgent, FreeAgency가 정함)가 가장 먼저다. since는 마지막 FA 선언·방출 날.</li>
 *   <li>정규시즌이 끝났고, 메이저 팀이 더 경기하지 않으며(포스트시즌이 아니며), 본인도 3일 넘게 경기가 없으면 시즌 끝.</li>
 *   <li>그 밖에는 이동 기록을 최신부터 보며, 지금 상태를 정하는 첫 기록으로 정한다(decide).
 *       등번호·트레이드·계약·클레임처럼 상태를 정하지 않는 기록은 건너뛴다.</li>
 *   <li>메이저인지 마이너인지는 선수 정보의 지금 단계(level)를 믿는다. 이동 기록은 '언제부터'를 정하는 데 쓴다.</li>
 *   <li>복귀·콜업·강등이 이번 정규시즌 개막 전이면(지난 시즌·스프링캠프) 배지도 '복귀 후'·'콜업 후'·'강등 후'도 없다.</li>
 * </ol>
 */
final class CardStatuses {

    /** 부상 복귀·콜업 배지를 보이는 날 수(그날 포함) */
    static final int RECENT_DAYS = 7;
    /** 정규시즌이 끝난 뒤 이만큼 경기가 없으면 시즌 끝으로 본다(포스트시즌 중인 선수는 남긴다) */
    static final int SEASON_OVER_AFTER_DAYS = 3;

    private static final Set<String> BECOMES_FREE_AGENT = Set.of("DFA", "REL");
    /** 다른 팀으로 옮기거나 팀을 떠나는 기록(트레이드·웨이버 클레임·계약·방출·FA 선언). 그 전의 지명 할당은 끝난 일이다 */
    private static final Set<String> CHANGES_TEAM = Set.of("TR", "CLW", "SFA", "SGN", "REL", "DFA");
    private static final Set<String> CALL_UP = Set.of("CU", "SE");
    private static final Pattern RETROACTIVE = Pattern.compile("retroactive to ([A-Z][a-z]+ \\d{1,2}, \\d{4})");
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);

    private CardStatuses() {
    }

    /** 마지막 경기(미국 날짜와 단계: MLB 또는 마이너 약어) */
    record LastGame(LocalDate date, String level) {
    }

    /** 지금 마이너 팀(마지막 마이너 경기의 단계 약어와 팀 이름) */
    record MinorSpot(String level, String teamName) {
    }

    /** 지금 단계의 기간이 무엇으로 시작됐나: 복귀(부상자 명단·출장 정지에서), 콜업, 강등(메이저에서 마이너로), 그 밖 */
    enum Start {
        RETURNED,
        CALLED_UP,
        SENT_DOWN,
        OTHER
    }

    /**
     * 정한 결과. status는 배지(평소대로면 null), stintStart는 지금 단계(메이저·마이너)에서 뛴 기간이 시작된 날
     * (출전 줄·소식을 그날 뒤 경기로만 센다, 모르면 null), start는 그 기간이 무엇으로 시작됐는지다.
     */
    record Resolved(MyPlayersResponse.CardStatus status, LocalDate stintStart, Start start) {
    }

    /**
     * 판단 기준 날짜와 시즌 사정.
     *
     * @param usDate      오늘(미국 날짜)
     * @param seasonStart 이번 시즌 정규시즌 개막일(미국 날짜)
     * @param seasonEnded 이번 시즌 정규시즌이 끝났는지
     * @param teamPlaying 지금 메이저 팀이 아직 경기하는지(최근 3일 안에 경기했거나 앞으로 경기가 잡혀 있음: 포스트시즌 등)
     */
    record Today(LocalDate usDate, LocalDate seasonStart, boolean seasonEnded, boolean teamPlaying) {
    }

    /**
     * @param allMovesNewestFirst 이동 기록(최신부터)
     * @param lastGame            본인 마지막 경기(메이저·마이너 중 늦은 것, 없으면 null)
     * @param minor               지금 마이너 팀(마이너 경기가 없으면 null)
     */
    static Resolved resolve(Player player, List<PlayerMove> allMovesNewestFirst, LastGame lastGame, MinorSpot minor,
                            Today today) {
        LocalDate usToday = today.usDate();
        // 오늘 뒤 날짜로 잡힌 기록(예정된 이동)은 아직 일어나지 않은 일이라 보지 않는다.
        List<PlayerMove> movesNewestFirst = allMovesNewestFirst.stream()
                .filter(m -> m.getMoveDate() == null || !m.getMoveDate().isAfter(usToday))
                .toList();
        if (player.isFreeAgent()) {
            LocalDate since = movesNewestFirst.stream()
                    .filter(m -> BECOMES_FREE_AGENT.contains(code(m)))
                    .map(PlayerMove::getMoveDate)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            return new Resolved(status("FA", since, usToday, null, null, lastGame), null, Start.OTHER);
        }
        // 포스트시즌 명단에 든 선수는 본인 경기가 며칠 없어도(팀이 쉬는 날·첫 출전 전) 시즌 끝이 아니다.
        if (today.seasonEnded() && !today.teamPlaying() && (lastGame == null
                || lastGame.date().isBefore(usToday.minusDays(SEASON_OVER_AFTER_DAYS)))) {
            return new Resolved(status("SEASON_OVER", null, usToday, null, null, lastGame), null, Start.OTHER);
        }

        Decision decision = decide(movesNewestFirst);
        String minorLevel = minor == null ? null : minor.level();
        String minorTeam = minor != null && minor.teamName() != null ? minor.teamName() : player.getLevelTeamName();
        switch (decision.kind()) {
            case INJURED -> {
                boolean inMinors = decision.minors() || player.getLevel() == Level.MINORS;
                return new Resolved(status(inMinors ? "MINORS_IL" : "IL", decision.date(), usToday,
                        inMinors ? minorLevel : null, inMinors ? minorTeam : null, null), null, Start.OTHER);
            }
            case SUSPENDED -> {
                return new Resolved(status("SUSPENDED", decision.date(), usToday, null, null, null), null, Start.OTHER);
            }
            case DESIGNATED -> {
                return new Resolved(status("DFA", decision.date(), usToday, null, null, null), null, Start.OTHER);
            }
            default -> {
                // 아래에서 단계로 정한다
            }
        }

        if (player.getLevel() == Level.MINORS) {
            // 마이너 팀의 '활성화'처럼 강등 뒤에 붙는 기록이 더 최신일 수 있어, 강등 기록을 따로 찾는다.
            PlayerMove sentDown = sentDownMove(movesNewestFirst);
            LocalDate since = decision.kind() == Kind.SENT_DOWN ? decision.date()
                    : sentDown == null ? null : sentDown.getMoveDate();
            boolean fromMajors = decision.kind() == Kind.SENT_DOWN ? decision.fromMajors()
                    : sentDown != null && Set.of("OPT", "OUT").contains(code(sentDown));
            MyPlayersResponse.CardStatus badge = status("MINORS", since, usToday, minorLevel, minorTeam, null);
            // 개막 전(지난 시즌·겨울)에 내려갔으면 이번 시즌 마이너 경기 전부가 한 기간이다.
            if (since == null || since.isBefore(today.seasonStart())) {
                return new Resolved(badge, null, Start.OTHER);
            }
            return new Resolved(badge, since, fromMajors ? Start.SENT_DOWN : Start.OTHER);
        }
        // 개막 전(지난 시즌·스프링캠프)의 복귀·콜업은 이번 시즌 이야기가 아니다.
        if (decision.date() == null || decision.date().isBefore(today.seasonStart())) {
            return new Resolved(null, null, Start.OTHER);
        }
        if (decision.kind() == Kind.RETURNED || decision.kind() == Kind.ACTIVATED) {
            MyPlayersResponse.CardStatus badge = decision.kind() == Kind.RETURNED && recent(decision.date(), usToday)
                    ? status("RETURNED", decision.date(), usToday, null, null, null) : null;
            return new Resolved(badge, decision.date(), Start.RETURNED);
        }
        if (decision.kind() == Kind.CALLED_UP) {
            MyPlayersResponse.CardStatus badge = recent(decision.date(), usToday)
                    ? status("CALLED_UP", decision.date(), usToday, null, null, null) : null;
            return new Resolved(badge, decision.date(), Start.CALLED_UP);
        }
        return new Resolved(null, null, Start.OTHER);
    }

    private enum Kind {
        INJURED,
        RETURNED,
        ACTIVATED,
        SUSPENDED,
        DESIGNATED,
        CALLED_UP,
        SENT_DOWN,
        NONE
    }

    /** minors: 마이너 팀의 부상자 명단, fromMajors: 메이저에서 마이너로 내려간 강등(옵션·아웃라이트) */
    private record Decision(Kind kind, LocalDate date, boolean minors, boolean fromMajors) {

        static final Decision NONE = new Decision(Kind.NONE, null, false, false);

        static Decision of(Kind kind, LocalDate date) {
            return new Decision(kind, date, false, false);
        }
    }

    /**
     * 지금 상태를 정하는 첫 기록(최신부터).
     * <ul>
     *   <li>부상자 명단(SC, 'injured list'): 해제면 복귀, 등재·이동이면 부상(이동이면 처음 오른 날까지 거슬러 간다).
     *       마이너 팀 기록이면 마이너 부상자 명단이다(메이저 뇌진탕 7일 명단은 메이저다).</li>
     *   <li>그 밖의 활성화(SC 'activated'·'reinstated', 출장 정지 해제): 다시 뛸 수 있게 됨. 메이저·마이너 팀이 아닌 곳
     *       (국가대표 등, 단계 없음)의 활성화는 건너뛰고, 같은 날 콜업이 있으면 콜업으로 본다(콜업 때 '활성화'가 함께 붙는다).</li>
     *   <li>출장 정지(SU), 지명 할당(DES), 콜업(CU)·계약 선택(SE), 강등(OPT 옵션·OUT 아웃라이트·재활 아닌 마이너 배정 ASG)</li>
     *   <li>지명 할당보다 새로운 트레이드·클레임·계약·방출이 있으면 지명 할당은 끝났다(새 팀에서 평소대로).</li>
     *   <li>재활 경기 배정(ASG 'rehab')은 부상 중이므로 건너뛰고 더 오래된 부상자 명단 기록을 본다.</li>
     * </ul>
     */
    private static Decision decide(List<PlayerMove> movesNewestFirst) {
        boolean changedTeam = false;
        for (int i = 0; i < movesNewestFirst.size(); i++) {
            PlayerMove move = movesNewestFirst.get(i);
            String text = text(move);
            LocalDate date = move.getMoveDate();
            switch (code(move)) {
                case "SC" -> {
                    if (text.contains("injured list")) {
                        boolean minors = move.getToLevel() == Level.MINORS;
                        if (text.contains("activated") || text.contains("reinstated")) {
                            return new Decision(Kind.RETURNED, date, minors, false);
                        }
                        return new Decision(Kind.INJURED, placementDate(movesNewestFirst, i), minors, false);
                    }
                    if ((text.contains("activated") || text.contains("reinstated")) && move.getToLevel() != null) {
                        return Decision.of(calledUpOn(movesNewestFirst, date) ? Kind.CALLED_UP : Kind.ACTIVATED, date);
                    }
                }
                case "SU" -> {
                    return Decision.of(text.contains("reinstated") ? Kind.ACTIVATED : Kind.SUSPENDED, date);
                }
                case "DES" -> {
                    if (changedTeam) {
                        return Decision.NONE;
                    }
                    return Decision.of(Kind.DESIGNATED, date);
                }
                case "CU", "SE" -> {
                    return Decision.of(Kind.CALLED_UP, date);
                }
                case "OPT", "OUT" -> {
                    return new Decision(Kind.SENT_DOWN, date, false, true);
                }
                case "ASG" -> {
                    if (!text.contains("rehab") && move.getToLevel() == Level.MINORS) {
                        return new Decision(Kind.SENT_DOWN, date, false, false);
                    }
                }
                default -> {
                    // 등번호·트레이드·계약·클레임·FA 선언·방출(그 뒤 계약함): 다음 기록을 본다
                    if (CHANGES_TEAM.contains(code(move))) {
                        changedTeam = true;
                    }
                }
            }
        }
        return Decision.NONE;
    }

    /** 그날 콜업(CU)·계약 선택(SE) 기록이 있나 */
    private static boolean calledUpOn(List<PlayerMove> movesNewestFirst, LocalDate date) {
        return date != null && movesNewestFirst.stream()
                .anyMatch(m -> date.equals(m.getMoveDate()) && CALL_UP.contains(code(m)));
    }

    /**
     * 부상자 명단에 처음 오른 날. 이동(10일 → 60일 등)이면 그 전 등재 기록까지 거슬러 간다(사이에 해제가 있으면 멈춤).
     * 소급 등재("retroactive to May 19, 2026")면 소급한 날이다.
     */
    private static LocalDate placementDate(List<PlayerMove> movesNewestFirst, int index) {
        PlayerMove move = movesNewestFirst.get(index);
        if (text(move).contains("transferred")) {
            for (int j = index + 1; j < movesNewestFirst.size(); j++) {
                PlayerMove older = movesNewestFirst.get(j);
                String text = text(older);
                if (!"SC".equals(code(older)) || !text.contains("injured list")) {
                    continue;
                }
                if (text.contains("activated") || text.contains("reinstated")) {
                    break;
                }
                if (text.contains("placed")) {
                    return retroactiveOr(older);
                }
            }
        }
        return retroactiveOr(move);
    }

    private static LocalDate retroactiveOr(PlayerMove move) {
        Matcher matcher = RETROACTIVE.matcher(move.getDescription() == null ? "" : move.getDescription());
        if (matcher.find()) {
            try {
                return LocalDate.parse(matcher.group(1), LONG_DATE);
            } catch (DateTimeParseException e) {
                return move.getMoveDate();
            }
        }
        return move.getMoveDate();
    }

    /** 마이너로 내려간 마지막 기록(옵션·아웃라이트·재활 아닌 마이너 배정). 없으면 null */
    private static PlayerMove sentDownMove(List<PlayerMove> movesNewestFirst) {
        return movesNewestFirst.stream()
                .filter(m -> m.getMoveDate() != null)
                .filter(m -> Set.of("OPT", "OUT").contains(code(m))
                        || ("ASG".equals(code(m)) && !text(m).contains("rehab") && m.getToLevel() == Level.MINORS))
                .findFirst()
                .orElse(null);
    }

    private static boolean recent(LocalDate since, LocalDate usToday) {
        return since != null && !since.isAfter(usToday) && daysSince(since, usToday) <= RECENT_DAYS;
    }

    /** since부터 오늘까지 며칠째(그날이 1일째) */
    static int daysSince(LocalDate since, LocalDate usToday) {
        return (int) ChronoUnit.DAYS.between(since, usToday) + 1;
    }

    private static MyPlayersResponse.CardStatus status(String kind, LocalDate since, LocalDate usToday, String level,
                                                       String teamName, LastGame lastGame) {
        Integer days = since == null || since.isAfter(usToday) ? null : daysSince(since, usToday);
        return new MyPlayersResponse.CardStatus(kind, since, days, level, teamName,
                lastGame == null ? null : lastGame.date(), lastGame == null ? null : lastGame.level());
    }

    private static String code(PlayerMove move) {
        return move.getTypeCode() == null ? "" : move.getTypeCode().trim().toUpperCase(Locale.ROOT);
    }

    private static String text(PlayerMove move) {
        return move.getDescription() == null ? "" : move.getDescription().toLowerCase(Locale.ROOT);
    }
}
