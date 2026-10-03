package com.evops.capacity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 批次容量控制状态：启用即冻结当时生效规则版本与配置快照；一个批次一条。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_capacity_batch")
public class CapacityBatch extends BaseEntity {
    private Long batchId;
    private Long siteId;
    private Long ruleId;
    private Long ruleVersionId;
    private String ruleVersionNo;
    /** 启用时冻结的配置 JSON 快照。 */
    private String configSnapshot;
    private Integer totalCapacity;
    /** 队列序号发放器：入队 +1，只增不减。 */
    private Long nextSeq;
    private LocalDateTime windowStartAt;
    private LocalDateTime windowEndAt;
    /** ENABLED / CLOSED */
    private String status;
    private LocalDateTime enabledAt;
    private LocalDateTime closedAt;
    private String closeReason;
    private String operator;
}
