package com.korleaguer.ingest;

/** 수집은 한 번에 하나만 돈다. 이미 도는 중에 또 부르면 던진다. */
public class IngestAlreadyRunningException extends RuntimeException {

    public IngestAlreadyRunningException() {
        super("ingest already running");
    }
}
