package com.evops.capacity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 递补单：一个空位一轮一条。OFFERING 待确认（通知带时限）→ SEATED 确认锁座 / EXPIRED 超时顺延下一位。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_capacity_offer")
public class CapacityOffer extends BaseEntity {
    private Long batchId;
    private Long seatId;
    /** 同一空位的递补轮次：1 起，超时一轮 +1。 */
    private Integer roundNo;
    private Long entryId;
    private String regNo;
    private String name;
    /** OFFERING / SEATED / EXPIRED */
    private String status;
    private LocalDateTime offeredAt;
    /** 确认截止钟点（服务器时间判定）。 */
    private LocalDateTime expireAt;
    private LocalDateTime notifiedAt;
    private Long notificationId;
    private LocalDateTime confirmedAt;
    private String operator;
}
