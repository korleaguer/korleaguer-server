package com.korleaguer.support;

import com.korleaguer.domain.Batting;
import com.korleaguer.domain.Pitching;
import com.korleaguer.domain.Role;

import java.util.ArrayList;
import java.util.List;

/**
 * 경기 한 줄 기록(summary) 문장을 만든다.
 * 타자: "4타수 2안타(2루타 1) 1홈런 2타점 1볼넷"
 * 투수: "6이닝 7피안타 2실점(2자책) 7탈삼진 1볼넷 · 승"
 */
public final class GameSummary {

    public static final String ABSENT = "결장";
    private static final String APPEARED = "출전";

    private GameSummary() {
    }

    public static String played(Role role, Batting batting, Pitching pitching) {
        return role == Role.PITCHER ? pitching(pitching) : hitting(batting);
    }

    /** 선수 기록이 없는 경기. 팀은 뛰었는데(final) 기록이 없으면 결장이다. */
    public static String notPlayed(String statusState, boolean statsAvailable) {
        return switch (statusState == null ? "unknown" : statusState) {
            case "final" -> statsAvailable ? ABSENT : "기록 없음";
            case "scheduled" -> "경기 전";
            case "in_progress" -> "경기 중";
            case "delayed" -> "지연";
            case "postponed" -> "연기";
            case "canceled" -> "취소";
            case "suspended", "abandoned" -> "중단";
            default -> "기록 없음";
        };
    }

    public static String hitting(Batting b) {
        if (b == null) {
            return APPEARED;
        }
        List<String> parts = new ArrayList<>();
        int atBats = orZero(b.atBats());
        int hits = orZero(b.hits());
        if (atBats > 0 || hits > 0) {
            String hitText = hits == 0 ? "무안타" : hits + "안타" + extraBaseHits(b);
            parts.add(atBats + "타수 " + hitText);
        }
        addIfPositive(parts, b.homeRuns(), "홈런");
        addIfPositive(parts, b.rbi(), "타점");
        addIfPositive(parts, b.walks(), "볼넷");
        addIfPositive(parts, b.stolenBases(), "도루");
        return parts.isEmpty() ? APPEARED : String.join(" ", parts);
    }

    public static String pitching(Pitching p) {
        if (p == null || p.pitchingOuts() == null) {
            return APPEARED;
        }
        List<String> parts = new ArrayList<>();
        parts.add(BaseballMath.inningsText(p.pitchingOuts()) + "이닝");
        int hits = orZero(p.hitsAllowed());
        parts.add(hits == 0 ? "무피안타" : hits + "피안타");
        int runs = orZero(p.runsAllowed());
        parts.add(runs == 0 ? "무실점" : runs + "실점(" + orZero(p.earnedRuns()) + "자책)");
        addIfPositive(parts, p.pitcherStrikeouts(), "탈삼진");
        addIfPositive(parts, p.walksAllowed(), "볼넷");

        String text = String.join(" ", parts);
        String decision = decision(p);
        return decision == null ? text : text + " · " + decision;
    }

    // 안타 중 2루타·3루타만 괄호로 적는다. 홈런은 따로 "1홈런"으로 쓴다.
    private static String extraBaseHits(Batting b) {
        List<String> parts = new ArrayList<>();
        if (orZero(b.doubles()) > 0) {
            parts.add("2루타 " + b.doubles());
        }
        if (orZero(b.triples()) > 0) {
            parts.add("3루타 " + b.triples());
        }
        return parts.isEmpty() ? "" : "(" + String.join(", ", parts) + ")";
    }

    private static String decision(Pitching p) {
        if (orZero(p.wins()) > 0) {
            return "승";
        }
        if (orZero(p.losses()) > 0) {
            return "패";
        }
        if (orZero(p.saves()) > 0) {
            return "세";
        }
        return null;
    }

    private static void addIfPositive(List<String> parts, Integer value, String label) {
        if (orZero(value) > 0) {
            parts.add(value + label);
        }
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
