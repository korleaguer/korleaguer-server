package com.korleaguer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 경기 박스스코어(game/{gamePk}/boxscore)에서 쓰는 칸만. 경기 상태는 없으므로 일정에서 받는다.
 * players의 키는 "ID" + 선수 번호(예: ID808982)다. pitchers는 나온 순서대로의 투수 번호다(첫 번째가 선발).
 * battingOrder는 "100"~"900"이면 선발 타순, "301"처럼 끝이 00이 아니면 교체로 들어온 선수다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlbBoxscore(Teams teams) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Teams(Side away, Side home) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Side(MlbTeam team, TeamStats teamStats, List<Long> pitchers, Map<String, Player> players) {

        /** 이 편의 그 선수 줄. 없으면 null */
        public Player player(long personId) {
            return players == null ? null : players.get("ID" + personId);
        }

        /** 이 편 점수(teamStats.batting.runs). 없으면 null */
        public Integer runs() {
            return teamStats == null || teamStats.batting() == null ? null : teamStats.batting().runs();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TeamStats(MlbStat batting) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Player(MlbPerson person, String battingOrder, GameStatus gameStatus, Stats stats) {

        /** 타격 기록. 치지 않았으면 null */
        public MlbStat batting() {
            return stats == null || MlbStat.isEmpty(stats.batting()) ? null : stats.batting();
        }

        /** 투구 기록. 던지지 않았으면 null */
        public MlbStat pitching() {
            return stats == null || MlbStat.isEmpty(stats.pitching()) ? null : stats.pitching();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GameStatus(Boolean isSubstitute) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stats(MlbStat batting, MlbStat pitching) {
    }

    /** 홈·원정 두 편(없는 편은 뺀다) */
    public List<Side> sides() {
        if (teams == null) {
            return List.of();
        }
        return Stream.of(teams.away(), teams.home()).filter(Objects::nonNull).toList();
    }
}
