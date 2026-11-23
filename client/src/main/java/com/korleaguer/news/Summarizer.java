package com.korleaguer.news;

import java.time.LocalDate;
import java.util.Optional;

/** 기사 본문을 한국어로 요약한다(길이는 기사 분량을 따른다). API 키가 없으면 OFF(아무것도 하지 않음)다. */
public interface Summarizer {

    Summarizer OFF = new Summarizer() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public Optional<Summary> summarize(String player, String title, LocalDate published, String body) {
            return Optional.empty();
        }

        @Override
        public String model() {
            return null;
        }
    };

    boolean enabled();

    /**
     * @param player    기사가 다루는 포스팅 선수의 한글 이름(영어 표기를 한글로 바꾸게)
     * @param published 기사 날짜(한국 날짜). 'last February'·'올해' 같은 상대 날짜를 바르게 옮기게 알려 준다
     * @return 한국어 제목과 요약(줄마다 한 문장). 거절·빈 응답이면 empty
     * @throws Failure 요약 서비스가 답하지 못했을 때
     */
    Optional<Summary> summarize(String player, String title, LocalDate published, String body);

    /**
     * 요약 결과.
     *
     * @param titleKo 기사 제목을 한국어로 옮긴 것(영어 기사 제목 대신 보인다). 받지 못했으면 null
     * @param text    요약(줄마다 한 문장)
     */
    record Summary(String titleKo, String text) {
    }

    /** 요약한 모델 이름(기록용) */
    String model();

    /**
     * 요약을 만든 모델과 지시문 판. 요약마다 적어 두고(summary_model), 지금 판과 다른 옛 요약은 차례로 다시 요약한다.
     * 지시문이 따로 없는 요약기는 모델 이름 그대로다
     */
    default String version() {
        return model();
    }

    /**
     * 요약 서비스가 이번에 답하지 못했다(키·결제·모델 설정, 사용량 제한, 서버 문제, 연결 실패, 요청 거절).
     * 본문은 8000자로 묶고 요청 모양은 늘 같아 기사 탓일 일이 거의 없으므로, 기사의 시도로 세지 않고 다음 차례에 다시 한다.
     */
    final class Failure extends RuntimeException {

        public Failure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
