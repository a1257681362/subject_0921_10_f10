package com.evops.capacity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 容量控制规则版本：窗口/时限配置发布即冻结；DRAFT → ACTIVE → DISABLED，同规则同时刻仅一版生效。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_capacity_rule_version")
public class CapacityRuleVersion extends BaseEntity {
    private Long ruleId;
    private String versionNo;
    /** DRAFT / ACTIVE / DISABLED */
    private String status;
    /** 发布时冻结的配置 JSON：{"waitlistEnabled":true,"offerTimeoutMinutes":30,"waitlistEndHoursBeforeExam":24} */
    private String config;
    private String remark;
    private String publishedBy;
    private LocalDateTime publishedAt;
    private String disabledBy;
    private LocalDateTime disabledAt;
    private String operator;
}
