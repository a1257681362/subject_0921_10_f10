package com.evops.capacity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 队列中的一行（按队位排序，位置透明）。
 */
@Data
public class QueueEntryItem {
    private Long entryId;
    private Long seq;
    /** 当前队位（1 起；仅 WAITING 有排队位置，其余状态为在飞/终态）。 */
    private Long position;
    private String regNo;
    private String name;
    private String status;
    private Long offerId;
    private LocalDateTime offeredAt;
    private LocalDateTime offerExpiresAt;
    private Integer offerCount;
}
