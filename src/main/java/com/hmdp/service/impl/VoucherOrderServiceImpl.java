package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.CommonVoucher;
import com.hmdp.entity.Event;
import com.hmdp.entity.LimitVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.event.KafkaOrderProducer;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ICommonVoucherService;
import com.hmdp.service.ILimitVoucherService;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.KafkaConstants.TOPIC_CREATE_ORDER;
import static com.hmdp.utils.KafkaConstants.TOPIC_SAVE_ORDER_FAILED;
import static com.hmdp.utils.SystemConstants.MAX_BUY_LIMIT;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ICommonVoucherService commonVoucherService;
    @Resource
    private ILimitVoucherService limitVoucherService;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private KafkaOrderProducer kafkaOrderProducer;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result commonVoucher(Long voucherId, int buyNumber) {

        // 1.查询优惠券
        CommonVoucher commonVoucher = commonVoucherService.getById(voucherId);
        if (commonVoucher == null) {
            return Result.fail("优惠券不存在");
        }
        // 2.判断库存是否充足
        if (commonVoucher.getStock() < buyNumber) {
            return Result.fail("库存不足");
        }
        //3. 乐观锁扣减库存
        commonVoucherService.update()
                .setSql("stock = stock - " + buyNumber) // set stock = stock - buyNumber
                .eq("voucher_id", voucherId) // where id = ?
                .ge("stock", buyNumber) // and stock >= buyNumber
                .update();
        //4.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 4.1.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 4.2.用户id
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        // 4.3.代金券id
        voucherOrder.setVoucherId(voucherId);
        // 保存订单
        save(voucherOrder);
        // 5.返回订单id
        return Result.ok(orderId);
    }

    @Override
    public Result limitVoucher(Long voucherId, int buyNumber) {
        return null;
    }
    /**
     * 先获得分布式锁   锁力度更大  -> 减少对数据库的压力
     * 无论多少用户同时请求购买同一商品，同一时间只有一个请求可以进行。
     * 这大大减少了对数据库的并发访问，
     * 因为同一时间只有一个线程可以访问数据库来处理特定商品的订单
     * @param voucherId
     * @param buyNumber
     * @return
     */
    @Override
    @Transactional
    public Result limitVoucher2(Long voucherId, int buyNumber) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //查询优惠券
        LimitVoucher limitVoucher = limitVoucherService.getById(voucherId);
        if (limitVoucher == null) {
            return Result.fail("优惠券不存在");
        }
        Integer stock = limitVoucher.getStock();
        Integer limitCount = limitVoucher.getLimitCount();
        //获取分布式锁 基于商品id
        RLock lock = redissonClient.getLock("lock:voucher:" + voucherId);
        try{

            //判断库存是否充足
            if (stock < buyNumber) {
                return Result.fail("库存不足");
            }
            //是否超过限制购买数量
            List<VoucherOrder> list = list(new LambdaQueryWrapper<VoucherOrder>()
                    .eq(VoucherOrder::getUserId, userId)
                    .eq(VoucherOrder::getVoucherId, voucherId));
            int totalNum = list.stream().mapToInt(VoucherOrder::getBuyNumber).sum();
            if (totalNum + buyNumber > limitCount) {
                return Result.fail("超过限制购买数量");
            }
            //尝试获取锁
            boolean isLock = lock.tryLock(10, TimeUnit.SECONDS);
            if (!isLock) {
                log.debug("获取锁失败");
                return Result.fail("当前人数过多，请稍后再试");
            }

            //乐观锁扣减库存
            boolean success = limitVoucherService.update()
                    .setSql("stock = stock - " + buyNumber)// set stock = stock - buyNumber
                    .eq("voucher_id", voucherId)// where id = ?
                    .ge("stock", buyNumber)// and stock >= buyNumber
                    .update();
            if (!success) {
                //扣减失败
                log.error("库存不足");
                return Result.fail("库存不足");
            }
            //创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            //用户id
            voucherOrder.setUserId(userId);
            //代金券id
            voucherOrder.setVoucherId(voucherId);
            //购买数量
            voucherOrder.setBuyNumber(buyNumber);
            //保存订单
            save(voucherOrder);
            //返回订单id
            return Result.ok(orderId);

        }catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            lock.unlock();
        }

    }

    /**
     * 锁力度小  -> 对数据库的压力大
     * 这意味着，如果不同用户同时请求下单，他们各自的请求可以同时进行，
     * 因为它们使用不同的锁。
     * 这可能导致多个用户同时访问数据库，
     * 增加了数据库的负载。
     * @param voucherId
     * @param buyNumber
     * @return
     */
    @Override
    @Transactional
    public Result limitVoucher1(Long voucherId, int buyNumber) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //获取分布式锁 基于用户id的锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.debug("获取锁失败");
            return Result.fail("当前人数过多，请稍后再试");
        }
        try{
            //查询优惠券
            LimitVoucher limitVoucher = limitVoucherService.getById(voucherId);
            if (limitVoucher == null) {
                return Result.fail("优惠券不存在");
            }
            Integer stock = limitVoucher.getStock();
            Integer limitCount = limitVoucher.getLimitCount();
            //判断库存是否充足
            if (stock < buyNumber) {
                return Result.fail("库存不足");
            }
            //是否超过限制购买数量
            List<VoucherOrder> list = list(new LambdaQueryWrapper<VoucherOrder>()
                    .eq(VoucherOrder::getUserId, userId)
                    .eq(VoucherOrder::getVoucherId, voucherId));
            int totalNum = list.stream().mapToInt(VoucherOrder::getBuyNumber).sum();
            if (totalNum + buyNumber > limitCount) {
                return Result.fail("超过限制购买数量");
            }


            //乐观锁扣减库存
            boolean success = limitVoucherService.update()
                    .setSql("stock = stock - " + buyNumber)// set stock = stock - buyNumber
                    .eq("voucher_id", voucherId)// where id = ?
                    .ge("stock", buyNumber)// and stock >= buyNumber
                    .update();
            if (!success) {
                //扣减失败
                log.error("库存不足");
                return Result.fail("库存不足");
            }
            //创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            //用户id
            voucherOrder.setUserId(userId);
            //代金券id
            voucherOrder.setVoucherId(voucherId);
            //购买数量
            voucherOrder.setBuyNumber(buyNumber);
            //保存订单
            save(voucherOrder);
            //返回订单id
            return Result.ok(orderId);

        } finally {
            //释放锁
            lock.unlock();
        }

    }

    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 获取锁
        RLock rLock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = rLock.tryLock();
        if (!isLock) {
            log.debug("获取用户ID锁失败！用户正在下单...");
            return;
        }
        try {
            //检查是否超过最大购买限制
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (count > MAX_BUY_LIMIT) {
                log.error("超过最大购买限制");
                return;
            }
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - " + voucherOrder.getBuyNumber()) // set stock = stock - buynumber
                    .eq("voucher_id", voucherId)
                    .gt("stock", voucherOrder.getBuyNumber()) // where id = ? and stock > buynumber
                    .update();
            if (!success) {
                // 扣减失败
                log.error("库存不足！");
                return;
            }
            // 7.保存订单
            if (!save(voucherOrder)) {
                log.debug("保存订单失败");
                throw new Exception("保存订单失败");
            }
        }catch (Exception e){
            Event event = new Event()
                    .setTopic(TOPIC_SAVE_ORDER_FAILED)
                    .setEntityId(voucherOrder.getId())
                    .setUserId(userId)
                    .setData(new HashMap<String, Object>() {{
                        put("voucherId", voucherId);
                        put("buyNumber", voucherOrder.getBuyNumber());
                    }});
            kafkaOrderProducer.publishEvent(event);
        }finally {
            // 释放锁
            rLock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId, int buyNumber) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        long currentTime = LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long orderId = redisIdWorker.nextId("order");
        //执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId),
                String.valueOf(currentTime),
                String.valueOf(buyNumber),
                String.valueOf(MAX_BUY_LIMIT)
        );
        //判断结果
        switch (result.intValue()) {
            case 0:
                // 2.秒杀成功，发送消息到kafka 这个消息是用来创建订单的
                sendOrderMsgToKafka(orderId, voucherId, userId, buyNumber);
                // 返回订单id
                return Result.ok(orderId);
            case 1:
                // TODO 获取锁，读取 mysql 数据存放到 Redis 中，然后递归调用本函数
                return Result.fail("redis缺少数据");
            case 2:
                return Result.fail("秒杀尚未开始");
            case 3:
                return Result.fail("秒杀已经结束");
            case 4:
                return Result.fail("库存不足");
            case 5:
                return Result.fail("超过最大购买限制");
            default:
                return Result.fail("未知错误");
        }
    }



    private void sendOrderMsgToKafka(long orderId, Long voucherId, Long userId, int buyNumber) {
        //发送消息到kafka
        //构建消息体
        HashMap<String, Object> data = new HashMap<>();
        data.put("voucherId", voucherId);
        data.put("buyNumber", buyNumber);
        Event event = new Event()
                .setTopic(TOPIC_CREATE_ORDER)
                .setUserId(userId)
                .setEntityId(orderId)
                .setData(data);
        kafkaOrderProducer.publishEvent(event);
    }

}
