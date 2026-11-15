package com.korleaguer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 경기 타석별 기록(/api/v1/game/{gamePk}/playByPlay)에서 쓰는 칸만. 마이너 경기도 같은 모양으로 온다.
 * allPlays는 타석(또는 타석을 끝낸 주루 플레이)마다 한 줄이고, playEvents는 그 타석의 공·주루 동작이다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlbPlayByPlay(List<Play> allPlays) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Play(Result result, About about, Count count, Matchup matchup, List<Event> playEvents,
                       List<Runner> runners) {
    }

    /**
     * @param eventType 결과 종류(single, home_run, field_out, strikeout …)
     * @param description 영어 설명("Jung Hoo Lee homers (10) on a fly ball to right field.")
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(String event, String eventType, String description, Integer rbi) {
    }

    /** @param halfInning top(초) / bottom(말) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record About(Integer atBatIndex, String halfInning, Integer inning, Boolean isComplete) {
    }

    /** 타석이 끝났을 때의 볼카운트와 아웃 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Count(Integer balls, Integer strikes, Integer outs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Matchup(Person batter, Person pitcher) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Person(Long id, String fullName) {
    }

    /**
     * 공 하나(isPitch) 또는 주루 동작. playId는 공마다 붙는 번호(Baseball Savant 영상 주소에 쓴다).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(Boolean isPitch, String playId, Details details, PitchData pitchData, HitData hitData) {
    }

    /**
     * @param code 공 결과 코드(B 볼, C 루킹 스트라이크, S 헛스윙, F 파울, T 파울팁, X 인플레이, D 인플레이 안타, E 인플레이 득점 …)
     * @param type 구종(code: FF·SL·CU …, description: "Four-Seam Fastball")
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Details(String code, String description, PitchType type) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PitchType(String code, String description) {
    }

    /** @param startSpeed 구속(mph) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PitchData(Double startSpeed) {
    }

    /**
     * @param launchSpeed 타구 속도(mph)
     * @param launchAngle 발사각(도)
     * @param totalDistance 비거리(피트)
     * @param trajectory ground_ball · fly_ball · line_drive · popup · bunt_grounder …
     * @param location 타구가 간 수비 위치 번호(1 투수 ~ 9 우익수)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HitData(Double launchSpeed, Double launchAngle, Double totalDistance, String trajectory,
                          String location) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Runner(RunnerDetails details, List<Credit> credits) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RunnerDetails(Person runner) {
    }

    /** 수비 기록(누가 공을 잡았나). credit: f_putout · f_assist · f_fielded_ball · f_fielding_error … */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Credit(Position position, String credit) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Position(String abbreviation) {
    }
}
