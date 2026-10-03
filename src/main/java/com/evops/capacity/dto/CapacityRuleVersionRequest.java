package com.evops.capacity.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

@Data
public class CapacityRuleVersionRequest {
    @Valid
    @NotNull(message = "容量配置不能为空")
    private CapacityConfig config;
    private String remark;
}
