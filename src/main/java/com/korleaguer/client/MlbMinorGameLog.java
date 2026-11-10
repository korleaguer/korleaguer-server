package com.korleaguer.client;

import java.util.List;

/**
 * 선수의 한 시즌 마이너리그(트리플A ~ 루키) 경기별 기록(정규시즌).
 * people/{id}/stats?stats=gameLog&group=hitting,pitching&leagueListId=milb_all 한 번으로 받는다.
 * hitting은 타자로 나온 경기, pitching은 등판한 경기다. 투수가 타석에 선 경기는 둘 다에 있다.
 */
public record MlbMinorGameLog(List<MlbGameLogSplit> hitting, List<MlbGameLogSplit> pitching) {

    public static final MlbMinorGameLog NONE = new MlbMinorGameLog(List.of(), List.of());

    public MlbMinorGameLog {
        hitting = hitting == null ? List.of() : List.copyOf(hitting);
        pitching = pitching == null ? List.of() : List.copyOf(pitching);
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

    private static int count(List<MlbGameLogSplit> splits) {
        return (int) splits.stream().filter(MlbMinorGameLog::isMinors).count();
    }
}
