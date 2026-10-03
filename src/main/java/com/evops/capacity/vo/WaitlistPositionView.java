package com.evops.capacity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 候补位置视图：位置透明，不让人猜。重复入队返回同一条原队位，不插队。
 */
@Data
public class WaitlistPositionView {
    private Long entryId;
    private Long batchId;
    private String regNo;
    private String name;
    /** 入队序号（一批次内只增，永不复用）。 */
    private Long seq;
    /** WAITING / OFFERING / SEATED / EXPIRED / CANCELLED / CLOSED */
    private String status;
    /** 当前前面还有几位排队（WAITING 才大于 0；OFFERING 时为 0，表示轮到本人）。 */
    private Long aheadCount;
    /** 当前队位（1 起；OFFERING/SEATED 等终态可为空）。 */
    private Long position;
    private Long offerId;
    private LocalDateTime offeredAt;
    /** 确认截止钟点（服务器时间口径）。 */
    private LocalDateTime offerExpiresAt;
    /** 判定所用的服务器当前时间。 */
    private LocalDateTime serverNow;
    private String message;
}
