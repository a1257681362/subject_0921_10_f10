package com.evops.capacity.common;

/**
 * 容量控制（旺季满员候补与递补）业务常量。
 */
public final class CapacityConstants {

    private CapacityConstants() {
    }

    /** 规则版本状态：草稿。 */
    public static final String VERSION_DRAFT = "DRAFT";
    /** 规则版本状态：生效（同规则同时刻仅一版）。 */
    public static final String VERSION_ACTIVE = "ACTIVE";
    /** 规则版本状态：停用。 */
    public static final String VERSION_DISABLED = "DISABLED";

    /** 批次容量控制状态：启用中（窗口内收候补/发起递补）。 */
    public static final String BATCH_ENABLED = "ENABLED";
    /** 批次容量控制状态：候补窗口已关闭。 */
    public static final String BATCH_CLOSED = "CLOSED";

    /** 座位状态：锁定中（直接占座或递补锁座）。 */
    public static final String SEAT_HELD = "HELD";
    /** 座位状态：已退位，等待递补。 */
    public static final String SEAT_RELEASED = "RELEASED";
    /** 座位来源：直接报名占座。 */
    public static final String SEAT_SOURCE_DIRECT = "DIRECT";
    /** 座位来源：候补递补锁座。 */
    public static final String SEAT_SOURCE_WAITLIST = "WAITLIST";

    /** 候补条目：排队中。 */
    public static final String ENTRY_WAITING = "WAITING";
    /** 候补条目：已发起递补通知待确认。 */
    public static final String ENTRY_OFFERING = "OFFERING";
    /** 候补条目：递补成功已锁座。 */
    public static final String ENTRY_SEATED = "SEATED";
    /** 候补条目：超时未确认出局。 */
    public static final String ENTRY_EXPIRED = "EXPIRED";
    /** 候补条目：主动退出。 */
    public static final String ENTRY_CANCELLED = "CANCELLED";
    /** 候补条目：窗口关闭仍未递补上。 */
    public static final String ENTRY_CLOSED = "CLOSED";

    /** 递补单：待确认。 */
    public static final String OFFER_OFFERING = "OFFERING";
    /** 递补单：已确认锁座。 */
    public static final String OFFER_SEATED = "SEATED";
    /** 递补单：超时未确认。 */
    public static final String OFFER_EXPIRED = "EXPIRED";

    /** 通知：已入队。 */
    public static final String NOTIFY_JOINED = "JOINED";
    /** 通知：递补通知。 */
    public static final String NOTIFY_OFFER = "OFFER";
    /** 通知：超时出局。 */
    public static final String NOTIFY_EXPIRED = "EXPIRED";
    /** 通知：递补成功锁座。 */
    public static final String NOTIFY_SEATED = "SEATED";
    /** 通知：窗口关闭未递补上。 */
    public static final String NOTIFY_QUEUE_CLOSED = "QUEUE_CLOSED";
    public static final String NOTIFY_NEW = "NEW";
    public static final String NOTIFY_READ = "READ";

    // 事件流水类型
    public static final String EVENT_SEAT_HELD = "SEAT_HELD";
    public static final String EVENT_JOIN = "JOIN";
    public static final String EVENT_RELEASE = "RELEASE";
    public static final String EVENT_OFFER = "OFFER";
    public static final String EVENT_CONFIRM = "CONFIRM";
    public static final String EVENT_EXPIRE = "EXPIRE";
    public static final String EVENT_CANCEL = "CANCEL";
    public static final String EVENT_WINDOW_CLOSE = "WINDOW_CLOSE";

    /** 默认递补确认时限（分钟）。 */
    public static final int DEFAULT_OFFER_TIMEOUT_MINUTES = 30;
    /** 时限允许范围（分钟）。 */
    public static final int MIN_OFFER_TIMEOUT_MINUTES = 1;
    public static final int MAX_OFFER_TIMEOUT_MINUTES = 24 * 60;
    /** 默认候补窗口距开考提前关闭小时数（0=开考时刻关；NULL=不按开考自动关）。 */
    public static final int DEFAULT_WAITLIST_END_HOURS_BEFORE_EXAM = 24;

    public static final String CLOSE_REASON_MANUAL = "MANUAL";
    public static final String CLOSE_REASON_WINDOW_END = "WINDOW_END";
}
