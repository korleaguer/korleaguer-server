package com.korleaguer.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** resources/roster/baseball.yml 의 선수 명단 */
@ConfigurationProperties("roster")
public record RosterProperties(List<Entry> baseball) {

    /**
     * 한글 이름과 MLB 공식 선수 번호(mlbId). 선수의 모든 데이터는 mlbId로 MLB에서 받는다.
     * mlbId가 없으면 이름만 보이는 선수가 된다. careerKo(한국 시절 소속)는 사람이 적은 문장으로 그대로 화면에 쓴다(없으면 null).
     * returnedKo는 국내 리그로 돌아간 팀(예: "LG 트윈스")이다. 있으면 MLB 기록상 FA여도 상태가 'KBO 복귀'다(MLB 데이터에는
     * KBO 계약이 없어 사람이 적는다). 이제 해외파가 아니므로 다음 시즌이 시작되면 명단에서 뺀다.
     */
    public record Entry(String nameKo, Long mlbId, String careerKo, String returnedKo) {
    }
}
