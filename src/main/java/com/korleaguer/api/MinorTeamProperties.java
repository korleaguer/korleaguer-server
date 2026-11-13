package com.korleaguer.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** resources/roster/minor-teams.yml 의 마이너리그 팀 한국어 이름(트리플A·더블A, 이슈 #145) */
@ConfigurationProperties("minor-league")
public record MinorTeamProperties(List<Team> teams) {

    public MinorTeamProperties {
        teams = teams == null ? List.of() : List.copyOf(teams);
    }

    /**
     * 팀 한 곳.
     *
     * @param id     MLB 팀 번호
     * @param level  단계 약어(AAA·AA, 확인용)
     * @param nameKo 화면에 쓰는 한국어 도시(지명) 이름(예: "오클라호마시티")
     * @param nameEn MLB 팀 이름(확인용)
     */
    public record Team(Long id, String level, String nameKo, String nameEn) {
    }
}
