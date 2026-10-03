package com.evops.capacity.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批次容量控制总览：容量/占座/空位/队列各状态计数 + 窗口状态 + 队列明细，全部按服务器时间计算。
 */
@Data
public class QueueStatusView {
    private Long batchId;
    private String batchCode;
    private String batchName;
    private Long cbId;
    private Long ruleId;
    private String ruleVersionNo;
    /** ENABLED / CLOSED（容量控制是否启用与开关状态）。 */
    private String controlStatus;
    /** 窗口计算状态：NOT_ENABLED 未启用 / OPEN 窗口开放 / CLOSED 窗口已关闭。 */
    private String windowState;
    private LocalDateTime windowStartAt;
    private LocalDateTime windowEndAt;
    /** 判定所用的服务器当前时间。 */
    private LocalDateTime serverNow;

    private Integer totalCapacity;
    /** 直接占座数（HELD 且来源 DIRECT）。 */
    private Integer directHeldCount;
    /** 候补递补锁座数。 */
    private Integer waitlistSeatedCount;
    /** 已退位待递补的空位数。 */
    private Integer releasedSeatCount;
    /** 当前在飞递补通知数（OFFERING）。 */
    private Integer offeringCount;
    private Integer waitingCount;
    private Integer seatedCount;
    private Integer expiredCount;
    private Integer cancelledCount;
    private Integer closedCount;

    /** 按队位排序的在队/在飞条目（SEATED/EXPIRED/CANCELLED/CLOSED 默认不列，可通过 includeHistory 查看）。 */
    private List<QueueEntryItem> queue;
}
