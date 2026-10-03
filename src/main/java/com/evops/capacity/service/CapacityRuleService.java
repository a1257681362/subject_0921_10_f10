package com.evops.capacity.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.capacity.common.CapacityConstants;
import com.evops.capacity.dto.CapacityConfig;
import com.evops.capacity.dto.CapacityRuleRequest;
import com.evops.capacity.dto.CapacityRuleVersionRequest;
import com.evops.capacity.entity.CapacityRule;
import com.evops.capacity.entity.CapacityRuleVersion;
import com.evops.capacity.mapper.CapacityRuleMapper;
import com.evops.capacity.mapper.CapacityRuleVersionMapper;
import com.evops.common.BusinessException;
import com.evops.common.OperatorHolder;
import com.evops.exam.entity.ExamSite;
import com.evops.exam.mapper.ExamSiteMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 容量控制规则模块（模块 + 版本 + 状态）：
 * - 窗口与时限按考点配，一个考点一套规则，规则下挂多个版本；
 * - 版本 DRAFT → ACTIVE → DISABLED：发布即冻结配置 JSON，自动停用同规则旧生效版；
 * - 批次启用容量控制时永久记下当时生效版本号与配置快照，规则以后调整不影响在飞候补/递补。
 */
@Service
public class CapacityRuleService {

    private final CapacityRuleMapper ruleMapper;
    private final CapacityRuleVersionMapper versionMapper;
    private final ExamSiteMapper siteMapper;
    private final ObjectMapper objectMapper;

    public CapacityRuleService(CapacityRuleMapper ruleMapper,
                               CapacityRuleVersionMapper versionMapper,
                               ExamSiteMapper siteMapper,
                               ObjectMapper objectMapper) {
        this.ruleMapper = ruleMapper;
        this.versionMapper = versionMapper;
        this.siteMapper = siteMapper;
        this.objectMapper = objectMapper;
    }

    // ---------- 规则模块 ----------

    @Transactional
    public CapacityRule createRule(CapacityRuleRequest request) {
        ExamSite site = siteMapper.selectById(request.getSiteId());
        if (site == null) {
            throw new BusinessException("SITE_NOT_FOUND", "考点不存在：id=" + request.getSiteId());
        }
        CapacityRule rule = new CapacityRule();
        rule.setCode(request.getCode().trim());
        rule.setName(request.getName().trim());
        rule.setSiteId(request.getSiteId());
        rule.setRemark(request.getRemark());
        try {
            ruleMapper.insert(rule);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("DUPLICATE_CAPACITY_RULE",
                    "规则编码已存在或该考点已配置容量规则：" + rule.getCode());
        }
        return rule;
    }

    @Transactional(readOnly = true)
    public List<CapacityRule> listRules(Long siteId) {
        QueryWrapper<CapacityRule> wrapper = new QueryWrapper<CapacityRule>()
                .orderByAsc("site_id", "code");
        if (siteId != null) {
            wrapper.eq("site_id", siteId);
        }
        return ruleMapper.selectList(wrapper);
    }

    public CapacityRule getRule(Long ruleId) {
        CapacityRule rule = ruleMapper.selectById(ruleId);
        if (rule == null) {
            throw new BusinessException("CAPACITY_RULE_NOT_FOUND", "容量规则不存在：id=" + ruleId);
        }
        return rule;
    }

    /** 按考点取规则（不存在返回 null）。 */
    public CapacityRule findRuleBySite(Long siteId) {
        return ruleMapper.selectOne(new QueryWrapper<CapacityRule>().eq("site_id", siteId));
    }

    // ---------- 规则版本 ----------

    @Transactional
    public CapacityRuleVersion createDraftVersion(Long ruleId, CapacityRuleVersionRequest request) {
        CapacityRule rule = getRule(ruleId);
        CapacityConfig normalized = validateConfig(request.getConfig());

        CapacityRuleVersion version = new CapacityRuleVersion();
        version.setRuleId(rule.getId());
        version.setVersionNo(nextVersionNo(rule.getId()));
        version.setStatus(CapacityConstants.VERSION_DRAFT);
        version.setConfig(writeConfig(normalized));
        version.setRemark(request.getRemark());
        version.setOperator(OperatorHolder.current());
        versionMapper.insert(version);
        return version;
    }

    /** 发布：DRAFT → ACTIVE；同一规则旧 ACTIVE 自动置 DISABLED。 */
    @Transactional
    public CapacityRuleVersion publishVersion(Long versionId) {
        CapacityRuleVersion version = requireVersion(versionId);
        String operator = OperatorHolder.current();
        LocalDateTime now = LocalDateTime.now();

        if (CapacityConstants.VERSION_ACTIVE.equals(version.getStatus())) {
            throw new BusinessException("VERSION_ALREADY_ACTIVE",
                    "版本已是生效状态，无需重复发布：" + version.getVersionNo());
        }
        if (CapacityConstants.VERSION_DISABLED.equals(version.getStatus())) {
            throw new BusinessException("VERSION_DISABLED",
                    "停用版本不可重新发布，请新建版本：" + version.getVersionNo());
        }

        List<CapacityRuleVersion> actives = versionMapper.selectList(new QueryWrapper<CapacityRuleVersion>()
                .eq("rule_id", version.getRuleId())
                .eq("status", CapacityConstants.VERSION_ACTIVE));
        for (CapacityRuleVersion active : actives) {
            active.setStatus(CapacityConstants.VERSION_DISABLED);
            active.setDisabledBy(operator);
            active.setDisabledAt(now);
            versionMapper.updateById(active);
        }

        version.setStatus(CapacityConstants.VERSION_ACTIVE);
        version.setPublishedBy(operator);
        version.setPublishedAt(now);
        versionMapper.updateById(version);
        return version;
    }

