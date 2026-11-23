package com.korleaguer.client;

/** MLB가 요청을 거절했다(403/429). 이번 실행에서는 MLB를 더 부르지 않는다. */
public class MlbBlockedException extends MlbApiException {

    public MlbBlockedException(String message, Throwable cause) {
        super(message, cause);
    }
}
