package com.evops.capacity.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.capacity.common.CapacityConstants;
import com.evops.capacity.dto.CapacityConfig;
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
import com.evops.capacity.entity.CapacityRule;
import com.evops.capacity.entity.CapacityRuleVersion;
import com.evops.capacity.entity.CapacitySeat;
import com.evops.capacity.entity.CapacityWaitlistEntry;
import com.evops.capacity.mapper.CapacityBatchMapper;
import com.evops.capacity.mapper.CapacityEventMapper;
import com.evops.capacity.mapper.CapacityNotificationMapper;
import com.evops.capacity.mapper.CapacityOfferMapper;
import com.evops.capacity.mapper.CapacitySeatMapper;
import com.evops.capacity.mapper.CapacityWaitlistEntryMapper;
import com.evops.capacity.vo.QueueEntryItem;
import com.evops.capacity.vo.QueueStatusView;
import com.evops.capacity.vo.WaitlistPositionView;
import com.evops.common.BusinessException;
import com.evops.common.OperatorHolder;
import com.evops.exam.entity.ExamBatch;
import com.evops.exam.entity.ExamRoom;
import com.evops.exam.entity.Registration;
import com.evops.exam.mapper.ExamBatchMapper;
import com.evops.exam.mapper.ExamRoomMapper;
import com.evops.exam.mapper.RegistrationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 旺季满员候补与递补：
 * - 满员才收候补，队列按服务器入库顺序发序号，位置全程透明；重复请求只认原队位，不许插队；
 * - 有人退位释放座位 → 取队首发起递补，通知带确认时限（服务器时间判超时）；
 * - 超时未确认：本轮递补单置 EXPIRED、本人出局，座位顺给下一位，逐轮写单/通知不漏发；
 * - 确认成功即把座位过记到候补人名下锁定（WAITLIST/HELD）；
 * - 候补窗口按考点规则配置，到点自动关闭，关窗后不再发起新一轮递补。
 *
 * 并发口径：每个批次的写操作先对 t_capacity_batch 行加行锁（SELECT … FOR UPDATE）串行化，
 * 保证序号发放、空位分配、顺延不重不漏。
 */
@Service
public class CapacityWaitlistService {

    private final CapacityBatchMapper batchControlMapper;
    private final CapacitySeatMapper seatMapper;
    private final CapacityWaitlistEntryMapper entryMapper;
    private final CapacityOfferMapper offerMapper;
    private final CapacityNotificationMapper notificationMapper;
    private final CapacityEventMapper eventMapper;
    private final CapacityRuleService ruleService;
    private final ExamBatchMapper examBatchMapper;
    private final ExamRoomMapper roomMapper;
    private final RegistrationMapper registrationMapper;

    public CapacityWaitlistService(CapacityBatchMapper batchControlMapper,
                                   CapacitySeatMapper seatMapper,
                                   CapacityWaitlistEntryMapper entryMapper,
                                   CapacityOfferMapper offerMapper,
                                   CapacityNotificationMapper notificationMapper,
                                   CapacityEventMapper eventMapper,
                                   CapacityRuleService ruleService,
                                   ExamBatchMapper examBatchMapper,
                                   ExamRoomMapper roomMapper,
                                   RegistrationMapper registrationMapper) {
        this.batchControlMapper = batchControlMapper;
        this.seatMapper = seatMapper;
        this.entryMapper = entryMapper;
        this.offerMapper = offerMapper;
        this.notificationMapper = notificationMapper;
        this.eventMapper = eventMapper;
        this.ruleService = ruleService;
        this.examBatchMapper = examBatchMapper;
        this.roomMapper = roomMapper;
        this.registrationMapper = registrationMapper;
    }

    // ============ 批次启用 / 关闭容量控制（容量控制状态） ============

