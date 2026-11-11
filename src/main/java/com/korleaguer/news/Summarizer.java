package com.korleaguer.news;

import java.util.Optional;

/** 기사 본문을 한국어 3~4줄로 요약한다. API 키가 없으면 OFF(아무것도 하지 않음)다. */
public interface Summarizer {

    Summarizer OFF = new Summarizer() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public Optional<String> summarize(String player, String title, String body) {
            return Optional.empty();
        }

        @Override
        public String model() {
            return null;
        }
    };

    boolean enabled();

    /**
     * @param player 기사가 다루는 포스팅 선수의 한글 이름(영어 표기를 한글로 바꾸게)
     * @return 요약(줄마다 한 문장). 거절·빈 응답이면 empty. API 오류는 RuntimeException
     */
    Optional<String> summarize(String player, String title, String body);

    /** 요약한 모델 이름(기록용) */
    String model();
}
