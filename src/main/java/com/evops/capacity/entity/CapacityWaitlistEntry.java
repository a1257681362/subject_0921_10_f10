package com.evops.capacity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 候补队列条目：满员才入队；队位由 seq 决定（序号透明）；重复请求只认原队位，不许插队。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_capacity_waitlist_entry")
public class CapacityWaitlistEntry extends BaseEntity {
    private Long batchId;
    private String regNo;
    private String name;
    private String phone;
    /** 队列序号（一批次内只增；位置透明靠它，不靠客户端时间）。 */
    private Long seq;
    /** WAITING / OFFERING / SEATED / EXPIRED / CANCELLED / CLOSED */
    private String status;
    private Long offerId;
    private LocalDateTime offeredAt;
    private LocalDateTime offerExpiresAt;
    private Integer offerCount;
    private Long seatId;
    private LocalDateTime seatedAt;
    private String cancelReason;
}