    @Transactional
    public CapacityBatch enable(CapacityEnableRequest request) {
        ExamBatch batch = requireExamBatch(request.getBatchId());
        CapacityBatch existing = findControl(batch.getId());
        if (existing != null) {
            throw new BusinessException("CAPACITY_ALREADY_ENABLED",
                    "该批次已启用容量控制，重复启用拒收；如需调整请改规则新版本（在飞队列沿用冻结配置）");
        }

        CapacityRule rule = ruleService.findRuleBySite(batch.getSiteId());
        if (rule == null) {
            throw new BusinessException("CAPACITY_RULE_NOT_CONFIGURED",
                    "考点尚未配置容量规则，窗口与时限无法确定：siteId=" + batch.getSiteId());
        }
        CapacityRuleVersion version = ruleService.findActiveVersion(rule.getId());
        if (version == null) {
            throw new BusinessException("CAPACITY_RULE_NOT_ACTIVE",
                    "考点容量规则尚无生效版本，请先发布规则版本：ruleId=" + rule.getId());
        }
        CapacityConfig config = ruleService.readConfig(version.getConfig());

        int capacity = request.getTotalCapacity() != null ? request.getTotalCapacity()
                : sumSiteCapacity(batch.getSiteId());
        if (capacity <= 0) {
            throw new BusinessException("CAPACITY_INVALID", "总容量必须大于 0（或为考点配置有容量的考场）");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = request.getWindowStartAt() != null ? request.getWindowStartAt() : now;
        LocalDateTime end = request.getWindowEndAt() != null ? request.getWindowEndAt()
                : deriveWindowEnd(batch, config);
        if (end != null && !end.isAfter(start)) {
            throw new BusinessException("CAPACITY_WINDOW_INVALID", "候补窗口终点必须晚于起点");
        }

        CapacityBatch cb = new CapacityBatch();
        cb.setBatchId(batch.getId());
        cb.setSiteId(batch.getSiteId());
        cb.setRuleId(rule.getId());
        cb.setRuleVersionId(version.getId());
        cb.setRuleVersionNo(version.getVersionNo());
        cb.setConfigSnapshot(ruleService.writeConfig(config));
        cb.setTotalCapacity(capacity);
        cb.setNextSeq(0L);
        cb.setWindowStartAt(start);
        cb.setWindowEndAt(end);
        cb.setStatus(CapacityConstants.BATCH_ENABLED);
        cb.setEnabledAt(now);
        cb.setOperator(OperatorHolder.current());
        batchControlMapper.insert(cb);

        if (Boolean.TRUE.equals(request.getSyncRegistrations())) {
            List<Registration> regs = registrationMapper.selectList(new QueryWrapper<Registration>()
                    .eq("batch_id", batch.getId()));
            if (regs.size() > capacity) {
                throw new BusinessException("CAPACITY_OVER_SUBSCRIBED",
                        "现有报名 " + regs.size() + " 人已超过容量 " + capacity + "，无法整批同步占座");
            }
            for (Registration reg : regs) {
                insertDirectSeat(cb, reg.getRegNo(), reg.getName(), now);
            }
        }
        return cb;
    }

    /** 人工关闭候补窗口；在飞递补仍按各自时限处理，关后不再发起新一轮递补。 */
    @Transactional
    public CapacityBatch closeWindow(Long batchId, String reason) {
        CapacityBatch cb = lockControl(batchId);
        return closeWindow(cb, LocalDateTime.now(), CapacityConstants.CLOSE_REASON_MANUAL, reason);
    }

    private CapacityBatch closeWindow(CapacityBatch cb, LocalDateTime now, String closeReason, String detail) {
        if (CapacityConstants.BATCH_CLOSED.equals(cb.getStatus())) {
            return cb;
        }
        cb.setStatus(CapacityConstants.BATCH_CLOSED);
        cb.setClosedAt(now);
        cb.setCloseReason(closeReason);
        batchControlMapper.updateById(cb);

        // 排队中的（不含已在飞 OFFERING）统一记 CLOSED 并通知；在飞的等其确认/超时，超时不再顺延。
        List<CapacityWaitlistEntry> waiting = entryMapper.selectList(new QueryWrapper<CapacityWaitlistEntry>()
                .eq("batch_id", cb.getBatchId())
                .eq("status", CapacityConstants.ENTRY_WAITING)
                .orderByAsc("seq"));
        for (CapacityWaitlistEntry e : waiting) {
            e.setStatus(CapacityConstants.ENTRY_CLOSED);
            e.setCancelReason(closeReason);
            entryMapper.updateById(e);
            notify(cb.getBatchId(), e, CapacityConstants.NOTIFY_QUEUE_CLOSED,
                    "候补窗口已关闭",
                    "本批次候补窗口已关闭，您（队位序号 " + e.getSeq() + "）本轮未递补上，请关注后续安排。",
                    null, "system");
            recordEvent(cb.getBatchId(), e.getId(), null, null, e.getRegNo(),
                    CapacityConstants.EVENT_WINDOW_CLOSE,
                    CapacityConstants.ENTRY_WAITING, CapacityConstants.ENTRY_CLOSED, detail);
        }
        return cb;
    }

    // ============ 直接占座 / 退位 ============

    /** 直接报名占座（容量未满时）；满员请走候补。 */
    @Transactional
    public CapacitySeat holdSeat(SeatHoldRequest request) {
        LocalDateTime now = LocalDateTime.now();
        CapacityBatch cb = lockControl(request.getBatchId());
        assertWindowOpen(cb, now);
        Registration reg = requireRegistration(cb.getBatchId(), request.getRegNo());

        CapacitySeat existing = findSeat(cb.getBatchId(), reg.getRegNo());
        if (existing != null && CapacityConstants.SEAT_HELD.equals(existing.getStatus())) {
            throw new BusinessException("SEAT_ALREADY_HELD", "该学员已锁定座位，无需重复占座");
        }
        CapacityWaitlistEntry entry = findEntry(cb.getBatchId(), reg.getRegNo());
        if (entry != null && (CapacityConstants.ENTRY_WAITING.equals(entry.getStatus())
                || CapacityConstants.ENTRY_OFFERING.equals(entry.getStatus()))) {
            throw new BusinessException("PERSON_IN_WAITLIST", "该学员正在候补中，不能直接占座");
        }
        if (availableNow(cb) <= 0) {
            throw new BusinessException("CAPACITY_FULL", "座位已满，请加入候补队列");
        }
        return insertDirectSeat(cb, reg.getRegNo(), reg.getName(), now);
    }

    /** 有人退位：座位置 RELEASED，立即触发按序递补。 */
    @Transactional
    public CapacitySeat releaseSeat(SeatReleaseRequest request) {
        LocalDateTime now = LocalDateTime.now();
        CapacityBatch cb = lockControl(request.getBatchId());
        CapacitySeat seat = findSeat(cb.getBatchId(), request.getRegNo());
        if (seat == null || !CapacityConstants.SEAT_HELD.equals(seat.getStatus())) {
            throw new BusinessException("SEAT_NOT_HELD", "该学员当前没有锁定中的座位，无需退位");
        }
        seat.setStatus(CapacityConstants.SEAT_RELEASED);
        seat.setReleasedAt(now);
        seat.setReleaseReason(request.getReason());
        seatMapper.updateById(seat);
        recordEvent(cb.getBatchId(), null, null, seat.getId(), seat.getRegNo(),
                CapacityConstants.EVENT_RELEASE, CapacityConstants.SEAT_HELD,
                CapacityConstants.SEAT_RELEASED, request.getReason());

        dispatch(cb, now);
        return seat;
    }

    // ============ 候补入队 / 退出 / 位置 ============

    /**
     * 满员进队列：
     * - 容量未满（含尚有未分配空位）拒收，提示直接占座；
     * - 已有在队/在飞条目：原样返回当前队位（幂等），重复请求不许插队；
     * - 已出局/已入座等终态：给可读原因，不能重新排队（防换状态插队）。
     */
    @Transactional
    public WaitlistPositionView join(WaitlistJoinRequest request) {
        LocalDateTime now = LocalDateTime.now();
        CapacityBatch cb = lockControl(request.getBatchId());
        assertWindowOpen(cb, now);
        Registration reg = requireRegistration(cb.getBatchId(), request.getRegNo());

        CapacitySeat seat = findSeat(cb.getBatchId(), reg.getRegNo());
        if (seat != null && CapacityConstants.SEAT_HELD.equals(seat.getStatus())) {
            throw new BusinessException("SEAT_ALREADY_HELD", "该学员已锁定座位，无需候补");
        }

        CapacityWaitlistEntry existing = findEntry(cb.getBatchId(), reg.getRegNo());
        if (existing != null) {
            if (CapacityConstants.ENTRY_WAITING.equals(existing.getStatus())
                    || CapacityConstants.ENTRY_OFFERING.equals(existing.getStatus())) {
                return toPositionView(existing, now, "您已在候补队列中，已返回原队位，重复请求不会插队");
            }
            throw new BusinessException("WAITLIST_TERMINAL",
                    "您的候补已是终态 " + existing.getStatus() + "，不能重新排队（防止重复请求插队）");
        }

        if (availableNow(cb) > 0) {
            throw new BusinessException("SEAT_AVAILABLE", "当前仍有空位，请直接报名占座，不必候补");
        }

        cb.setNextSeq(cb.getNextSeq() + 1);
        batchControlMapper.updateById(cb);

        CapacityWaitlistEntry entry = new CapacityWaitlistEntry();
        entry.setBatchId(cb.getBatchId());
        entry.setRegNo(reg.getRegNo());
        entry.setName(reg.getName());
        entry.setPhone(request.getPhone() != null ? request.getPhone() : reg.getPhone());
        entry.setSeq(cb.getNextSeq());
        entry.setStatus(CapacityConstants.ENTRY_WAITING);
        entry.setOfferCount(0);
        entryMapper.insert(entry);

        recordEvent(cb.getBatchId(), entry.getId(), null, null, entry.getRegNo(),
                CapacityConstants.EVENT_JOIN, null, CapacityConstants.ENTRY_WAITING,
                "入队序号 " + entry.getSeq());
        notify(cb.getBatchId(), entry, CapacityConstants.NOTIFY_JOINED,
                "候补入队成功",
                "您已进入本批次候补队列，队位序号 " + entry.getSeq()
                        + "。有人退位时将按序递补，递补通知带确认时限，超时未确认视为放弃。",
                null, OperatorHolder.current());

        // 临界情况：此前已退位但窗口未开放/无人排队的空位，入队时立即补位。
        dispatch(cb, now);
        return toPositionView(findEntry(cb.getBatchId(), reg.getRegNo()), now, "入队成功");
    }

    /** 主动退出候补：只对排队中/在飞生效；在飞退出同时把其递补单置 EXPIRED 并顺延。 */
    @Transactional
    public CapacityWaitlistEntry cancel(WaitlistCancelRequest request) {
        LocalDateTime now = LocalDateTime.now();
        CapacityBatch cb = lockControl(request.getBatchId());
        CapacityWaitlistEntry entry = findEntry(cb.getBatchId(), request.getRegNo());
        if (entry == null) {
            throw new BusinessException("WAITLIST_ENTRY_NOT_FOUND", "候补条目不存在");
        }
        if (CapacityConstants.ENTRY_SEATED.equals(entry.getStatus())
                || CapacityConstants.ENTRY_EXPIRED.equals(entry.getStatus())
                || CapacityConstants.ENTRY_CANCELLED.equals(entry.getStatus())
                || CapacityConstants.ENTRY_CLOSED.equals(entry.getStatus())) {
            throw new BusinessException("WAITLIST_TERMINAL", "候补已是终态 " + entry.getStatus() + "，不能退出");
        }
        String from = entry.getStatus();
        if (CapacityConstants.ENTRY_OFFERING.equals(from) && entry.getOfferId() != null) {
            CapacityOffer offer = offerMapper.selectById(entry.getOfferId());
            if (offer != null && CapacityConstants.OFFER_OFFERING.equals(offer.getStatus())) {
                offer.setStatus(CapacityConstants.OFFER_EXPIRED);
                offerMapper.updateById(offer);
                recordEvent(cb.getBatchId(), entry.getId(), offer.getId(), offer.getSeatId(), entry.getRegNo(),
                        CapacityConstants.EVENT_EXPIRE, CapacityConstants.OFFER_OFFERING,
                        CapacityConstants.OFFER_EXPIRED, "候补人在确认时限内主动退出，座位顺延");
            }
        }
        entry.setStatus(CapacityConstants.ENTRY_CANCELLED);
        entry.setCancelReason(request.getReason());
        entryMapper.updateById(entry);
        recordEvent(cb.getBatchId(), entry.getId(), null, null, entry.getRegNo(),
                CapacityConstants.EVENT_CANCEL, from, CapacityConstants.ENTRY_CANCELLED, request.getReason());

        dispatch(cb, now);
        return entry;
    }

    /** 查本人队位（透明）：序号、前面人数、在飞递补截止钟点、服务器当前时间一起返回。 */
    @Transactional(readOnly = true)
    public WaitlistPositionView position(Long batchId, String regNo) {
        LocalDateTime now = LocalDateTime.now();
        requireControl(batchId);
        CapacityWaitlistEntry entry = findEntry(batchId, regNo);
        if (entry == null) {
            throw new BusinessException("WAITLIST_ENTRY_NOT_FOUND", "候补条目不存在：" + regNo);
        }
        return toPositionView(entry, now, null);
    }

    // ============ 递补确认 ============

    /** 候补人确认递补：在时限内确认 → 座位过记本人并锁定；超时服务器时间直接判定出局并顺延。 */
    @Transactional
    public CapacitySeat confirm(OfferConfirmRequest request) {
        LocalDateTime now = LocalDateTime.now();
        CapacityBatch cb = lockControl(request.getBatchId());

        // 先按服务器时间结清已超时的在飞递补（顺延给下一位），避免踩点确认抢座。
        expireOffers(cb, now);

        CapacityWaitlistEntry entry = findEntry(cb.getBatchId(), request.getRegNo());
        if (entry == null || entry.getOfferId() == null) {
            throw new BusinessException("OFFER_NOT_FOUND", "当前没有待确认的递补通知：" + request.getRegNo());
        }
        CapacityOffer offer = offerMapper.selectById(entry.getOfferId());
        if (offer == null || !offer.getId().equals(entry.getOfferId())) {
            throw new BusinessException("OFFER_NOT_FOUND", "递补单与候补条目不匹配");
        }
        if (!CapacityConstants.OFFER_OFFERING.equals(offer.getStatus())) {
            throw new BusinessException("OFFER_NOT_ACTIVE", "该递补单已结束：" + offer.getStatus());
        }
        // 关窗前已发出的在飞递补：仍按各自截止钟点处理，时限内确认照常锁座（只是不再发起新一轮递补）。
        if (!offer.getRegNo().equals(request.getRegNo())
                || !CapacityConstants.ENTRY_OFFERING.equals(entry.getStatus())) {
            throw new BusinessException("OFFER_PERSON_MISMATCH", "递补单与确认人不一致");
        }
        if (!now.isBefore(offer.getExpireAt())) {
            // 双保险：锁内再判一次钟点。
            expireOffers(cb, now);
            throw new BusinessException("OFFER_EXPIRED", "已超过确认时限，座位已顺延给下一位");
        }
        CapacitySeat seat = seatMapper.selectById(offer.getSeatId());
        if (seat == null || !CapacityConstants.SEAT_RELEASED.equals(seat.getStatus())) {
            throw new BusinessException("SEAT_NOT_AVAILABLE", "空位状态已变化，无法确认");
        }

        // 递补成功：座位过记候补人并锁定。
        seat.setRegNo(entry.getRegNo());
        seat.setName(entry.getName());
        seat.setSource(CapacityConstants.SEAT_SOURCE_WAITLIST);
        seat.setEntryId(entry.getId());
        seat.setOfferId(offer.getId());
        seat.setStatus(CapacityConstants.SEAT_HELD);
        seat.setHeldAt(now);
        seat.setConfirmedAt(now);
        seat.setReleasedAt(null);
        seat.setReleaseReason(null);
        seatMapper.updateById(seat);

        offer.setStatus(CapacityConstants.OFFER_SEATED);
        offer.setConfirmedAt(now);
        offer.setOperator(OperatorHolder.current());
        offerMapper.updateById(offer);

        entry.setStatus(CapacityConstants.ENTRY_SEATED);
        entry.setSeatId(seat.getId());
        entry.setSeatedAt(now);
        entryMapper.updateById(entry);

        recordEvent(cb.getBatchId(), entry.getId(), offer.getId(), seat.getId(), entry.getRegNo(),
                CapacityConstants.EVENT_CONFIRM, CapacityConstants.ENTRY_OFFERING,
                CapacityConstants.ENTRY_SEATED, "递补确认成功，座位已锁定");
        notify(cb.getBatchId(), entry, CapacityConstants.NOTIFY_SEATED,
                "递补成功，座位已锁定",
                "您已递补成功，座位已锁定。请按批次安排准时参加。",
                offer.getId(), OperatorHolder.current());
        return seat;
    }

    // ============ 超时顺延 / 关窗兜底（定时任务与手动触发共用） ============

    /** 扫描全部批次：按服务器时间结清超时递补、关闭到点窗口，并顺延空位。 */
    @Transactional
    public Map<String, Object> scanAll() {
        LocalDateTime now = LocalDateTime.now();
        int expired = 0;
        int offered = 0;
        int closed = 0;
        List<Long> batchIds = new ArrayList<>();
        // 已关窗批次也要结清在飞递补（只出局不顺延）；故这里遍历全部批次，不限 ENABLED。
        for (CapacityBatch cb : batchControlMapper.selectList(null)) {
            boolean changed = false;
            int e = expireOffers(cb, now);
            if (e > 0) {
                expired += e;
                changed = true;
            }
            if (CapacityConstants.BATCH_ENABLED.equals(cb.getStatus())) {
                if (isWindowEnded(cb, now)) {
                    closeWindow(cb, now, CapacityConstants.CLOSE_REASON_WINDOW_END,
                            "候补窗口到点（" + cb.getWindowEndAt() + "）自动关闭");
                    closed++;
                    changed = true;
                } else {
                    int o = dispatch(cb, now);
                    if (o > 0) {
                        offered += o;
                        changed = true;
                    }
                }
            }
            if (changed) {
                batchIds.add(cb.getBatchId());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("expiredCount", expired);
        result.put("offeredCount", offered);
        result.put("closedBatchCount", closed);
        result.put("batchIds", batchIds);
        result.put("serverNow", now);
        return result;
    }

    /** 手动触发单批次的超时顺延处理。 */
    @Transactional
    public Map<String, Object> scanBatch(Long batchId) {
        LocalDateTime now = LocalDateTime.now();
        CapacityBatch cb = lockControl(batchId);
        int expired = expireOffers(cb, now);
        int offered = 0;
        boolean closed = false;
        if (isWindowEnded(cb, now)) {
            closeWindow(cb, now, CapacityConstants.CLOSE_REASON_WINDOW_END,
                    "候补窗口到点（" + cb.getWindowEndAt() + "）自动关闭");
            closed = true;
        } else {
            offered = dispatch(cb, now);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("expiredCount", expired);
        result.put("offeredCount", offered);
        result.put("closed", closed);
        result.put("serverNow", now);
        return result;
    }

    /**
     * 超时判定（只认服务器时间）：对所有到点未确认的在飞递补——
     * 递补单置 EXPIRED、本人候补条目置 EXPIRED 出局、发超时通知，随后空位由 dispatch 顺给下一位。
     * 返回本轮结清的超时递补单数。
     */
    private int expireOffers(CapacityBatch cb, LocalDateTime now) {
        List<CapacityOffer> due = offerMapper.selectList(new QueryWrapper<CapacityOffer>()
                .eq("batch_id", cb.getBatchId())
                .eq("status", CapacityConstants.OFFER_OFFERING)
                .le("expire_at", now)
                .orderByAsc("expire_at", "id"));
        for (CapacityOffer offer : due) {
            offer.setStatus(CapacityConstants.OFFER_EXPIRED);
            offerMapper.updateById(offer);

            CapacityWaitlistEntry entry = entryMapper.selectById(offer.getEntryId());
            if (entry != null && CapacityConstants.ENTRY_OFFERING.equals(entry.getStatus())
                    && offer.getId().equals(entry.getOfferId())) {
                entry.setStatus(CapacityConstants.ENTRY_EXPIRED);
                entry.setOfferExpiresAt(offer.getExpireAt());
                entryMapper.updateById(entry);
                notify(cb.getBatchId(), entry, CapacityConstants.NOTIFY_EXPIRED,
                        "递补确认超时，已顺延下一位",
                        "您的递补确认已于 " + offer.getExpireAt() + " 截止（服务器时间），未收到确认，"
                                + "视为放弃，座位已按序顺延给下一位。",
                        offer.getId(), "system");
                recordEvent(cb.getBatchId(), entry.getId(), offer.getId(), offer.getSeatId(),
                        entry.getRegNo(), CapacityConstants.EVENT_EXPIRE,
                        CapacityConstants.ENTRY_OFFERING, CapacityConstants.ENTRY_EXPIRED,
                        "确认截止 " + offer.getExpireAt() + " 未确认，顺给下一位");
            } else {
                recordEvent(cb.getBatchId(), offer.getEntryId(), offer.getId(), offer.getSeatId(),
                        offer.getRegNo(), CapacityConstants.EVENT_EXPIRE,
                        CapacityConstants.OFFER_OFFERING, CapacityConstants.OFFER_EXPIRED,
                        "递补单超时结束");
            }
        }
        if (!due.isEmpty() && !isWindowEnded(cb, now)) {
            dispatch(cb, now);
        }
        return due.size();
    }

    /**
     * 顺延/发起递补（不漏发）：循环为「尚无在飞递补的空位」各取队首发起通知，直到无空位或无人可补。
     * 返回新发起的递补单数。
     */
    private int dispatch(CapacityBatch cb, LocalDateTime now) {
        if (CapacityConstants.BATCH_CLOSED.equals(cb.getStatus()) || isWindowEnded(cb, now)) {
            return 0;
        }
        CapacityConfig config = ruleService.readConfig(cb.getConfigSnapshot());
        int created = 0;
        while (true) {
            // 取一个尚未被在飞递补占用的空位（超时轮的旧单已 EXPIRED，不会再被选中）。
            CapacitySeat vacant = seatMapper.selectOne(new QueryWrapper<CapacitySeat>()
                    .eq("batch_id", cb.getBatchId())
                    .eq("status", CapacityConstants.SEAT_RELEASED)
                    .notExists("SELECT 1 FROM t_capacity_offer o WHERE o.seat_id = t_capacity_seat.id "
                            + "AND o.status = '" + CapacityConstants.OFFER_OFFERING + "'")
                    .orderByAsc("released_at", "id")
                    .last("LIMIT 1"));
            if (vacant == null) {
                break;
            }
            CapacityWaitlistEntry head = entryMapper.selectOne(new QueryWrapper<CapacityWaitlistEntry>()
                    .eq("batch_id", cb.getBatchId())
                    .eq("status", CapacityConstants.ENTRY_WAITING)
                    .orderByAsc("seq")
                    .last("LIMIT 1"));
            if (head == null) {
                break;
            }

            int round = offerMapper.selectCount(new QueryWrapper<CapacityOffer>()
                    .eq("seat_id", vacant.getId())).intValue() + 1;
            LocalDateTime expireAt = now.plusMinutes(config.getOfferTimeoutMinutes());

            CapacityOffer offer = new CapacityOffer();
            offer.setBatchId(cb.getBatchId());
            offer.setSeatId(vacant.getId());
            offer.setRoundNo(round);
            offer.setEntryId(head.getId());
            offer.setRegNo(head.getRegNo());
            offer.setName(head.getName());
            offer.setStatus(CapacityConstants.OFFER_OFFERING);
            offer.setOfferedAt(now);
            offer.setExpireAt(expireAt);
            offerMapper.insert(offer);

            head.setStatus(CapacityConstants.ENTRY_OFFERING);
            head.setOfferId(offer.getId());
            head.setOfferedAt(now);
            head.setOfferExpiresAt(expireAt);
            head.setOfferCount(head.getOfferCount() == null ? 1 : head.getOfferCount() + 1);
            entryMapper.updateById(head);

            CapacityNotification n = notify(cb.getBatchId(), head, CapacityConstants.NOTIFY_OFFER,
                    "轮到您递补，请在时限内确认",
                    "有名额空缺，按候补顺序已轮到您（队位序号 " + head.getSeq() + "，第 " + round + " 轮递补）。"
                            + "请于 " + expireAt + "（服务器时间）前确认，超时未确认视为放弃并顺给下一位。",
                    offer.getId(), "system");
            offer.setNotifiedAt(n.getNotifiedAt());
            offer.setNotificationId(n.getId());
            offerMapper.updateById(offer);

            recordEvent(cb.getBatchId(), head.getId(), offer.getId(), vacant.getId(), head.getRegNo(),
                    CapacityConstants.EVENT_OFFER, CapacityConstants.ENTRY_WAITING,
                    CapacityConstants.ENTRY_OFFERING,
                    "空位 id=" + vacant.getId() + " 第 " + round + " 轮递补，截止 " + expireAt);
            created++;
        }
        return created;
    }

    // ============ 查询：队列总览 / 列表 / 通知 ============

    @Transactional(readOnly = true)
    public QueueStatusView status(Long batchId, boolean includeHistory) {
        LocalDateTime now = LocalDateTime.now();
        CapacityBatch cb = requireControl(batchId);
        ExamBatch batch = examBatchMapper.selectById(batchId);

        QueueStatusView view = new QueueStatusView();
        view.setBatchId(batchId);
        view.setBatchCode(batch == null ? null : batch.getCode());
        view.setBatchName(batch == null ? null : batch.getName());
        view.setCbId(cb.getId());
        view.setRuleId(cb.getRuleId());
        view.setRuleVersionNo(cb.getRuleVersionNo());
        view.setControlStatus(cb.getStatus());
        view.setWindowState(resolveWindowState(cb, now));
        view.setWindowStartAt(cb.getWindowStartAt());
        view.setWindowEndAt(cb.getWindowEndAt());
        view.setServerNow(now);
        view.setTotalCapacity(cb.getTotalCapacity());

        List<CapacitySeat> seats = seatMapper.selectList(new QueryWrapper<CapacitySeat>()
                .eq("batch_id", batchId));
        int held = 0;
        int direct = 0;
        int seated = 0;
        int released = 0;
        for (CapacitySeat s : seats) {
            if (CapacityConstants.SEAT_HELD.equals(s.getStatus())) {
                held++;
                if (CapacityConstants.SEAT_SOURCE_WAITLIST.equals(s.getSource())) {
                    seated++;
                } else {
                    direct++;
                }
            } else if (CapacityConstants.SEAT_RELEASED.equals(s.getStatus())) {
                released++;
            }
        }
        view.setDirectHeldCount(direct);
        view.setWaitlistSeatedCount(seated);
        view.setReleasedSeatCount(released);
        view.setOfferingCount(countEntry(batchId, CapacityConstants.ENTRY_OFFERING));
        view.setWaitingCount(countEntry(batchId, CapacityConstants.ENTRY_WAITING));
        view.setSeatedCount(countEntry(batchId, CapacityConstants.ENTRY_SEATED));
        view.setExpiredCount(countEntry(batchId, CapacityConstants.ENTRY_EXPIRED));
        view.setCancelledCount(countEntry(batchId, CapacityConstants.ENTRY_CANCELLED));
        view.setClosedCount(countEntry(batchId, CapacityConstants.ENTRY_CLOSED));

        List<String> activeStatuses = new ArrayList<>();
        activeStatuses.add(CapacityConstants.ENTRY_WAITING);
        activeStatuses.add(CapacityConstants.ENTRY_OFFERING);
        QueryWrapper<CapacityWaitlistEntry> qw = new QueryWrapper<CapacityWaitlistEntry>()
                .eq("batch_id", batchId);
        if (includeHistory) {
            // 全部状态按序号列出
            qw.orderByAsc("seq");
        } else {
            qw.in("status", activeStatuses).orderByAsc("seq");
        }
        List<CapacityWaitlistEntry> entries = entryMapper.selectList(qw);

        long waitingSeen = 0;
        List<QueueEntryItem> items = new ArrayList<>();
        for (CapacityWaitlistEntry e : entries) {
            QueueEntryItem item = new QueueEntryItem();
            item.setEntryId(e.getId());
            item.setSeq(e.getSeq());
            item.setRegNo(e.getRegNo());
            item.setName(e.getName());
            item.setStatus(e.getStatus());
            item.setOfferId(e.getOfferId());
            item.setOfferedAt(e.getOfferedAt());
            item.setOfferExpiresAt(e.getOfferExpiresAt());
            item.setOfferCount(e.getOfferCount());
            if (CapacityConstants.ENTRY_WAITING.equals(e.getStatus())) {
                waitingSeen++;
                item.setPosition(waitingSeen);
            }
            items.add(item);
        }
        view.setQueue(items);
        return view;
    }

    @Transactional(readOnly = true)
    public List<CapacityNotification> notifications(String regNo, boolean unreadOnly) {
        QueryWrapper<CapacityNotification> wrapper = new QueryWrapper<CapacityNotification>()
                .orderByDesc("notified_at", "id");
        if (regNo != null && !regNo.trim().isEmpty()) {
            wrapper.eq("reg_no", regNo.trim());
        }
        if (unreadOnly) {
            wrapper.eq("status", CapacityConstants.NOTIFY_NEW);
        }
        return notificationMapper.selectList(wrapper);
    }

    @Transactional
    public CapacityNotification markRead(Long id) {
        CapacityNotification n = notificationMapper.selectById(id);
        if (n == null) {
            throw new BusinessException("CAPACITY_NOTIFICATION_NOT_FOUND", "通知不存在：id=" + id);
        }
        if (CapacityConstants.NOTIFY_NEW.equals(n.getStatus())) {
            n.setStatus(CapacityConstants.NOTIFY_READ);
            n.setReadAt(LocalDateTime.now());
            notificationMapper.updateById(n);
        }
        return n;
    }

    /** 递补单流水（一个空位一轮一条）：可按批次核对每轮顺延与通知。 */
    @Transactional(readOnly = true)
    public List<CapacityOffer> listOffers(Long batchId) {
        requireControl(batchId);
        return offerMapper.selectList(new QueryWrapper<CapacityOffer>()
                .eq("batch_id", batchId)
                .orderByAsc("seat_id", "round_no"));
    }

    /** 事件流水（只追加查询）：入队/退位/递补/确认/超时/关窗全程可查。 */
    @Transactional(readOnly = true)
    public List<CapacityEvent> events(Long batchId) {
        return eventMapper.selectList(new QueryWrapper<CapacityEvent>()
                .eq("batch_id", batchId)
                .orderByAsc("id"));
    }

    @Transactional(readOnly = true)
    public CapacityBatch getControl(Long batchId) {
        return requireControl(batchId);
    }

    // ============ 内部辅助 ============

    private CapacitySeat insertDirectSeat(CapacityBatch cb, String regNo, String name, LocalDateTime now) {
        CapacitySeat seat = new CapacitySeat();
        seat.setBatchId(cb.getBatchId());
        seat.setRegNo(regNo);
        seat.setName(name);
        seat.setSource(CapacityConstants.SEAT_SOURCE_DIRECT);
        seat.setStatus(CapacityConstants.SEAT_HELD);
        seat.setHeldAt(now);
        seat.setConfirmedAt(now);
        seatMapper.insert(seat);
        recordEvent(cb.getBatchId(), null, null, seat.getId(), regNo,
                CapacityConstants.EVENT_SEAT_HELD, null, CapacityConstants.SEAT_HELD, "直接占座");
        return seat;
    }

    /** 当前可用于直接报名的空位数：总容量 - 锁定座位 - 已退位（全部预留给候补递补）。 */
    private int availableNow(CapacityBatch cb) {
        long held = seatMapper.selectCount(new QueryWrapper<CapacitySeat>()
                .eq("batch_id", cb.getBatchId())
                .eq("status", CapacityConstants.SEAT_HELD));
        long released = seatMapper.selectCount(new QueryWrapper<CapacitySeat>()
                .eq("batch_id", cb.getBatchId())
                .eq("status", CapacityConstants.SEAT_RELEASED));
        int available = cb.getTotalCapacity() - (int) held - (int) released;
        return Math.max(available, 0);
    }

    private int countEntry(Long batchId, String status) {
        return entryMapper.selectCount(new QueryWrapper<CapacityWaitlistEntry>()
                .eq("batch_id", batchId)
                .eq("status", status)).intValue();
    }

    private void assertWindowOpen(CapacityBatch cb, LocalDateTime now) {
        String state = resolveWindowState(cb, now);
        if ("CLOSED".equals(state)) {
            throw new BusinessException("WAITLIST_WINDOW_CLOSED",
                    "候补窗口已关闭" + (cb.getWindowEndAt() != null ? "（截止 " + cb.getWindowEndAt() + "）" : ""));
        }
    }

    private String resolveWindowState(CapacityBatch cb, LocalDateTime now) {
        if (CapacityConstants.BATCH_CLOSED.equals(cb.getStatus())) {
            return "CLOSED";
        }
        if (cb.getWindowStartAt() != null && now.isBefore(cb.getWindowStartAt())) {
            return "NOT_OPENED";
        }
        if (isWindowEnded(cb, now)) {
            return "CLOSED";
        }
        return "OPEN";
    }

    private boolean isWindowEnded(CapacityBatch cb, LocalDateTime now) {
        return cb.getWindowEndAt() != null && !now.isBefore(cb.getWindowEndAt());
    }

    /** 窗口终点推导：开考日期 + 开考时间 - 提前小时数；开考时间未知则不自动关闭（返回 null）。 */
    private LocalDateTime deriveWindowEnd(ExamBatch batch, CapacityConfig config) {
        LocalDate examDate = batch.getExamDate();
        LocalTime startTime = batch.getStartTime();
        Integer hoursBefore = config.getWaitlistEndHoursBeforeExam();
        if (examDate == null || startTime == null || hoursBefore == null) {
            return null;
        }
        return LocalDateTime.of(examDate, startTime).minusHours(hoursBefore);
    }

    private int sumSiteCapacity(Long siteId) {
        List<ExamRoom> rooms = roomMapper.selectList(new QueryWrapper<ExamRoom>()
                .eq("site_id", siteId)
                .eq("status", "ENABLED"));
        int sum = 0;
        for (ExamRoom room : rooms) {
            sum += room.getCapacity() == null ? 0 : room.getCapacity();
        }
        return sum;
    }

    private CapacityNotification notify(Long batchId, CapacityWaitlistEntry entry, String type,
                                        String title, String content, Long offerId, String operator) {
        CapacityNotification n = new CapacityNotification();
        n.setBatchId(batchId);
        n.setRegNo(entry.getRegNo());
        n.setName(entry.getName());
        n.setNotifyType(type);
        n.setTitle(title);
        n.setContent(content);
        n.setStatus(CapacityConstants.NOTIFY_NEW);
        n.setOfferId(offerId);
        n.setEntryId(entry.getId());
        n.setOperator(operator);
        n.setNotifiedAt(LocalDateTime.now());
        notificationMapper.insert(n);
        return n;
    }

    private void recordEvent(Long batchId, Long entryId, Long offerId, Long seatId, String regNo,
                             String eventType, String fromStatus, String toStatus, String detail) {
        CapacityEvent e = new CapacityEvent();
        e.setBatchId(batchId);
        e.setEntryId(entryId);
        e.setOfferId(offerId);
        e.setSeatId(seatId);
        e.setRegNo(regNo);
        e.setEventType(eventType);
        e.setFromStatus(fromStatus);
        e.setToStatus(toStatus);
        e.setDetail(detail);
        e.setOperator(OperatorHolder.current());
        e.setEventAt(LocalDateTime.now());
        eventMapper.insert(e);
    }

    private WaitlistPositionView toPositionView(CapacityWaitlistEntry entry, LocalDateTime now, String message) {
        WaitlistPositionView v = new WaitlistPositionView();
        v.setEntryId(entry.getId());
        v.setBatchId(entry.getBatchId());
        v.setRegNo(entry.getRegNo());
        v.setName(entry.getName());
        v.setSeq(entry.getSeq());
        v.setStatus(entry.getStatus());
        v.setOfferId(entry.getOfferId());
        v.setOfferedAt(entry.getOfferedAt());
        v.setOfferExpiresAt(entry.getOfferExpiresAt());
        v.setServerNow(now);
        v.setMessage(message);
        if (CapacityConstants.ENTRY_WAITING.equals(entry.getStatus())) {
            long ahead = entryMapper.selectCount(new QueryWrapper<CapacityWaitlistEntry>()
                    .eq("batch_id", entry.getBatchId())
                    .eq("status", CapacityConstants.ENTRY_WAITING)
                    .lt("seq", entry.getSeq()));
            v.setAheadCount(ahead);
            v.setPosition(ahead + 1);
        } else if (CapacityConstants.ENTRY_OFFERING.equals(entry.getStatus())) {
            v.setAheadCount(0L);
            v.setPosition(1L);
        }
        return v;
    }

    private CapacityWaitlistEntry findEntry(Long batchId, String regNo) {
        return entryMapper.selectOne(new QueryWrapper<CapacityWaitlistEntry>()
                .eq("batch_id", batchId)
                .eq("reg_no", regNo.trim()));
    }

    private CapacitySeat findSeat(Long batchId, String regNo) {
        return seatMapper.selectOne(new QueryWrapper<CapacitySeat>()
                .eq("batch_id", batchId)
                .eq("reg_no", regNo.trim()));
    }

    private Registration requireRegistration(Long batchId, String regNo) {
        if (regNo == null || regNo.trim().isEmpty()) {
            throw new BusinessException("REG_NO_REQUIRED", "报名号不能为空");
        }
        Registration reg = registrationMapper.selectOne(new QueryWrapper<Registration>()
                .eq("reg_no", regNo.trim()));
        if (reg == null || !batchId.equals(reg.getBatchId())) {
            throw new BusinessException("CAPACITY_REG_NOT_FOUND",
                    "报名号不属于本批次或名册中不存在：" + regNo.trim());
        }
        return reg;
    }

    private ExamBatch requireExamBatch(Long batchId) {
        if (batchId == null) {
            throw new BusinessException("BATCH_ID_REQUIRED", "批次 id 不能为空");
        }
        ExamBatch batch = examBatchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("BATCH_NOT_FOUND", "考试批次不存在：id=" + batchId);
        }
        return batch;
    }

    private CapacityBatch findControl(Long batchId) {
        return batchControlMapper.selectOne(new QueryWrapper<CapacityBatch>().eq("batch_id", batchId));
    }

    private CapacityBatch requireControl(Long batchId) {
        CapacityBatch cb = findControl(batchId);
        if (cb == null) {
            throw new BusinessException("CAPACITY_NOT_ENABLED", "该批次尚未启用容量控制：batchId=" + batchId);
        }
        return cb;
    }

    /** 批次写操作入口：行锁串行化，保证序号发放/空位递补/超时顺延的一致性。 */
    private CapacityBatch lockControl(Long batchId) {
        requireExamBatch(batchId);
        CapacityBatch cb = batchControlMapper.selectByBatchForUpdate(batchId);
        if (cb == null) {
            throw new BusinessException("CAPACITY_NOT_ENABLED", "该批次尚未启用容量控制：batchId=" + batchId);
        }
        return cb;
    }
}
