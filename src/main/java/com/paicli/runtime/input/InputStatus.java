package com.paicli.runtime.input;

public enum InputStatus {
    /** Queue 已保管，尚未启动下一 Turn。 */
    ACCEPTED,
    PENDING_DELIVERY,
    DELIVERY_RESERVED,
    /** 无法确认请求是否送达；保留消息与请求关联，不自动重发。 */
    DELIVERY_UNCERTAIN,
    /** 仅表示调用方确认已纳入提交的请求，不表示模型执行或遵循了要求。 */
    INCLUDED_IN_REQUEST,
    INTERRUPT_REQUESTED,
    CANCELLED
}
