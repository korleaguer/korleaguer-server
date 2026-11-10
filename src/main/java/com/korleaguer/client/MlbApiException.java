package com.korleaguer.client;

/** MLB Stats API 호출 실패(5xx, 네트워크, 읽을 수 없는 응답 등). 그 건만 건너뛰고 다음 수집 때 다시 묻는다. */
public class MlbApiException extends RuntimeException {

    public MlbApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
