package com.korleaguer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** application.properties 의 korleaguer.* 설정 */
@ConfigurationProperties("korleaguer")
public record KorleaguerProperties(int season, String dataSource, Ingest ingest, Admin admin, Images images, Mlb mlb) {

    /**
     * scheduleEnabled: 자동 수집(스케줄과 앱 시작 때 따라잡기)을 켤지.
     * cron: 스케줄이 깨어나는 주기(UTC). 깨어날 때마다 할 일이 있는지 보고 필요한 것만 받는다.
     * onDemandTimeout: 선수 페이지를 열 때 그 선수 시즌이 비어 있으면 그 자리에서 받는데, 그때 기다리는 최대 시간
     */
    public record Ingest(boolean scheduleEnabled, String cron, Duration onDemandTimeout) {
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
     * MLB 공식 Stats API(키 없음). data-source가 mlb일 때만 부른다(sample은 SampleMlbClient).
     * userAgent: 우리가 누군지 밝히는 값(korleaguer/0.1). 브라우저인 척하지 않는다.
     */
    public record Mlb(String baseUrl, String userAgent) {
    }
}
