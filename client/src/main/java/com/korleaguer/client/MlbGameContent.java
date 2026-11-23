package com.korleaguer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 경기 영상 목록(/api/v1/game/{gamePk}/content)에서 하이라이트만. 영상마다 나온 선수 번호가 keywordsAll(type=player_id)에
 * 붙어 있어, 우리 선수가 나온 영상만 고를 수 있다. 마이너 경기는 대개 빈 목록이다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlbGameContent(Highlights highlights) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Highlights(HighlightList highlights) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HighlightList(List<Item> items) {
    }

    /**
     * @param headline 영어 제목("Jung Hoo Lee's solo home run (10)")
     * @param date     올린 시각(UTC, ISO-8601)
     * @param duration 길이("00:00:30")
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String headline, String date, String duration, List<Keyword> keywordsAll, Image image,
                       List<Playback> playbacks) {
    }

    /** type: player_id · game_pk · taxonomy …, value: 그 값(선수 번호는 글자) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Keyword(String type, String value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Image(List<Cut> cuts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cut(Integer width, Integer height, String src) {
    }

    /** name: mp4Avc(mp4) · hlsCloud(HLS) …, url: 영상 주소(MLB 서버) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Playback(String name, String url) {
    }
}
