package com.korleaguer.domain;

public enum IngestStatus {
    RUNNING,
    SUCCESS,
    /** MLB가 요청을 거절해(403/429) 도중에 멈췄다. 그때까지 받은 것은 저장했다. */
    BLOCKED,
    FAILED
}
