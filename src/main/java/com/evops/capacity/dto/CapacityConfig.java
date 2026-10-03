package com.evops.capacity.dto;

import lombok.Data;

/**
 * 容量控制规则配置（发布时冻结）。窗口与时限按考点配，不写死。
 */
@Data
public class CapacityConfig {
    /** 候补总开关（停用规则时为 false；当前版本保留字段，缺省 true）。 */
    private Boolean waitlistEnabled;
    /** 递补通知确认时限（分钟），超时顺给下一位。 */
    private Integer offerTimeoutMinutes;
    /** 候补窗口距开考提前关闭小时数（0=开考时刻关；为空表示不按开考自动关，只认人工/显式窗口）。 */
    private Integer waitlistEndHoursBeforeExam;
}
