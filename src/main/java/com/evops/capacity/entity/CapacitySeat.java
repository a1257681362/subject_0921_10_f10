package com.evops.capacity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 座位台账：一批次一人一条。直接报名 HELD；退位 RELEASED；候补确认后过记给候补人（WAITLIST/HELD）锁座。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_capacity_seat")
public class CapacitySeat extends BaseEntity {
    private Long batchId;
    private String regNo;
    private String name;
    /** DIRECT 直接占座 / WAITLIST 候补递补锁座 */
    private String source;
    private Long entryId;
    private Long offerId;
    /** HELD 锁定中 / RELEASED 已退位 */
    private String status;
    private LocalDateTime heldAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime releasedAt;
    private String releaseReason;
}
