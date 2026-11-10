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

    /** /api/v1/people/{id}/stats?stats=season (묶음을 여러 개 물을 때). group은 hitting / pitching */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeasonStats(List<SeasonGroup> stats) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeasonGroup(GroupRef group, List<SeasonSplit> splits) {

        /** hitting / pitching. 없으면 null */
        public String groupName() {
            return group == null ? null : group.displayName();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GroupRef(String displayName) {
    }

    /**
     * 시즌 합계 한 줄. 팀마다 한 줄이 오고, 한 단계에서 여러 팀을 뛰었으면 그 단계 합계(team 없음),
     * 여러 단계를 뛰었으면 마이너 전체 합계(team 없음, sport.id 21)가 더 온다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeasonSplit(MlbTeam team, MlbTeam.Sport sport, MlbStat stat) {
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
