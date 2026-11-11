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
     * @return 요약(줄마다 한 문장). 거절·빈 응답이면 empty
     * @throws Failure 요약 서비스가 답하지 못했을 때
     */
    Optional<String> summarize(String player, String title, String body);

    /** 요약한 모델 이름(기록용) */
    String model();

    /**
     * 요약 서비스가 이번에 답하지 못했다. articleProblem이면 요청 내용이 거절된 것(400 등)이라 그 기사의 시도로 센다.
     * 아니면(키·모델 설정, 사용량 제한, 서버 문제, 연결 실패) 기사 탓이 아니라 세지 않고 다음 차례에 다시 한다.
     */
    final class Failure extends RuntimeException {

        private final boolean articleProblem;

        public Failure(String message, boolean articleProblem, Throwable cause) {
            super(message, cause);
            this.articleProblem = articleProblem;
        }

        public boolean articleProblem() {
            return articleProblem;
        }
    }
}
