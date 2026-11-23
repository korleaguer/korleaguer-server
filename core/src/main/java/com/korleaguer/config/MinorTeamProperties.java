package com.korleaguer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * resources/roster/minor-teams.yml 의 마이너리그 팀 한국어 이름. 미국의 마이너 팀 모두다(트리플A·더블A 이슈 #145,
 * 하이싱글A·싱글A·루키 컴플렉스 리그 이슈 #191)
 */
@ConfigurationProperties("minor-league")
public record MinorTeamProperties(List<Team> teams) {

    public MinorTeamProperties {
        teams = teams == null ? List.of() : List.copyOf(teams);
    }

    /**
     * 팀 한 곳.
     *
     * @param id     MLB 팀 번호
     * @param level  단계 약어(AAA·AA·A+·A·ROK). 이동 기록 팀 이름 뒤에 붙인다(예: "귀넷(트리플A)", "주피터(싱글A)",
     *               MinorTeamNames.moveName)
     * @param nameKo 화면에 쓰는 한국어 도시(지명) 이름(예: "오클라호마시티". 지명이 없는 루키 컴플렉스 리그 팀은
     *               "FCL 브레이브스"·"ACL 다저스")
     * @param nameEn MLB 팀 이름(확인용)
     */
    public record Team(Long id, String level, String nameKo, String nameEn) {
    }
}
