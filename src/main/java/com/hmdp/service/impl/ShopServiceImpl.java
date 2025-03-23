package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private StringRedisTemplate stringRedisTemplate;

    public ShopServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result queryShopById(Long id) {


        //Shop shop = queryWithPassThrough(id);
        //缓存击穿问题 (加锁)
        // Shop shop = queryWithMutex(id);
        //缓存逻辑过期问题
        Shop shop = queryWithLogicalExpire(id);
        // 返回商铺信息
        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = new ThreadPoolExecutor(
            10,
            20,
            1L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadPoolExecutor.AbortPolicy()
    );

    private Shop queryWithLogicalExpire(Long id) {
        // 1.根据id从redis查询商铺信息
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断商铺信息是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 3.如果存在，直接返回
            return null;
        }
        //判断过期时间
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 4.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.如果没有过期，直接返回商铺信息
            return shop;
        }
        // 6.如果过期，获取商铺信息
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2.判断是否获取锁成功
        if (isLock){
            //获取锁成功，再次检查缓存是否过期  double check
            if (expireTime.isAfter(LocalDateTime.now())){
                // 6.3.如果没有过期，直接返回商铺信息
                unlock(lockKey);
                return shop;
            }
            // 6.4.如果过期，开启新线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit( ()->{
                try{
                    //重建缓存
                    this.saveShop2Redis(id,20L);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    unlock(lockKey);
                }
            });
        }
        return shop;
    }

    public void saveShop2Redis(Long id, Long expireSeconds) {
        // 1.查询店铺数据
        Shop shop = getById(id);
        // 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    private Shop queryWithMutex(Long id) {
        // 1.根据id从redis查询商铺信息
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断商铺信息是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.如果存在，直接返回
            return BeanUtil.toBean(shopJson, Shop.class);
        }
        //不存在，尝试获取锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.判断是否获取锁成功
            if (!isLock) {
                // 5.如果获取锁失败，休眠一段时间后重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 6.如果获取锁成功，查询数据库
            shop = getById(id);
            // 5.判断商铺信息是否存在
            if (shop == null) {
                // 6.如果不存在，返回错误信息
                // 7.将空值写入redis，防止缓存穿透
                stringRedisTemplate.opsForValue().set(key,
                        "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 8.将商铺信息存入redis
            stringRedisTemplate.opsForValue().set(key,
                    JSONUtil.toJsonStr(shop),
                    CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 9.释放锁
            unlock(lockKey);
        }

        return shop;
    }

    private boolean tryLock(String key) {
        // 1.获取锁
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(LOCK_SHOP_KEY, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag);
    }
    private void unlock(String key) {
        // 2.释放锁
        stringRedisTemplate.delete(LOCK_SHOP_KEY);
    }

    private Shop queryWithPassThrough(Long id) {
        // 1.根据id从redis查询商铺信息
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断商铺信息是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.如果存在，直接返回
            return BeanUtil.toBean(shopJson, Shop.class);
        }
        // 4.如果不存在，判断是否存在缓存标记
        if (shopJson != null) {
            // 5.说明是缓存的空字符串，返回错误信息
            return null;
        }
        // 4.如果不存在，根据id查询数据库
        Shop shop = getById(id);
        // 5.判断商铺信息是否存在
        if (shop == null) {
            // 6.如果不存在，返回错误信息
            // 7.将空值写入redis，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key,
                    "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 8.将商铺信息存入redis
        stringRedisTemplate.opsForValue().set(key,
                JSONUtil.toJsonStr(shop),
                CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        // 1.判断商铺信息是否存在
        if (id == null) {
            // 2.如果不存在，返回错误信息
            return Result.fail("商铺不存在!");
        }
        //先更新数据库
        updateById(shop);
        //再删除缓存
        String key = CACHE_SHOP_KEY + id;
        stringRedisTemplate.delete(key);
        // 3.返回成功信息
        return Result.ok("商铺信息更新成功!");
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {

        //判断是否需要根据坐标查询
        if (x == null || y == null){
            //不需要根据坐标查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //查询redis 按照距离排序 分页 结果 ： shopId distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                                .includeDistance()
                                .limit(end)
                );
        if (results == null || results.getContent().isEmpty()) {
            //没有查询到数据
            return Result.ok(null);
        }
        //获取商铺id
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        if (content.size() < from) {
            //没有查询到数据
            return Result.ok(null);
        }
        List<Long> ids = new ArrayList<>(content.size());
        Map<String, Distance> distanceMap = new HashMap<>(content.size());
        //截取分页数据
        content.stream().skip(from).forEach(
                result -> {
                    //获取商铺id
                    String shopId = result.getContent().getName();
                    ids.add(Long.valueOf(shopId));
                    //获取距离
                    Distance distance = result.getDistance();
                    distanceMap.put(shopId, distance);
                }
        );
        //根据shopId查询商铺信息
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        //返回结果
        return Result.ok(shops);
    }
}
