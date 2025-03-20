package com.like.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.like.dto.LikeBehaviorDTO;
import com.like.entity.LikeBehavior;

public interface ILikeBehaviorService extends IService<LikeBehavior> {

    Result like(LikeBehaviorDTO likeBehaviorDTO);

    boolean saveLikeLog(LikeBehavior likeBehavior);

    String getCache(String key);
}
