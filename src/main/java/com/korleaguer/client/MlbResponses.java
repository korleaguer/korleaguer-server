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

    /** /api/v1/schedule */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Schedule(List<ScheduleDate> dates) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScheduleDate(String date, List<MlbScheduleGame> games) {
    }
}
