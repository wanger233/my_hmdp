package com.hmdp.utils;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

@Component
public class SeckillTaskScheduler {
    private final TaskScheduler taskScheduler;

    private final StringRedisTemplate  stringRedisTemplate;

    private final ISeckillVoucherService seckillVoucherService;

    public SeckillTaskScheduler(ThreadPoolTaskScheduler taskScheduler, StringRedisTemplate stringRedisTemplate, ISeckillVoucherService seckillVoucherService) {
        this.taskScheduler = taskScheduler;
        this.stringRedisTemplate = stringRedisTemplate;
        this.seckillVoucherService = seckillVoucherService;
    }

    // 任务存储（方便取消）
    private ScheduledFuture<?> startTask;
    private ScheduledFuture<?> endTask;

    /**
     * 注册秒杀定时任务
     * @param voucherId 优惠券ID
     * @param startTime 秒杀开始时间（时间戳）
     * @param endTime 秒杀结束时间（时间戳）
     */
    public void scheduleSeckillTask(Long voucherId, long startTime, long endTime) {
        long now = System.currentTimeMillis();

        // 计算任务执行时间（开始前 30 分钟 & 结束后 30 分钟）
        long preloadTime = startTime - 30 * 60 * 1000;
        long cleanupTime = endTime + 30 * 60 * 1000;

        if (preloadTime > now) {
            startTask = taskScheduler.schedule(() -> preloadSeckillInfo(voucherId), Instant.ofEpochMilli(preloadTime));
        }
        if (cleanupTime > now) {
            endTask = taskScheduler.schedule(() -> cleanupSeckillInfo(voucherId), Instant.ofEpochMilli(cleanupTime));
        }
    }

    /**
     * 取消定时任务
     */
    public void cancelSeckillTask() {
        if (startTask != null) startTask.cancel(false);
        if (endTask != null) endTask.cancel(false);
    }

    /**
     * 在秒杀开始前30分钟预加载数据到 Redis
     */
    private void preloadSeckillInfo(Long voucherId) {
        System.out.println("【秒杀数据预加载】优惠券ID：" + voucherId);
        // TODO: 从数据库查询秒杀信息，并写入 Redis
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher != null) {
            stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucherId, String.valueOf(voucher.getStock()));
            stringRedisTemplate.opsForValue().set(SECKILL_ORDER_KEY + voucherId, "0");
        }
    }

    /**
     * 在秒杀结束后30分钟清理 Redis
     */
    private void cleanupSeckillInfo(Long voucherId) {
        System.out.println("【清理秒杀缓存】优惠券ID：" + voucherId);
        // TODO: 从 Redis 中删除库存、订单信息等
        stringRedisTemplate.delete(SECKILL_STOCK_KEY + voucherId);
        stringRedisTemplate.delete(SECKILL_ORDER_KEY + voucherId);
    }
}
