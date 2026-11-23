package com.korleaguer.support;

import java.util.Locale;

/**
 * "타격/투구"(예: Left/Right, L/R, Switch/Right. MLB batSide·pitchHand를 이어 붙인 값)를 한국식 "우투좌타"(투구 먼저)로 바꾼다.
 * 읽을 수 없으면 null.
 */
public final class BatsThrows {

    private BatsThrows() {
    }

    /** Left/Right → 우투좌타, Switch/Right → 우투양타. 투구에는 양쪽(Switch)이 없다. */
    public static String korean(String batsThrows) {
        if (batsThrows == null) {
            return null;
        }
        String[] parts = batsThrows.split("/", -1);
        if (parts.length != 2) {
            return null;
        }
        String batting = batting(parts[0]);
        String throwing = side(parts[1]);
        if (batting == null || throwing == null) {
            return null;
        }
        return throwing + "투" + batting + "타";
    }

    private static String batting(String text) {
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "s", "switch", "b", "both" -> "양";
            default -> side(text);
        };
    }

    private static String side(String text) {
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "r", "right" -> "우";
            case "l", "left" -> "좌";
            default -> null;
        };
    }
}
