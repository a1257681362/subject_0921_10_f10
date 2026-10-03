package com.evops.capacity.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class CapacityRuleRequest {
    @NotBlank(message = "规则编码不能为空")
    private String code;
    @NotBlank(message = "规则名称不能为空")
    private String name;
    /** 归属考点：窗口与时限按考点配，一个考点仅一套规则。 */
    @NotNull(message = "考点 id 不能为空")
    private Long siteId;
    private String remark;
}
