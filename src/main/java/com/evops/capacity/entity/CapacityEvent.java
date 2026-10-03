package com.evops.capacity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 容量控制事件流水：入队/退位/递补/确认/超时/顺延/关窗全程留痕，只追加。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_capacity_event")
public class CapacityEvent extends BaseEntity {
    private Long batchId;
    private Long entryId;
    private Long offerId;
    private Long seatId;
    private String regNo;
    /** SEAT_HELD / JOIN / RELEASE / OFFER / CONFIRM / EXPIRE / CANCEL / WINDOW_CLOSE */
    private String eventType;
    private String fromStatus;
    private String toStatus;
    private String detail;
    private String operator;
    private LocalDateTime eventAt;
}
