package com.like.utils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.like.entity.LikeBehavior;
import com.like.mapper.LikeBehaviorMapper;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;

import static com.like.utils.RedisConstants.LIKE_BEHAVIOR_BLOOM_FILTER;

@Service
public class BloomFilterService {

    @Resource
    private RedissonClient client;
    @Resource
    private LikeBehaviorMapper likeBehaviorMapper;

    //每次查询的数据量
    private static final int PAGE_SIZE = 1000;

    /**
     * 初始化布隆过滤器 一页一页将数据放入布隆过滤器
     */
    public void initBloomFilter(){
        RBloomFilter<Object> bloomFilter = client.getBloomFilter(LIKE_BEHAVIOR_BLOOM_FILTER);
        // 初始化布隆过滤器，设计预计元素数量为100万，误差率为1%
        bloomFilter.tryInit(1000000L,0.01);
        //page从1开始 进行存入数据
        int pageNum = 1;
        List<String> keys;
        do {
            //从数据库中查询数据,分页查询
            keys = getKeysFromDatabase(pageNum, PAGE_SIZE);
            for (String key : keys) {
                //将数据放入布隆过滤器
                bloomFilter.add(key);
            }
            pageNum += 1;
            //如果没有数据了，跳出循环
        }while (!keys.isEmpty());
    }

    private List<String> getKeysFromDatabase(int pageNum, int pageSize) {

        List<String> keys = new ArrayList<>();
        // 创建分页对象
        Page<LikeBehavior> pageInfo = new Page<>(pageNum, pageSize);
        // 调用 selectPage() 方法进行分页查询
        Page<LikeBehavior> page = likeBehaviorMapper.selectPage(pageInfo, new QueryWrapper<LikeBehavior>()
                .eq("type", 1));// 根据实际需求构建查询条件
        // 获取查询到的用户列表
        List<LikeBehavior> records = page.getRecords();
        // 返回分页后的结果
        for (LikeBehavior record : records) {
            //将数据放入List中
            //key组合成 文章id+用户id
            keys.add(record.getArticleId().toString() + record.getUserId().toString());
        }
        return keys;
    }

    /**
     * 判断布隆过滤器中是否存在某个数据
     * @param name
     * @param args
     * @return
     */
    public boolean isExist(String name, String ... args) {
        //获取布隆过滤器
        RBloomFilter<Object> bloomFilter = client.getBloomFilter(name);
        StringBuffer key = new StringBuffer();
        for (String arg : args) {
            key.append(arg);
        }
        //判断是否存在
        return bloomFilter.contains(key.toString());
    }

    /**
     * 将数据添加到布隆过滤器
     * @param name
     * @param args
     * @return
     */
    public boolean addKeyToBloomFilter(String name, String ... args){
        //获取布隆过滤器
        RBloomFilter<Object> bloomFilter = client.getBloomFilter(name);
        //将数据放入布隆过滤器
        StringBuffer key = new StringBuffer();
        for (String arg : args) {
            key.append(arg);
        }
        return bloomFilter.add(key.toString());
    }

    /**
     * 更新布隆过滤器
     */
    public void updateBloomFilter() {
        // 1. 获取布隆过滤器
        RBloomFilter<Object> bloomFilter = client.getBloomFilter(LIKE_BEHAVIOR_BLOOM_FILTER);
        // 2. 清空布隆过滤器
        bloomFilter.delete();
        // 3. 根据需要更新的数据，重新添加到布隆过滤器
        //我们有一个SQL表：latest_likes_log，记录了最新的点赞行为
        // TODO
    }
}