    @Transactional
    public CapacityRuleVersion disableVersion(Long versionId) {
        CapacityRuleVersion version = requireVersion(versionId);
        if (!CapacityConstants.VERSION_ACTIVE.equals(version.getStatus())) {
            throw new BusinessException("VERSION_NOT_ACTIVE", "仅生效版本可停用：" + version.getVersionNo());
        }
        version.setStatus(CapacityConstants.VERSION_DISABLED);
        version.setDisabledBy(OperatorHolder.current());
        version.setDisabledAt(LocalDateTime.now());
        versionMapper.updateById(version);
        return version;
    }

    @Transactional(readOnly = true)
    public List<CapacityRuleVersion> listVersions(Long ruleId) {
        getRule(ruleId);
        return versionMapper.selectList(new QueryWrapper<CapacityRuleVersion>()
                .eq("rule_id", ruleId)
                .orderByDesc("id"));
    }

    public CapacityRuleVersion getVersion(Long versionId) {
        return requireVersion(versionId);
    }

    /** 取规则当前生效版本；无生效版返回 null（启用批次时据此报可读错误）。 */
    @Transactional(readOnly = true)
    public CapacityRuleVersion findActiveVersion(Long ruleId) {
        List<CapacityRuleVersion> actives = versionMapper.selectList(new QueryWrapper<CapacityRuleVersion>()
                .eq("rule_id", ruleId)
                .eq("status", CapacityConstants.VERSION_ACTIVE)
                .orderByDesc("id"));
        if (actives.isEmpty()) {
            return null;
        }
        if (actives.size() > 1) {
            throw new BusinessException("MULTIPLE_ACTIVE_CAPACITY_VERSION",
                    "容量规则 id=" + ruleId + " 存在多个生效版本，请先停用多余版本");
        }
        return actives.get(0);
    }

    /** 按考点取生效规则版本；规则或生效版缺失均返回 null（调用方给可读原因）。 */
    @Transactional(readOnly = true)
    public CapacityRuleVersion findActiveVersionBySite(Long siteId) {
        CapacityRule rule = findRuleBySite(siteId);
        if (rule == null) {
            return null;
        }
        return findActiveVersion(rule.getId());
    }

    // ---------- 配置校验 / 序列化 ----------

    /** 校验并补全默认值，返回用于冻结的规范化配置（草稿也不允许半截非法配置）。 */
    public CapacityConfig validateConfig(CapacityConfig raw) {
        CapacityConfig c = raw == null ? new CapacityConfig() : raw;
        CapacityConfig norm = new CapacityConfig();
        norm.setWaitlistEnabled(c.getWaitlistEnabled() == null ? Boolean.TRUE : c.getWaitlistEnabled());

        int timeout = c.getOfferTimeoutMinutes() == null
                ? CapacityConstants.DEFAULT_OFFER_TIMEOUT_MINUTES : c.getOfferTimeoutMinutes();
        if (timeout < CapacityConstants.MIN_OFFER_TIMEOUT_MINUTES
                || timeout > CapacityConstants.MAX_OFFER_TIMEOUT_MINUTES) {
            throw new BusinessException("PARAM_OFFER_TIMEOUT_INVALID",
                    "递补确认时限须在 [" + CapacityConstants.MIN_OFFER_TIMEOUT_MINUTES + ", "
                            + CapacityConstants.MAX_OFFER_TIMEOUT_MINUTES + "] 分钟之间");
        }
        norm.setOfferTimeoutMinutes(timeout);

        if (c.getWaitlistEndHoursBeforeExam() != null && c.getWaitlistEndHoursBeforeExam() < 0) {
            throw new BusinessException("PARAM_WAITLIST_END_INVALID",
                    "候补窗口距开考提前关闭小时数不能为负");
        }
        norm.setWaitlistEndHoursBeforeExam(c.getWaitlistEndHoursBeforeExam() == null
                ? CapacityConstants.DEFAULT_WAITLIST_END_HOURS_BEFORE_EXAM
                : c.getWaitlistEndHoursBeforeExam());
        return norm;
    }

    public String writeConfig(CapacityConfig config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new BusinessException("CAPACITY_CONFIG_SERIALIZE_FAILED",
                    "容量配置序列化失败：" + e.getMessage());
        }
    }

    public CapacityConfig readConfig(String json) {
        try {
            CapacityConfig config = objectMapper.readValue(json, new TypeReference<CapacityConfig>() {
            });
            if (config == null) {
                throw new BusinessException("CAPACITY_CONFIG_BROKEN", "容量配置为空或已损坏");
            }
            return config;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("CAPACITY_CONFIG_BROKEN", "容量配置无法解析：" + e.getMessage());
        }
    }

    // ---------- 内部 ----------

    private CapacityRuleVersion requireVersion(Long versionId) {
        if (versionId == null) {
            throw new BusinessException("VERSION_ID_REQUIRED", "规则版本 id 不能为空");
        }
        CapacityRuleVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException("CAPACITY_VERSION_NOT_FOUND", "容量规则版本不存在：id=" + versionId);
        }
        return version;
    }

    /** 版本号：规则 code + -C + 递增序号（如 CAP01-C1）。 */
    private String nextVersionNo(Long ruleId) {
        CapacityRule rule = getRule(ruleId);
        int max = 0;
        String prefix = rule.getCode() + "-C";
        for (CapacityRuleVersion v : versionMapper.selectList(
                new QueryWrapper<CapacityRuleVersion>().eq("rule_id", ruleId))) {
            String no = v.getVersionNo();
            if (no != null && no.startsWith(prefix)) {
                try {
                    max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                    // 非本规则序号忽略
                }
            }
        }
        return prefix + (max + 1);
    }
}
