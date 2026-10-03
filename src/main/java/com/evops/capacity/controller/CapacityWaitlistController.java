package com.evops.capacity.controller;

import com.evops.capacity.dto.CapacityEnableRequest;
import com.evops.capacity.dto.OfferConfirmRequest;
import com.evops.capacity.dto.SeatHoldRequest;
import com.evops.capacity.dto.SeatReleaseRequest;
import com.evops.capacity.dto.WaitlistCancelRequest;
import com.evops.capacity.dto.WaitlistJoinRequest;
import com.evops.capacity.entity.CapacityBatch;
import com.evops.capacity.entity.CapacityEvent;
import com.evops.capacity.entity.CapacityNotification;
import com.evops.capacity.entity.CapacityOffer;
import com.evops.capacity.entity.CapacitySeat;
import com.evops.capacity.entity.CapacityWaitlistEntry;
import com.evops.capacity.service.CapacityWaitlistService;
import com.evops.capacity.vo.QueueStatusView;
import com.evops.capacity.vo.WaitlistPositionView;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 旺季满员候补与递补：
 * 满员进队列（位置透明、重复不插队）→ 有人退位按序递补（通知带服务器时限）→ 超时不顺延漏发 → 确认锁座。
 * 路径在 /api/** 下，自动走 HTTP Basic；经办人取 X-Operator。
 */
@RestController
@RequestMapping("/api/capacity")
public class CapacityWaitlistController {

    private final CapacityWaitlistService waitlistService;

    public CapacityWaitlistController(CapacityWaitlistService waitlistService) {
        this.waitlistService = waitlistService;
    }

    // ---------- 容量控制状态：启用 / 关窗 / 总览 ----------

    /** 启用批次容量控制：冻结生效规则版本与窗口、时限配置。 */
    @PostMapping("/enable")
    public ApiResponse<CapacityBatch> enable(@Valid @RequestBody CapacityEnableRequest request) {
        return ApiResponse.ok(waitlistService.enable(request));
    }

    /** 人工关闭候补窗口（在飞递补仍按时限处理，关后不再发起新一轮递补）。 */
    @PostMapping("/window/close")
    public ApiResponse<CapacityBatch> closeWindow(@RequestParam Long batchId,
                                                  @RequestParam(required = false) String reason) {
        return ApiResponse.ok(waitlistService.closeWindow(batchId, reason));
    }

    /** 批次容量/队列/窗口总览（服务器时间口径，队列位置透明）。 */
    @GetMapping("/status")
    public ApiResponse<QueueStatusView> status(@RequestParam Long batchId,
                                               @RequestParam(required = false, defaultValue = "false")
                                               boolean includeHistory) {
        return ApiResponse.ok(waitlistService.status(batchId, includeHistory));
    }

    // ---------- 直接占座 / 退位 ----------

    @PostMapping("/seats/hold")
    public ApiResponse<CapacitySeat> holdSeat(@Valid @RequestBody SeatHoldRequest request) {
        return ApiResponse.ok(waitlistService.holdSeat(request));
    }

    /** 有人退位：座位置空位并立即按序发起递补。 */
    @PostMapping("/seats/release")
    public ApiResponse<CapacitySeat> releaseSeat(@Valid @RequestBody SeatReleaseRequest request) {
        return ApiResponse.ok(waitlistService.releaseSeat(request));
    }

    // ---------- 候补入队 / 退出 / 位置 ----------

    /** 满员进队列；重复请求返回原队位不插队；仍有空位时提示直接占座。 */
    @PostMapping("/waitlist/join")
    public ApiResponse<WaitlistPositionView> join(@Valid @RequestBody WaitlistJoinRequest request) {
        return ApiResponse.ok(waitlistService.join(request));
    }

    @PostMapping("/waitlist/cancel")
    public ApiResponse<CapacityWaitlistEntry> cancel(@Valid @RequestBody WaitlistCancelRequest request) {
        return ApiResponse.ok(waitlistService.cancel(request));
    }

    /** 查本人队位：序号、前面人数、在飞递补截止钟点与服务器当前时间。 */
    @GetMapping("/waitlist/position")
    public ApiResponse<WaitlistPositionView> position(@RequestParam Long batchId,
                                                      @RequestParam String regNo) {
        return ApiResponse.ok(waitlistService.position(batchId, regNo));
    }

    // ---------- 递补确认 ----------

    /** 候补人在通知时限内确认：座位过记本人并锁定；超时由服务器时间判定出局并顺延。 */
    @PostMapping("/offers/confirm")
    public ApiResponse<CapacitySeat> confirm(@Valid @RequestBody OfferConfirmRequest request) {
        return ApiResponse.ok(waitlistService.confirm(request));
    }

    /** 手动立即执行超时顺延/到点关窗兜底（平时由定时任务每分钟执行）。 */
    @PostMapping("/scan")
    public ApiResponse<Map<String, Object>> scan(@RequestParam(required = false) Long batchId) {
        return ApiResponse.ok(batchId == null ? waitlistService.scanAll() : waitlistService.scanBatch(batchId));
    }

    @GetMapping("/offers")
    public ApiResponse<List<CapacityOffer>> offers(@RequestParam Long batchId) {
        return ApiResponse.ok(waitlistService.listOffers(batchId));
    }

    @GetMapping("/events")
    public ApiResponse<List<CapacityEvent>> events(@RequestParam Long batchId) {
        return ApiResponse.ok(waitlistService.events(batchId));
    }

    // ---------- 通知 ----------

    @GetMapping("/notifications")
    public ApiResponse<List<CapacityNotification>> notifications(
            @RequestParam(required = false) String regNo,
            @RequestParam(required = false, defaultValue = "false") boolean unreadOnly) {
        return ApiResponse.ok(waitlistService.notifications(regNo, unreadOnly));
    }

    @PostMapping("/notifications/{id}/read")
    public ApiResponse<CapacityNotification> markRead(@PathVariable Long id) {
        return ApiResponse.ok(waitlistService.markRead(id));
    }
}
