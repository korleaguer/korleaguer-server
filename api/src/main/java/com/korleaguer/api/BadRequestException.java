package com.korleaguer.api;

/** 요청 값이 규칙에 맞지 않을 때. ApiExceptionHandler가 400 {"error": 메시지}로 바꾼다. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
