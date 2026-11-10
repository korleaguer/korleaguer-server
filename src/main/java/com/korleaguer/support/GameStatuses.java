package com.korleaguer.support;

import java.util.Locale;

/**
 * MLB 경기 상태(status.abstractGameState / codedGameState / detailedState)를 DB와 응답(GameLine.gameStatus)에 쓰는
 * 소문자 값으로 바꾸는 표. 웹과 GameSummary.notPlayed가 이 값을 쓴다.
 * <ul>
 *   <li>scheduled(경기 전), in_progress(경기 중), final(끝남)</li>
 *   <li>postponed(연기), canceled(취소), delayed(지연), suspended(중단)</li>
 *   <li>그 밖은 MLB detailedState를 소문자로(빈칸은 _)</li>
 * </ul>
 */
public final class GameStatuses {

    public static final String SCHEDULED = "scheduled";
    public static final String IN_PROGRESS = "in_progress";
    public static final String FINAL = "final";
    public static final String POSTPONED = "postponed";
    public static final String CANCELED = "canceled";
    public static final String DELAYED = "delayed";
    public static final String SUSPENDED = "suspended";

    private GameStatuses() {
    }

    public static String of(String abstractGameState, String codedGameState, String detailedState) {
        String detailed = lower(detailedState);
        // detailedState가 더 자세하다(예: abstract는 Final인데 detailed는 Postponed).
        if (detailed.contains("postponed")) {
            return POSTPONED;
        }
        if (detailed.contains("cancel")) {
            return CANCELED;
        }
        if (detailed.contains("suspended")) {
            return SUSPENDED;
        }
        if (detailed.contains("delay")) {
            return DELAYED;
        }
        if (detailed.equals("warmup") || detailed.equals("pre-game")) {
            return SCHEDULED;
        }
        switch (lower(abstractGameState)) {
            case "final":
                return FINAL;
            case "live":
                return IN_PROGRESS;
            case "preview":
                return SCHEDULED;
            default:
                break;
        }
        String coded = codedGameState == null ? "" : codedGameState.trim().toUpperCase(Locale.ROOT);
        return switch (coded) {
            case "F", "O" -> FINAL;
            case "I", "M" -> IN_PROGRESS;
            case "S", "P" -> SCHEDULED;
            case "D" -> POSTPONED;
            case "C" -> CANCELED;
            case "U", "T" -> SUSPENDED;
            default -> detailed.isEmpty() ? "unknown" : detailed.replace(' ', '_');
        };
    }

    private static String lower(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }
}
