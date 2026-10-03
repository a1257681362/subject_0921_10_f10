package com.evops.capacity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 容量控制规则模块：窗口与时限按考点配，一个考点一套规则，规则下挂多个版本。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_capacity_rule")
public class CapacityRule extends BaseEntity {
    private String code;
    private String name;
    /** 归属考点（一个考点仅一套容量规则）。 */
    private Long siteId;
    private String remark;
}
