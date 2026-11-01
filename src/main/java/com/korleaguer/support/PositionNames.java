package com.korleaguer.support;

import java.util.Map;

/** balldontlie position 값의 한글 이름. 표에 없으면 영어 원문을 그대로 쓴다. */
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

    private PositionNames() {
    }

    public static String korean(String position) {
        if (position == null) {
            return null;
        }
        return KOREAN.getOrDefault(position, position);
    }
}
