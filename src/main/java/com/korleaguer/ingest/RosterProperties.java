package com.korleaguer.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** resources/roster/baseball.yml 의 선수 명단 */
@ConfigurationProperties("roster")
public record RosterProperties(List<Entry> baseball) {

    /**
     * 한글 이름과 balldontlie 검색어(영어 별칭).
     * bdlPlayerId를 적어 두면 검색하지 않고 그 선수를 쓴다(사람이 고른 경우).
     * mlbId(MLB 공식 선수 번호)는 사진 주소와 MLB 공식 페이지 링크에, careerKo(한국 시절 소속)는 그대로 화면에 쓴다.
     * mlbId와 careerKo는 없어도 된다(null).
     */
    public record Entry(String nameKo, List<String> aliases, Long bdlPlayerId, Long mlbId, String careerKo) {

        /** 성. 첫 번째 별칭의 마지막 단어("Jung Hoo Lee" → "Lee") */
        public String surname() {
            String[] words = aliases.getFirst().trim().split("\\s+");
            return words[words.length - 1];
        }
    }
}
