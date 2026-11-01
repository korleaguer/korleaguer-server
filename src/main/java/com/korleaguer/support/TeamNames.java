package com.korleaguer.support;

import java.util.Map;

/** MLB 30개 팀 한글 이름. 약어 기준이며, 쓰는 곳마다 다른 약어(CWS/CHW 등)도 함께 둔다. */
public final class TeamNames {

    private static final Map<String, String> KOREAN = Map.ofEntries(
            Map.entry("ARI", "애리조나"), Map.entry("AZ", "애리조나"),
            Map.entry("ATL", "애틀랜타"),
            Map.entry("BAL", "볼티모어"),
            Map.entry("BOS", "보스턴"),
            Map.entry("CHC", "시카고 컵스"),
            Map.entry("CWS", "시카고 화이트삭스"), Map.entry("CHW", "시카고 화이트삭스"),
            Map.entry("CIN", "신시내티"),
            Map.entry("CLE", "클리블랜드"),
            Map.entry("COL", "콜로라도"),
            Map.entry("DET", "디트로이트"),
            Map.entry("HOU", "휴스턴"),
            Map.entry("KC", "캔자스시티"), Map.entry("KCR", "캔자스시티"),
            Map.entry("LAA", "LA 에인절스"),
            Map.entry("LAD", "LA 다저스"),
            Map.entry("MIA", "마이애미"),
            Map.entry("MIL", "밀워키"),
            Map.entry("MIN", "미네소타"),
            Map.entry("NYM", "뉴욕 메츠"),
            Map.entry("NYY", "뉴욕 양키스"),
            Map.entry("ATH", "애슬레틱스"), Map.entry("OAK", "애슬레틱스"),
            Map.entry("PHI", "필라델피아"),
            Map.entry("PIT", "피츠버그"),
            Map.entry("SD", "샌디에이고"), Map.entry("SDP", "샌디에이고"),
            Map.entry("SF", "샌프란시스코"), Map.entry("SFG", "샌프란시스코"),
            Map.entry("SEA", "시애틀"),
            Map.entry("STL", "세인트루이스"),
            Map.entry("TB", "탬파베이"), Map.entry("TBR", "탬파베이"),
            Map.entry("TEX", "텍사스"),
            Map.entry("TOR", "토론토"),
            Map.entry("WSH", "워싱턴"), Map.entry("WSN", "워싱턴"), Map.entry("WAS", "워싱턴"));

    private TeamNames() {
    }

    /** 표에 없는 약어면 영어 이름을 그대로 쓴다. */
    public static String korean(String abbreviation, String fallback) {
        if (abbreviation == null) {
            return fallback;
        }
        return KOREAN.getOrDefault(abbreviation, fallback);
    }
}
