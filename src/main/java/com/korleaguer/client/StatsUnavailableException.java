package com.korleaguer.client;

/** stats 호출이 401/403으로 막혔을 때. 유료 요금제가 없다는 뜻으로 본다. */
public class StatsUnavailableException extends RuntimeException {

    public StatsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
