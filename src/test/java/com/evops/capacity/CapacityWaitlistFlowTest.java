package com.evops.capacity;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.capacity.common.CapacityConstants;
import com.evops.capacity.dto.CapacityConfig;
import com.evops.capacity.dto.CapacityEnableRequest;
import com.evops.capacity.dto.CapacityRuleRequest;
import com.evops.capacity.dto.CapacityRuleVersionRequest;
import com.evops.capacity.dto.OfferConfirmRequest;
import com.evops.capacity.dto.SeatHoldRequest;
import com.evops.capacity.dto.SeatReleaseRequest;
import com.evops.capacity.dto.WaitlistJoinRequest;
import com.evops.capacity.entity.CapacityRuleVersion;
import com.evops.capacity.entity.CapacitySeat;
import com.evops.capacity.entity.CapacityWaitlistEntry;
import com.evops.capacity.mapper.CapacityBatchMapper;
import com.evops.capacity.mapper.CapacityNotificationMapper;
import com.evops.capacity.mapper.CapacityOfferMapper;
import com.evops.capacity.service.CapacityRuleService;
import com.evops.capacity.service.CapacityWaitlistService;
import com.evops.capacity.vo.QueueStatusView;
import com.evops.capacity.vo.WaitlistPositionView;
import com.evops.common.BusinessException;
import com.evops.exam.entity.ExamBatch;
import com.evops.exam.entity.ExamSite;
import com.evops.exam.entity.Registration;
import com.evops.exam.entity.WorkspaceVersion;
import com.evops.exam.mapper.ExamBatchMapper;
import com.evops.exam.mapper.ExamSiteMapper;
import com.evops.exam.mapper.RegistrationMapper;
import com.evops.exam.service.WorkspaceVersionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旺季满员候补与递补端到端：
 * 规则三段式（按考点配窗口/时限）→ 满员入队、位置透明、重复不插队
 * → 退位按序递补、通知带服务器时限、确认锁座 → 超时出局顺给下一位、不漏发
 * → 窗口关闭不再递补 → 启用冻结规则版本 → 工作区快照回退。
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CapacityWaitlistFlowTest {

    @Autowired
    private CapacityRuleService ruleService;
    @Autowired
    private CapacityWaitlistService waitlistService;
    @Autowired
    private ExamSiteMapper siteMapper;
    @Autowired
    private ExamBatchMapper batchMapper;
    @Autowired
    private RegistrationMapper registrationMapper;
    @Autowired
    private CapacityBatchMapper capacityBatchMapper;
    @Autowired
    private CapacityOfferMapper offerMapper;
    @Autowired
    private CapacityNotificationMapper notificationMapper;
    @Autowired
    private WorkspaceVersionService workspaceVersionService;

    // ---------- 辅助 ----------

    private ExamBatch createBatchWithSite(String code) {
        ExamSite site = new ExamSite();
        site.setCode("S" + code);
        site.setName("考点" + code);
        site.setStatus("ENABLED");
        siteMapper.insert(site);
        ExamBatch batch = new ExamBatch();
        batch.setCode(code);
        batch.setName("批次" + code);
        batch.setSiteId(site.getId());
        batch.setProjectCode("DEFAULT");
        batch.setStatus("CREATED");
        batchMapper.insert(batch);
        return batch;
    }

    private CapacityRuleVersion publishRule(String ruleCode, Long siteId, int timeoutMinutes,
                                            Integer endHoursBefore) {
        CapacityRuleRequest rr = new CapacityRuleRequest();
        rr.setCode(ruleCode);
        rr.setName("容量规则-" + ruleCode);
        rr.setSiteId(siteId);
        ruleService.createRule(rr);
        Long ruleId = ruleService.findRuleBySite(siteId).getId();

        CapacityConfig config = new CapacityConfig();
        config.setWaitlistEnabled(true);
        config.setOfferTimeoutMinutes(timeoutMinutes);
        config.setWaitlistEndHoursBeforeExam(endHoursBefore);
        CapacityRuleVersionRequest vr = new CapacityRuleVersionRequest();
        vr.setConfig(config);
        CapacityRuleVersion draft = ruleService.createDraftVersion(ruleId, vr);
        return ruleService.publishVersion(draft.getId());
    }

    private void enableControl(ExamBatch batch, int capacity, LocalDateTime start, LocalDateTime end) {
        CapacityEnableRequest req = new CapacityEnableRequest();
        req.setBatchId(batch.getId());
        req.setTotalCapacity(capacity);
        req.setWindowStartAt(start);
        req.setWindowEndAt(end);
        waitlistService.enable(req);
    }

    private void reg(ExamBatch batch, String regNo, String name) {
        Registration r = new Registration();
        r.setRegNo(regNo);
        r.setName(name);
        r.setBatchId(batch.getId());
        r.setStatus("REGISTERED");
        registrationMapper.insert(r);
    }

    private void hold(ExamBatch batch, String regNo) {
        SeatHoldRequest req = new SeatHoldRequest();
        req.setBatchId(batch.getId());
        req.setRegNo(regNo);
        waitlistService.holdSeat(req);
    }

    private WaitlistPositionView join(ExamBatch batch, String regNo, String phone) {
        WaitlistJoinRequest req = new WaitlistJoinRequest();
        req.setBatchId(batch.getId());
        req.setRegNo(regNo);
        req.setPhone(phone);
        return waitlistService.join(req);
    }

    private void release(ExamBatch batch, String regNo) {
        SeatReleaseRequest req = new SeatReleaseRequest();
        req.setBatchId(batch.getId());
        req.setRegNo(regNo);
        waitlistService.releaseSeat(req);
    }

    private CapacitySeat confirm(ExamBatch batch, String regNo) {
        OfferConfirmRequest req = new OfferConfirmRequest();
        req.setBatchId(batch.getId());
        req.setRegNo(regNo);
        return waitlistService.confirm(req);
    }

    private com.evops.capacity.entity.CapacityOffer activeOffer(ExamBatch batch, String regNo) {
        return offerMapper.selectOne(new QueryWrapper<com.evops.capacity.entity.CapacityOffer>()
                .eq("batch_id", batch.getId())
                .eq("reg_no", regNo)
                .eq("status", CapacityConstants.OFFER_OFFERING)
                .orderByDesc("id")
                .last("LIMIT 1"));
    }

    private long notifications(String regNo, String type) {
        return notificationMapper.selectCount(new QueryWrapper<com.evops.capacity.entity.CapacityNotification>()
                .eq("reg_no", regNo)
                .eq("notify_type", type));
    }

    /** 测试专用：把批次候补窗口终点改到过去，模拟到点（只动数据，不动代码口径）。 */
    private void moveWindowEndToPast(ExamBatch batch) {
        com.evops.capacity.entity.CapacityBatch cb = capacityBatchMapper.selectOne(
                new QueryWrapper<com.evops.capacity.entity.CapacityBatch>()
                        .eq("batch_id", batch.getId()));
        cb.setWindowEndAt(LocalDateTime.now().minusMinutes(1));
        capacityBatchMapper.updateById(cb);
    }

    // ---------- 用例 ----------

    @Test
    void fullWaitlistFlow_positionTransparent_noJump_timeoutRollsForward_seatLocked() {
        ExamBatch batch = createBatchWithSite("CW01");
        publishRule("CAPCW01", batch.getSiteId(), 30, null);
        LocalDateTime now = LocalDateTime.now();
        enableControl(batch, 2, now.minusHours(1), now.plusDays(1));

        reg(batch, "R1", "甲");
        reg(batch, "R2", "乙");
        reg(batch, "W1", "丙");
        reg(batch, "W2", "丁");
        reg(batch, "W3", "戊");
        hold(batch, "R1");
        hold(batch, "R2");

        // 满员：直接占座被拒
        BusinessException full = assertThrows(BusinessException.class, () -> hold(batch, "W1"));
        assertEquals("CAPACITY_FULL", full.getCode());

        // 入队：序号只增，位置透明
        WaitlistPositionView p1 = join(batch, "W1", "13800000001");
        assertEquals(1L, p1.getSeq());
        assertEquals(1L, p1.getPosition());
        assertEquals(0L, p1.getAheadCount());
        WaitlistPositionView p2 = join(batch, "W2", null);
        assertEquals(2L, p2.getSeq());
        assertEquals(2L, p2.getPosition());
        assertEquals(1L, p2.getAheadCount());

        // 重复请求：返回原队位，不插队
        WaitlistPositionView p2Again = join(batch, "W2", null);
        assertEquals(2L, p2Again.getSeq());
        assertEquals(2L, p2Again.getPosition());

        // 甲退位 → 队首 W1 收到第一轮递补通知（带服务器时限）
        release(batch, "R1");
        com.evops.capacity.entity.CapacityOffer offerW1 = activeOffer(batch, "W1");
        assertNotNull(offerW1);
        assertEquals(1, offerW1.getRoundNo());
        assertTrue(offerW1.getExpireAt().isAfter(LocalDateTime.now()));
        assertEquals(1, notifications("W1", CapacityConstants.NOTIFY_OFFER));

        // 确认成功：座位过记 W1 并锁定
        CapacitySeat seat = confirm(batch, "W1");
        assertEquals(CapacityConstants.SEAT_SOURCE_WAITLIST, seat.getSource());
        assertEquals(CapacityConstants.SEAT_HELD, seat.getStatus());
        assertEquals("W1", seat.getRegNo());
        assertEquals(1, notifications("W1", CapacityConstants.NOTIFY_SEATED));

        // 乙退位 → W2 收到递补；把截止钟点改到过去模拟超时，scan 按服务器时间判定出局
        release(batch, "R2");
        com.evops.capacity.entity.CapacityOffer offerW2 = activeOffer(batch, "W2");
        assertNotNull(offerW2);
        offerW2.setExpireAt(LocalDateTime.now().minusMinutes(1));
        offerMapper.updateById(offerW2);
        waitlistService.scanBatch(batch.getId());
        assertEquals(CapacityConstants.OFFER_EXPIRED,
                offerMapper.selectById(offerW2.getId()).getStatus());
        assertEquals(1, notifications("W2", CapacityConstants.NOTIFY_EXPIRED));
        // 超时出局者不能重新排队（防插队）
        BusinessException terminal = assertThrows(BusinessException.class, () -> join(batch, "W2", null));
        assertEquals("WAITLIST_TERMINAL", terminal.getCode());

        // 新人 W3 入队即顺延到该空位第二轮递补，通知不漏发
        WaitlistPositionView p3 = join(batch, "W3", null);
        assertEquals(3L, p3.getSeq());
        com.evops.capacity.entity.CapacityOffer offerW3 = activeOffer(batch, "W3");
        assertNotNull(offerW3);
        assertEquals(2, offerW3.getRoundNo());
        confirm(batch, "W3");

        // 总览：容量 2 全锁、空位 0、递补锁座 2、超时出局 1
        QueueStatusView status = waitlistService.status(batch.getId(), false);
        assertEquals(2, status.getTotalCapacity());
        assertEquals(0, status.getReleasedSeatCount());
        assertEquals(2, status.getWaitlistSeatedCount());
        assertEquals(2, status.getSeatedCount());
        assertEquals(1, status.getExpiredCount());
        assertEquals(0, status.getOfferingCount());
        assertEquals(0, status.getWaitingCount());
        assertEquals("OPEN", status.getWindowState());

        // 逐轮递补单：乙座位 第1轮 EXPIRED、第2轮 SEATED
        List<com.evops.capacity.entity.CapacityOffer> offers = waitlistService.listOffers(batch.getId());
        long seat2Rounds = offers.stream().filter(o -> o.getSeatId().equals(offerW2.getSeatId())).count();
        assertEquals(2, seat2Rounds);
        // 事件流水完整
        assertTrue(waitlistService.events(batch.getId()).size() >= 10);
    }

    @Test
    void ruleVersionFrozenAtEnable_andValidationRejectsBadTimeout() {
        ExamBatch batch = createBatchWithSite("CW02");
        CapacityRuleVersion v1 = publishRule("CAPCW02", batch.getSiteId(), 30, null);
        enableControl(batch, 5, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusDays(1));
        assertEquals(v1.getVersionNo(), waitlistService.getControl(batch.getId()).getRuleVersionNo());

        // 发布新版不影响已启用批次冻结口径
        Long ruleId = ruleService.findRuleBySite(batch.getSiteId()).getId();
        CapacityConfig c2 = new CapacityConfig();
        c2.setOfferTimeoutMinutes(90);
        c2.setWaitlistEndHoursBeforeExam(48);
        CapacityRuleVersionRequest vr = new CapacityRuleVersionRequest();
        vr.setConfig(c2);
        CapacityRuleVersion v2 = ruleService.publishVersion(
                ruleService.createDraftVersion(ruleId, vr).getId());
        assertEquals(CapacityConstants.VERSION_DISABLED, ruleService.getVersion(v1.getId()).getStatus());
        assertEquals(v1.getVersionNo(), waitlistService.getControl(batch.getId()).getRuleVersionNo());
        assertEquals(30, ruleService.readConfig(
                waitlistService.getControl(batch.getId()).getConfigSnapshot())
                .getOfferTimeoutMinutes().intValue());
        assertEquals(v2.getVersionNo(), ruleService.findActiveVersion(ruleId).getVersionNo());

        // 非法时限整批拒收
        CapacityConfig bad = new CapacityConfig();
        bad.setOfferTimeoutMinutes(0);
        CapacityRuleVersionRequest badReq = new CapacityRuleVersionRequest();
        badReq.setConfig(bad);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> ruleService.createDraftVersion(ruleId, badReq));
        assertEquals("PARAM_OFFER_TIMEOUT_INVALID", ex.getCode());
    }

    @Test
    void windowEndAutoCloses_noJoin_noNewOffer_waitingMarkedClosed() {
        LocalDateTime now = LocalDateTime.now();

        // 窗口已到点：不能候补
        ExamBatch b1 = createBatchWithSite("CW03");
        publishRule("CAPCW03", b1.getSiteId(), 30, null);
        enableControl(b1, 2, now.minusHours(2), now.minusHours(1));
        reg(b1, "C1", "辰");
        BusinessException closed = assertThrows(BusinessException.class, () -> join(b1, "C1", null));
        assertEquals("WAITLIST_WINDOW_CLOSED", closed.getCode());
        assertEquals("CLOSED", waitlistService.status(b1.getId(), false).getWindowState());

        // 开放窗口中排队：到点 scan 自动关窗，排队人员置 CLOSED 并通知，退位也不再递补
        ExamBatch b2 = createBatchWithSite("CW04");
        publishRule("CAPCW04", b2.getSiteId(), 30, null);
        enableControl(b2, 1, now.minusHours(2), now.plusMinutes(5));
        reg(b2, "D1", "己");
        reg(b2, "D2", "庚");
        hold(b2, "D1");
        join(b2, "D2", null);

        moveWindowEndToPast(b2);
        waitlistService.scanBatch(b2.getId());
        assertEquals(CapacityConstants.BATCH_CLOSED, waitlistService.getControl(b2.getId()).getStatus());
        assertEquals(CapacityConstants.ENTRY_CLOSED,
                waitlistService.position(b2.getId(), "D2").getStatus());
        assertEquals(1, notifications("D2", CapacityConstants.NOTIFY_QUEUE_CLOSED));

        // 关窗后即便有人退位，也不再发起递补（不漏发也不错发）
        release(b2, "D1");
        assertEquals(0, waitlistService.listOffers(b2.getId()).size());

        // 关窗前已发出的在飞递补：关窗后时限内仍可确认锁座（只是不再发起新一轮）
        ExamBatch b3 = createBatchWithSite("CW05B");
        publishRule("CAPCW05B", b3.getSiteId(), 30, null);
        enableControl(b3, 1, now.minusHours(2), now.plusMinutes(5));
        reg(b3, "F1", "子");
        reg(b3, "F2", "丑");
        hold(b3, "F1");
        join(b3, "F2", null);
        release(b3, "F1");
        assertNotNull(activeOffer(b3, "F2"));
        moveWindowEndToPast(b3);
        waitlistService.scanBatch(b3.getId());
        assertEquals(CapacityConstants.BATCH_CLOSED, waitlistService.getControl(b3.getId()).getStatus());
        assertEquals(CapacityConstants.ENTRY_OFFERING,
                waitlistService.position(b3.getId(), "F2").getStatus());
        CapacitySeat seatF2 = confirm(b3, "F2");
        assertEquals(CapacityConstants.SEAT_HELD, seatF2.getStatus());
        assertEquals(CapacityConstants.ENTRY_SEATED,
                waitlistService.position(b3.getId(), "F2").getStatus());

        // 兜底扫描幂等
        waitlistService.scanAll();
        assertEquals(CapacityConstants.BATCH_CLOSED, waitlistService.getControl(b2.getId()).getStatus());
    }

    @Test
    void snapshotRollbackRestoresInFlightOffer() {
        ExamBatch batch = createBatchWithSite("CW05");
        publishRule("CAPCW05", batch.getSiteId(), 30, null);
        enableControl(batch, 1, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusDays(1));
        reg(batch, "E1", "辛");
        reg(batch, "E2", "壬");
        hold(batch, "E1");
        join(batch, "E2", null);
        release(batch, "E1");
        com.evops.capacity.entity.CapacityOffer offer = activeOffer(batch, "E2");
        assertNotNull(offer);
        assertEquals(CapacityConstants.OFFER_OFFERING, offer.getStatus());

        // 快照时 E2 的递补正在飞（座位 RELEASED、条目 OFFERING）
        WorkspaceVersion version = workspaceVersionService.createSnapshot("capwait 快照", "回退测试");
        confirm(batch, "E2");
        assertEquals(CapacityConstants.ENTRY_SEATED,
                waitlistService.position(batch.getId(), "E2").getStatus());

        // 回退到递补在飞时刻
        workspaceVersionService.rollback(version.getVersionNo(), "回退到递补在飞时刻");
        assertEquals(CapacityConstants.OFFER_OFFERING, offerMapper.selectById(offer.getId()).getStatus());
        assertEquals(CapacityConstants.ENTRY_OFFERING,
                waitlistService.position(batch.getId(), "E2").getStatus());
        assertEquals(1, waitlistService.status(batch.getId(), false).getReleasedSeatCount());
        assertEquals(0, waitlistService.status(batch.getId(), false).getWaitlistSeatedCount());

        // 回退后业务可续跑：仍可在时限内确认并锁座
        confirm(batch, "E2");
        assertEquals(CapacityConstants.ENTRY_SEATED,
                waitlistService.position(batch.getId(), "E2").getStatus());
        assertEquals(1, waitlistService.status(batch.getId(), false).getWaitlistSeatedCount());
    }
}
