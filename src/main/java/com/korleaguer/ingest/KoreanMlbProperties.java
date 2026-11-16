package com.korleaguer.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * resources/roster/korean-mlb.yml: 한국인 메이저리거 역대 기록(이슈 #177)에 넣는, 지금 명단에 없는 선수.
 * 지금 명단(baseball.yml) 선수는 여기 적지 않아도 들어간다.
 */
@ConfigurationProperties("korean-mlb")
public record KoreanMlbProperties(List<Entry> past) {

    public KoreanMlbProperties {
        past = past == null ? List.of() : List.copyOf(past);
    }

    /**
     * 선수 한 명.
     *
     * @param nameKo 화면에 쓰는 한글 이름
     * @param mlbId  MLB 공식 선수 번호
     * @param role   "hitter"(타자 기록) 또는 "pitcher"(투수 기록)
     */
    public record Entry(String nameKo, Long mlbId, String role) {

        /** 투수 기록으로 순위에 넣는 선수인지 */
        public boolean pitcher() {
            return "pitcher".equalsIgnoreCase(role);
        }
    }
}
