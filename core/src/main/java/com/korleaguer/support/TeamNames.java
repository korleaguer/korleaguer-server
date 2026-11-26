package com.korleaguer.support;

import java.util.List;
import java.util.Map;

/** MLB 30개 팀 한글 이름. 대표 약어 기준이며, 쓰는 곳마다 다른 약어(CHW 등)는 MlbTeams.canonical로 대표 약어로 바꿔 찾는다. */
public final class TeamNames {

    private static final Map<String, String> KOREAN = Map.ofEntries(
            Map.entry("ARI", "애리조나"),
            Map.entry("ATL", "애틀랜타"),
            Map.entry("BAL", "볼티모어"),
            Map.entry("BOS", "보스턴"),
            Map.entry("CHC", "시카고 컵스"),
            Map.entry("CWS", "시카고 화이트삭스"),
            Map.entry("CIN", "신시내티"),
            Map.entry("CLE", "클리블랜드"),
            Map.entry("COL", "콜로라도"),
            Map.entry("DET", "디트로이트"),
            Map.entry("HOU", "휴스턴"),
            Map.entry("KC", "캔자스시티"),
            Map.entry("LAA", "LA 에인절스"),
            Map.entry("LAD", "LA 다저스"),
            Map.entry("MIA", "마이애미"),
            Map.entry("MIL", "밀워키"),
            Map.entry("MIN", "미네소타"),
            Map.entry("NYM", "뉴욕 메츠"),
            Map.entry("NYY", "뉴욕 양키스"),
            Map.entry("ATH", "애슬레틱스"),
            Map.entry("PHI", "필라델피아"),
            Map.entry("PIT", "피츠버그"),
            Map.entry("SD", "샌디에이고"),
            Map.entry("SF", "샌프란시스코"),
            Map.entry("SEA", "시애틀"),
            Map.entry("STL", "세인트루이스"),
            Map.entry("TB", "탬파베이"),
            Map.entry("TEX", "텍사스"),
            Map.entry("TOR", "토론토"),
            Map.entry("WSH", "워싱턴"));

    /**
     * 기사 글에서 그 구단을 찾을 때 쓰는 한글 이름(긴 것부터). 도시만으로 두 구단이 갈리는 곳(뉴욕·LA·시카고)은 도시를 넣지 않는다.
     * KBO 구단과 겹치는 별명(자이언츠·트윈스·타이거스)과 사람 이름과 겹치는 도시(워싱턴: 조니 워싱턴 코치 등)는 혼자 쓰지 않는다.
     */
    private static final Map<String, List<String>> SEARCH = searchNames();

    private TeamNames() {
    }

    private static Map<String, List<String>> searchNames() {
        Map<String, List<String>> m = new java.util.HashMap<>();
        put(m, "ARI", "애리조나 다이아몬드백스", "애리조나", "다이아몬드백스");
        put(m, "ATL", "애틀랜타 브레이브스", "애틀랜타", "브레이브스");
        put(m, "BAL", "볼티모어 오리올스", "볼티모어", "오리올스");
        put(m, "BOS", "보스턴 레드삭스", "보스턴", "레드삭스");
        put(m, "CHC", "시카고 컵스", "컵스");
        put(m, "CWS", "시카고 화이트삭스", "화이트삭스");
        put(m, "CIN", "신시내티 레즈", "신시내티");
        put(m, "CLE", "클리블랜드 가디언스", "클리블랜드", "가디언스");
        put(m, "COL", "콜로라도 로키스", "콜로라도", "로키스");
        put(m, "DET", "디트로이트 타이거스", "디트로이트");
        put(m, "HOU", "휴스턴 애스트로스", "휴스턴", "애스트로스");
        put(m, "KC", "캔자스시티 로열스", "캔자스시티", "로열스");
        put(m, "LAA", "LA 에인절스", "로스앤젤레스 에인절스", "에인절스", "엔젤스");
        put(m, "LAD", "LA 다저스", "로스앤젤레스 다저스", "다저스");
        put(m, "MIA", "마이애미 말린스", "마이애미", "말린스");
        put(m, "MIL", "밀워키 브루어스", "밀워키", "브루어스");
        put(m, "MIN", "미네소타 트윈스", "미네소타");
        put(m, "NYM", "뉴욕 메츠", "메츠");
        put(m, "NYY", "뉴욕 양키스", "양키스");
        put(m, "ATH", "오클랜드 애슬레틱스", "애슬레틱스", "오클랜드");
        put(m, "PHI", "필라델피아 필리스", "필라델피아", "필리스");
        put(m, "PIT", "피츠버그 파이리츠", "피츠버그 파이어리츠", "피츠버그", "파이리츠", "파이어리츠");
        put(m, "SD", "샌디에이고 파드리스", "샌디에이고", "파드리스");
        put(m, "SF", "샌프란시스코 자이언츠", "샌프란시스코");
        put(m, "SEA", "시애틀 매리너스", "시애틀", "매리너스");
        put(m, "STL", "세인트루이스 카디널스", "세인트루이스", "카디널스");
        put(m, "TB", "탬파베이 레이스", "탬파베이");
        put(m, "TEX", "텍사스 레인저스", "텍사스", "레인저스");
        put(m, "TOR", "토론토 블루제이스", "토론토", "블루제이스");
        put(m, "WSH", "워싱턴 내셔널스", "내셔널스");
        return Map.copyOf(m);
    }

    private static void put(Map<String, List<String>> m, String abbreviation, String... names) {
        m.put(abbreviation, List.of(names));
    }

    /** 표에 없는 약어면 영어 이름을 그대로 쓴다. */
    public static String korean(String abbreviation, String fallback) {
        if (abbreviation == null) {
            return fallback;
        }
        return KOREAN.getOrDefault(clubKey(abbreviation), fallback);
    }

    /** 글에서 그 구단을 찾을 이름들(긴 것부터). 표에 없는 약어면 빈 목록 */
    public static List<String> searchNames(String abbreviation) {
        return abbreviation == null ? List.of() : SEARCH.getOrDefault(clubKey(abbreviation), List.of());
    }

    /** 다른 약어(SFG 등)는 대표 약어로. MLB 구단 약어가 아니면 그대로 */
    private static String clubKey(String abbreviation) {
        String canonical = MlbTeams.canonical(abbreviation);
        return canonical == null ? abbreviation : canonical;
    }
}
