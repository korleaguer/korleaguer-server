package com.korleaguer.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** resources/roster/baseball.yml 의 선수 명단 */
@ConfigurationProperties("roster")
public record RosterProperties(List<Entry> baseball) {

    /**
     * 한글 이름과 MLB 공식 선수 번호(mlbId). 선수의 모든 데이터는 mlbId로 MLB에서 받는다.
     * mlbId가 없으면 이름만 보이는 선수가 된다. careerKo(한국 시절 소속)는 사람이 적은 문장으로 그대로 화면에 쓴다(없으면 null).
     */
    public record Entry(String nameKo, Long mlbId, String careerKo) {
    }
}
