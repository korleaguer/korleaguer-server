package com.korleaguer.client;

import java.util.List;

/**
 * 선수의 한 시즌 마이너리그(트리플A ~ 루키) 경기별 기록(정규시즌).
 * people/{id}/stats?stats=gameLog&group=hitting,pitching&leagueListId=milb_all 한 번으로 받는다.
 * hitting은 타자로 나온 경기, pitching은 등판한 경기다. 투수가 타석에 선 경기는 둘 다에 있다.
 * 정규시즌(gameType R) 줄만 남긴다(isRegularSeason). 마이너 경기 줄의 경기 종류가 늘 REGULAR이고(이슈 #190) 마이너 경기 수·
 * 시즌 합계가 정규시즌만인 것이 이것에 기댄다.
 */
public record MlbMinorGameLog(List<MlbGameLogSplit> hitting, List<MlbGameLogSplit> pitching) {

    public static final MlbMinorGameLog NONE = new MlbMinorGameLog(List.of(), List.of());

    public MlbMinorGameLog {
        hitting = regularSeason(hitting);
        pitching = regularSeason(pitching);
    }

    /** 마이너(sport.id 11~16) 경기 수. 타격·투구를 따로 센다(홈 화면의 '주로 마이너' 판단에 쓴다). */
    public MlbMinorGames counts() {
        return new MlbMinorGames(count(hitting), count(pitching));
    }

    /** sport.id 11~16(트리플A ~ 루키, Level.fromSportId와 같은 범위)인 경기인지 */
    public static boolean isMinors(MlbGameLogSplit split) {
        Long id = split.sportId();
        return id != null && id >= 11 && id <= 16;
    }

    /**
     * 정규시즌 경기(gameType R)인지. gameType을 묻지 않으면 MLB가 정규시즌만 주지만(11-19 명단 6명 2024~2026 마이너 541줄 모두 R),
     * MLB 기본값이 바뀌거나 요청을 넓혀도 포스트시즌(F·D·L·W)·시범경기(S) 줄이 정규시즌으로 섞이지 않게 여기서 거른다.
     * 종류를 모르는 줄(gameType 없음)도 뺀다(메이저 경기 저장과 같은 규칙, SeasonType.fromGameType).
     */
    public static boolean isRegularSeason(MlbGameLogSplit split) {
        return "R".equals(split.gameType());
    }

    private static List<MlbGameLogSplit> regularSeason(List<MlbGameLogSplit> splits) {
        return splits == null ? List.of() : splits.stream().filter(MlbMinorGameLog::isRegularSeason).toList();
    }

    private static int count(List<MlbGameLogSplit> splits) {
        return (int) splits.stream().filter(MlbMinorGameLog::isMinors).count();
    }
}
