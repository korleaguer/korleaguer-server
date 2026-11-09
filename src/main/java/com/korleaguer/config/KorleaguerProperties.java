package com.korleaguer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.properties 의 korleaguer.* 설정 */
@ConfigurationProperties("korleaguer")
public record KorleaguerProperties(int season, Data data, Bdl bdl, Ingest ingest, Admin admin, Images images,
                                   Mlb mlb) {

    /** mode: sample | bdl | 비움(키가 있으면 bdl) */
    public record Data(String mode) {
    }

    public record Bdl(String baseUrl, String apiKey, int requestsPerMinute) {

        // 키가 로그에 찍히지 않게 가린다.
        @Override
        public String toString() {
            return "Bdl[baseUrl=" + baseUrl + ", apiKey=***, requestsPerMinute=" + requestsPerMinute + "]";
        }
    }

    public record Ingest(boolean scheduleEnabled, String cron) {
    }

    public record Admin(String token) {

        @Override
        public String toString() {
            return "Admin[token=***]";
        }
    }

    /**
     * officialPhotos: false면 MLB 공식 사진 주소를 응답에 넣지 않는다(photoUrl이 null).
     * playerPhotoTemplate: 사진 주소 틀. {mlbId}가 MLB 선수 번호로 바뀐다.
     */
    public record Images(boolean officialPhotos, String playerPhotoTemplate) {
    }

    /**
     * MLB 공식 Stats API(키 없음). 실제 모드에서만 부른다(샘플 모드는 SampleMlbClient).
     * userAgent: 우리가 누군지 밝히는 값(korleaguer/0.1). 브라우저인 척하지 않는다.
     */
    public record Mlb(String baseUrl, String userAgent) {
    }
}
