package com.korleaguer.api;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 이동 기록의 부상자 명단 등재 줄과, 설명 글 속 부상 부위를 한국어로 바꾸는 작은 사전(시즌 성적표의 이유 문장에 쓴다).
 * MLB 설명 글 예: "Atlanta Braves placed SS Ha-Seong Kim on the 10-day injured list. Right finger sprain."
 * 부위가 여럿이면 글에서 먼저 나온 것을 쓴다. 사전에 없으면 null이다.
 */
final class InjuryParts {

    private record Part(Pattern pattern, String korean) {
    }

    private static final List<Part> PARTS = List.of(
            part("quadriceps|quads?", "허벅지"),
            part("hamstrings?", "햄스트링"),
            part("obliques?|sides?", "옆구리"),
            part("fingers?", "손가락"),
            part("thumbs?", "엄지"),
            part("wrists?", "손목"),
            part("hands?", "손"),
            part("elbows?", "팔꿈치"),
            part("shoulders?", "어깨"),
            part("forearms?", "팔뚝"),
            part("back", "허리"),
            part("knees?", "무릎"),
            part("ankles?", "발목"),
            part("foot|feet", "발"),
            part("hips?", "고관절"),
            part("groin", "사타구니"),
            part("calf|calves", "종아리"),
            part("concussion", "뇌진탕"));

    private InjuryParts() {
    }

    private static Part part(String words, String korean) {
        return new Part(Pattern.compile("\\b(?:" + words + ")\\b"), korean);
    }

    /** 부상자 명단에 새로 올린 줄인지(옮김·해제는 아니다) */
    static boolean isPlacement(String typeCode, String description) {
        if (description == null || (typeCode != null && !"SC".equalsIgnoreCase(typeCode.trim()))) {
            return false;
        }
        String text = description.toLowerCase(Locale.ROOT);
        boolean list = text.contains("injured list") || text.contains("disabled list");
        return list && text.contains("placed") && !text.contains("transferred") && !text.contains("activated")
                && !text.contains("reinstated");
    }

    /** 설명 글 속 부상 부위(한국어). 없거나 사전에 없으면 null */
    static String korean(String description) {
        if (description == null) {
            return null;
        }
        String text = description.toLowerCase(Locale.ROOT);
        String found = null;
        int at = Integer.MAX_VALUE;
        for (Part part : PARTS) {
            Matcher matcher = part.pattern().matcher(text);
            if (matcher.find() && matcher.start() < at) {
                at = matcher.start();
                found = part.korean();
            }
        }
        return found;
    }
}
