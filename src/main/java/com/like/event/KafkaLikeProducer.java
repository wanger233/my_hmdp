package com.like.event;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Event;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
public class KafkaLikeProducer {
    @Resource
    private KafkaTemplate kafkaTemplate;

    public void publishEvent(Event event) {
        // 将事件发布到指定的主题
        kafkaTemplate.send(event.getTopic(), JSONUtil.toJsonStr(event));
    }
    public void publishLikeEvent(Event event) {
        Long userId = event.getUserId();
        if (userId !=null) {
            kafkaTemplate.send(event.getTopic(), userId, JSONUtil.toJsonStr(event));
        }
    }

}