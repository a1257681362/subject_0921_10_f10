package com.evops.capacity.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class OfferConfirmRequest {
    @NotNull(message = "批次 id 不能为空")
    private Long batchId;
    @NotBlank(message = "报名号不能为空")
    private String regNo;
}
