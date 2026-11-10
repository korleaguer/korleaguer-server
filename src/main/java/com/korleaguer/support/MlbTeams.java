package com.korleaguer.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MLB 30개 구단의 MLB 공식 팀 번호(statsapi.mlb.com). 웹(web/src/lib/teams.ts)과 같은 표다.
 * 약어는 쓰는 곳마다 다른 것(CWS/CHW 등)도 함께 받는다. 번호 → 약어는 대표 약어 하나를 돌려준다.
 */
public final class MlbTeams {

    // 대표 약어(TeamNames의 한글 이름 표에 있는 약어)
    private static final Map<String, Long> CLUBS = Map.ofEntries(
            Map.entry("ARI", 109L), Map.entry("ATH", 133L), Map.entry("ATL", 144L), Map.entry("BAL", 110L),
            Map.entry("BOS", 111L), Map.entry("CHC", 112L), Map.entry("CWS", 145L), Map.entry("CIN", 113L),
            Map.entry("CLE", 114L), Map.entry("COL", 115L), Map.entry("DET", 116L), Map.entry("HOU", 117L),
            Map.entry("KC", 118L), Map.entry("LAA", 108L), Map.entry("LAD", 119L), Map.entry("MIA", 146L),
            Map.entry("MIL", 158L), Map.entry("MIN", 142L), Map.entry("NYM", 121L), Map.entry("NYY", 147L),
            Map.entry("PHI", 143L), Map.entry("PIT", 134L), Map.entry("SD", 135L), Map.entry("SF", 137L),
            Map.entry("SEA", 136L), Map.entry("STL", 138L), Map.entry("TB", 139L), Map.entry("TEX", 140L),
            Map.entry("TOR", 141L), Map.entry("WSH", 120L));

    // 다른 곳에서 쓰는 약어
    private static final Map<String, String> ALIASES = Map.of(
            "AZ", "ARI", "OAK", "ATH", "CHW", "CWS", "KCR", "KC", "SDP", "SD", "SFG", "SF", "TBR", "TB",
            "WSN", "WSH", "WAS", "WSH");

    private static final Map<Long, String> ABBREVIATION_BY_ID = new LinkedHashMap<>();

    static {
        CLUBS.forEach((abbreviation, id) -> ABBREVIATION_BY_ID.put(id, abbreviation));
    }

    private MlbTeams() {
    }

    /** 약어 → MLB 팀 번호. 모르는 약어면 null */
    public static Long id(String abbreviation) {
        if (abbreviation == null) {
            return null;
        }
        return CLUBS.get(ALIASES.getOrDefault(abbreviation, abbreviation));
    }

    /** MLB 팀 번호 → 대표 약어. MLB 구단이 아니면(마이너 팀 등) null */
    public static String abbreviation(Long mlbTeamId) {
        return mlbTeamId == null ? null : ABBREVIATION_BY_ID.get(mlbTeamId);
    }

    /** MLB 30개 구단 중 하나인지 */
    public static boolean isClub(Long mlbTeamId) {
        return abbreviation(mlbTeamId) != null;
    }
}
