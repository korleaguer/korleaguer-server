package com.korleaguer.api.card;

/** 한 줄 소식. tone: HOT(좋은 일, 주황) · COLD(안 좋은 일, 파랑). text는 화면에 그대로 쓰는 한국어 문장이다. */
public record CardFact(String tone, String text) {
}
