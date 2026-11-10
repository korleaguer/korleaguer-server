package com.korleaguer.support;

import java.util.Map;

/** 포지션 이름. MLB 주 포지션 약어를 영어 이름으로, 영어 이름을 한글로 바꾼다. 표에 없으면 받은 값을 그대로 쓴다. */
public final class PositionNames {

    private static final Map<String, String> KOREAN = Map.ofEntries(
            Map.entry("Pitcher", "투수"),
            Map.entry("Starting Pitcher", "선발투수"),
            Map.entry("Relief Pitcher", "구원투수"),
            Map.entry("Catcher", "포수"),
            Map.entry("First Baseman", "1루수"),
            Map.entry("Second Baseman", "2루수"),
            Map.entry("Third Baseman", "3루수"),
            Map.entry("Shortstop", "유격수"),
            Map.entry("Infielder", "내야수"),
            Map.entry("Left Fielder", "좌익수"),
            Map.entry("Center Fielder", "중견수"),
            Map.entry("Right Fielder", "우익수"),
            Map.entry("Outfielder", "외야수"),
            Map.entry("Designated Hitter", "지명타자"),
            Map.entry("Two-Way Player", "투타겸업"));

    // MLB primaryPosition.abbreviation → 영어 이름(KOREAN 표의 키)
    private static final Map<String, String> ENGLISH_BY_MLB = Map.ofEntries(
            Map.entry("P", "Pitcher"),
            Map.entry("SP", "Starting Pitcher"),
            Map.entry("RP", "Relief Pitcher"),
            Map.entry("C", "Catcher"),
            Map.entry("1B", "First Baseman"),
            Map.entry("2B", "Second Baseman"),
            Map.entry("3B", "Third Baseman"),
            Map.entry("SS", "Shortstop"),
            Map.entry("IF", "Infielder"),
            Map.entry("LF", "Left Fielder"),
            Map.entry("CF", "Center Fielder"),
            Map.entry("RF", "Right Fielder"),
            Map.entry("OF", "Outfielder"),
            Map.entry("DH", "Designated Hitter"),
            Map.entry("TWP", "Two-Way Player"));

    private PositionNames() {
    }

    /** MLB 주 포지션(약어, 이름) → 영어 이름. 예: (CF, Outfielder) → Center Fielder. 약어를 모르면 MLB 이름 그대로 */
    public static String fromMlb(String abbreviation, String name) {
        if (abbreviation == null) {
            return name;
        }
        return ENGLISH_BY_MLB.getOrDefault(abbreviation, name);
    }

    public static String korean(String position) {
        if (position == null) {
            return null;
        }
        return KOREAN.getOrDefault(position, position);
    }
}
