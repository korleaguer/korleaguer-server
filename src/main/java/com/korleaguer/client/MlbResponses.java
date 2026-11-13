package com.korleaguer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** MLB Stats API 응답의 바깥 껍데기. 목록만 꺼내 쓴다. */
public final class MlbResponses {

    private MlbResponses() {
    }

    /** /api/v1/people */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record People(List<MlbPerson> people) {
    }

    /** /api/v1/transactions */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Transactions(List<MlbTransaction> transactions) {
    }

    /** /api/v1/teams/{id} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Teams(List<MlbTeam> teams) {
    }

    /** /api/v1/people/{id}/stats */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stats(List<StatGroup> stats) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatGroup(List<MlbGameLogSplit> splits) {
    }

    /** /api/v1/people/{id}/stats?stats=yearByYear · stats=sabermetrics, 리그 전체 /api/v1/stats?stats=season · sabermetrics */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeasonStats(List<SeasonStatGroup> stats) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeasonStatGroup(List<MlbSeasonSplit> splits) {
    }

    /** /api/v1/people/{id}/stats?stats=gameLog (묶음을 여러 개 물을 때). group은 hitting / pitching */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GameLogGroups(List<GameLogGroup> stats) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GameLogGroup(GroupRef group, List<MlbGameLogSplit> splits) {

        /** hitting / pitching. 없으면 null */
        public String groupName() {
            return group == null ? null : group.displayName();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GroupRef(String displayName) {
    }

    /** /api/v1/schedule */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Schedule(List<ScheduleDate> dates) {
    }

    /** /api/v1/seasons/{year}. 아직 발표되지 않은 해면 빈 목록이다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Seasons(List<MlbSeason> seasons) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScheduleDate(String date, List<MlbScheduleGame> games) {
    }
}
