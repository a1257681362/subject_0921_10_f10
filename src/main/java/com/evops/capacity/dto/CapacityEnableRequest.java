package com.evops.capacity.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 启用批次容量控制：冻结当时生效规则版本；容量缺省取考点考场容量之和；窗口缺省按规则配置与开考时间推导。
 */
@Data
public class CapacityEnableRequest {
    @NotNull(message = "批次 id 不能为空")
    private Long batchId;
    /** 总容量；为空时取考点全部考场容量之和。 */
    private Integer totalCapacity;
    /** 显式窗口起点（缺省=启用当前服务器时间）。 */
    private LocalDateTime windowStartAt;
    /** 显式窗口终点（缺省按规则 waitlistEndHoursBeforeExam 与开考时间推导；都没有则不自动关闭）。 */
    private LocalDateTime windowEndAt;
    /** 是否把批次现有报名名册同步为直接占座（默认 false；开启时名册超容量整批拒收）。 */
    private Boolean syncRegistrations;
    private String remark;
}
