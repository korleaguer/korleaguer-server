package com.korleaguer.support;

import java.time.ZoneId;

/** MLB 날짜를 정하는 시간대. 수집(경기 날짜)과 화면(시즌·기간)이 같은 값을 써야 날짜가 어긋나지 않는다 */
public final class MlbTime {

    /** 미국 날짜(경기 공식 날짜)를 정할 때 쓰는 시간대 */
    public static final ZoneId US_EASTERN = ZoneId.of("America/New_York");

    private MlbTime() {
    }
}
