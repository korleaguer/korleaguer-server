package com.korleaguer.support;

import java.util.Locale;

/**
 * MLB 이동 기록(transactions)의 종류(typeCode)를 짧은 한국어 이름으로 바꾼다.
 * <ul>
 *   <li>선수 상태 변경(SC)은 설명 글에서 부상자 명단·출장 정지만 골라 이름을 붙이고, 나머지는 null(화면에서 뺀다)이다.</li>
 *   <li>팀 배정(ASG)은 설명 글로 재활 경기·스프링캠프 초청을 가른다.</li>
 *   <li>표에 없는 종류는 영어 typeDesc를 그대로 쓴다. typeDesc에 "Signed"가 들어 있으면 "계약"이다.</li>
 * </ul>
 */
public final class MoveLabels {

    private MoveLabels() {
    }

    /** 팬에게 보일 이름. null이면 보여 주지 않는 줄이다. */
    public static String korean(String typeCode, String typeDesc, String description) {
        String code = typeCode == null ? "" : typeCode.trim().toUpperCase(Locale.ROOT);
        String text = description == null ? "" : description.toLowerCase(Locale.ROOT);
        return switch (code) {
            case "OPT" -> "마이너 강등(옵션)";
            // SE: 마이너 선수의 계약을 사 와서(Selected) 메이저 명단에 올린다.
            case "CU", "SE" -> "메이저 콜업";
            case "OUT" -> "마이너 이관(아웃라이트)";
            case "DES" -> "지명 할당(DFA)";
            case "TR" -> "트레이드";
            case "SFA", "SGN" -> "계약";
            // DFA는 MLB 코드에서 "Declared Free Agency"(선수가 FA를 선택)다. 지명 할당은 DES다.
            case "DFA" -> "FA 선언";
            case "REL" -> "방출";
            case "CLW" -> "웨이버 클레임";
            case "SU" -> suspension(text);
            case "ASG" -> assignment(text);
            case "SC" -> statusChange(text);
            default -> typeDesc != null && typeDesc.contains("Signed") ? "계약" : typeDesc;
        };
    }

    private static String statusChange(String text) {
        if (text.contains("injured list")) {
            if (text.contains("activated") || text.contains("reinstated")) {
                return "부상자 명단 해제";
            }
            return text.contains("transferred") ? "부상자 명단 이동" : "부상자 명단 등재";
        }
        if (text.contains("suspend")) {
            return suspension(text);
        }
        // "roster status changed", 그냥 "activated"(무슨 명단에서인지 모름) 등
        return null;
    }

    private static String suspension(String text) {
        return text.contains("reinstated") || text.contains("activated") ? "출장 정지 해제" : "출장 정지";
    }

    private static String assignment(String text) {
        if (text.contains("rehab assignment")) {
            return "재활 경기 배정";
        }
        if (text.contains("spring training")) {
            return "스프링캠프 초청";
        }
        return "팀 배정";
    }
}
