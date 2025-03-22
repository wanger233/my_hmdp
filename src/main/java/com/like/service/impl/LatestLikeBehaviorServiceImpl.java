package com.like.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.like.entity.LatestLikeBehavior;
import com.like.mapper.LatestLikeBehaviorMapper;
import com.like.service.ILatestLikeBehaviorService;
import org.springframework.stereotype.Service;

@Service
public class LatestLikeBehaviorServiceImpl extends ServiceImpl<LatestLikeBehaviorMapper, LatestLikeBehavior> implements ILatestLikeBehaviorService {
}
