package com.like.event;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Event;
import com.like.entity.LikeArticleCount;
import com.like.entity.LikeBehavior;
import com.like.service.ILikeArticleCountService;
import com.like.service.ILikeBehaviorService;
import com.like.service.ILikeUserCountService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;

import static com.like.utils.KafkaConstants.TOPIC_LIKE_BEHAVIOR;
import static com.like.utils.KafkaConstants.TOPIC_SAVE_DB_FAILED;

@Component
@Slf4j
public class KafkaLikeConsumer {

    @Resource
    private ILikeBehaviorService likeBehaviorService;
    @Resource
    private KafkaLikeProducer kafkaLikeProducer;
    @Resource
    private ILikeArticleCountService likeArticleCountService;
    @Resource
    private ILikeUserCountService likeUserCountService;
    //缓冲区
    private final Map<String, Integer> articleCountBuffer = new HashMap<>();
    private final Map<String, Integer> userCountBuffer = new HashMap<>();

    // 消费下单事件
    @KafkaListener(topics = {TOPIC_LIKE_BEHAVIOR})
    public void handleLikeBehavior(ConsumerRecord record, Acknowledgment ack) {
        if (record == null || record.value() == null) {
            log.error("消息的内容为空!");
            return;
        }
        Event event = JSONUtil.toBean(record.value().toString(), Event.class);
        if (event == null) {
            log.error("消息格式错误!");
            return;
        }
        //先提取数据
        Map<String, Object> data = event.getData();
        Long articleId = Long.valueOf(data.get("articleId").toString());
        Long behaviorId = event.getEntityId();
        Long userId = event.getUserId();
        Integer type = Integer.valueOf(data.get("type").toString());
        LocalDateTime time = LocalDateTime.now();
        //封装成LikeBehavior对象
        LikeBehavior likeBehavior = new LikeBehavior()
                .setBehaviorId(behaviorId)
                .setArticleId(articleId)
                .setType(type)
                .setUserId(userId)
                .setTime(time);
        //调用点赞模块保存数据
        //这里如果保存不成功，重复最多100次，
        int tryCount = 0;
        int maxRetries = 100;
        try {
            while (!likeBehaviorService.saveLikeLog(likeBehavior) && tryCount < maxRetries) {
                tryCount++;
                if (tryCount < maxRetries){
                    Thread.sleep(100);
                }
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            if (tryCount >= maxRetries) {
                //如果保存失败，发送到save-db-failed-topic
                HashMap<String, Object> failData = new HashMap<>();
                failData.put("articleId", articleId);
                failData.put("type", type);
                Event failEvent = new Event()
                        .setTopic(TOPIC_SAVE_DB_FAILED)
                        .setUserId(userId)
                        .setEntityId(behaviorId)
                        .setData(failData);
                kafkaLikeProducer.publishEvent(failEvent);
                log.error("保存点赞行为失败，已发送到save-db-failed-topic");
            }
        }
        //统计文章和用户获赞的总数量
        int diff = type == 1 ? 1 : -1;
        articleCountBuffer.put(articleId.toString(),
                articleCountBuffer.getOrDefault(articleId.toString(), 0) + diff);
        userCountBuffer.put(userId.toString(),
                userCountBuffer.getOrDefault(userId.toString(), 0) + diff);
        //ack 确认
        ack.acknowledge();
    }

    @KafkaListener(topics = {TOPIC_SAVE_DB_FAILED})
    public void handleSaveDBFailed(ConsumerRecord record, Acknowledgment ack) {
        if (record == null || record.value() == null) {
            log.error("消息的内容为空!");
            return;
        }
        Event event = JSONUtil.toBean(record.value().toString(), Event.class);
        if (event == null) {
            log.error("消息格式错误!");
            return;
        }
        Map<String, Object> data = event.getData();
        Long articleId = Long.valueOf(data.get("articleId").toString());
        Long userId = event.getUserId();
        Integer type = Integer.valueOf(data.get("type").toString());
        int diff = type == 1 ? -1 : 1;
        articleCountBuffer.put(articleId.toString(),
                articleCountBuffer.getOrDefault(articleId.toString(), 0) + diff);
        userCountBuffer.put(userId.toString(),
                userCountBuffer.getOrDefault(userId.toString(), 0) + diff);
        //ack 确认
        ack.acknowledge();
    }

    @Scheduled(fixedRate = 3000) // 每3秒执行一次
    private void flush() {
        updateLikeCount();
    }

    private void updateLikeCount() {
        if (!articleCountBuffer.isEmpty()) {
            List<LikeArticleCount> countList = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : articleCountBuffer.entrySet()) {
                Long articleId = Long.valueOf(entry.getKey());
                Integer count = entry.getValue();
                countList.add(new LikeArticleCount().setArticleId(articleId).setLikeCount(count));
            }
            likeArticleCountService.updateBatchCount(countList);
            likeUserCountService.updateBatchCount(countList);
            articleCountBuffer.clear();
        }
    }

}