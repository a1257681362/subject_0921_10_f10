package com.evops.capacity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 容量控制站内通知：入队/递补/超时/锁座/关窗都通知；本版落库，短信等外发渠道下版再接。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_capacity_notification")
public class CapacityNotification extends BaseEntity {
    private Long batchId;
    private String regNo;
    private String name;
    /** JOINED / OFFER / EXPIRED / SEATED / QUEUE_CLOSED */
    private String notifyType;
    private String title;
    private String content;
    /** NEW / READ */
    private String status;
    private Long offerId;
    private Long entryId;
    private String operator;
    private LocalDateTime notifiedAt;
    private LocalDateTime readAt;
}
