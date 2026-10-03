package com.evops.capacity.schedule;

import com.evops.capacity.service.CapacityWaitlistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 候补递补兜底：每分钟按服务器时间扫描——
 * 到点未确认的递补自动出局并顺给下一位、候补窗口到点自动关闭、空窗空位补发递补。
 * 人工也可调 POST /api/capacity/scan 立即执行同一逻辑。
 */
@Component
public class CapacityOfferTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(CapacityOfferTimeoutScheduler.class);

    private final CapacityWaitlistService waitlistService;

    public CapacityOfferTimeoutScheduler(CapacityWaitlistService waitlistService) {
        this.waitlistService = waitlistService;
    }

    @Scheduled(cron = "${evops.capacity.scan-cron:0 * * * * *}")
    public void scan() {
        Map<String, Object> result = waitlistService.scanAll();
        int expired = (Integer) result.get("expiredCount");
        int offered = (Integer) result.get("offeredCount");
        int closed = (Integer) result.get("closedBatchCount");
        if (expired > 0 || offered > 0 || closed > 0) {
            log.info("容量候补兜底扫描：超时出局 {} 人，新发递补 {} 人，关闭窗口批次 {} 个 {}",
                    expired, offered, closed, result.get("batchIds"));
        }
    }
}
