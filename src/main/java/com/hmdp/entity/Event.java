package com.hmdp.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.util.HashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
public class Event {

    private String topic;  // 事件所属的 Kafka 主题
    private Long userId;   // 触发事件的用户 ID
    private Long entityId; // 事件对应的实体 ID（如订单 ID、商品 ID）
    private Map<String, Object> data = new HashMap<>(); // 额外的事件数据

}
