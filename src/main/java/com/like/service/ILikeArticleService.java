package com.like.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.like.dto.LikeArticleDTO;
import com.like.dto.LikeUserDTO;
import com.like.entity.LikeBehavior;

public interface ILikeArticleService extends IService<LikeBehavior> {
    Result getArticleLikeList(LikeArticleDTO dto);
}
