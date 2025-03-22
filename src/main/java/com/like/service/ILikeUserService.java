package com.like.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.like.dto.LikeUserDTO;
import com.like.entity.Article;
import com.like.entity.LikeBehavior;

import java.util.List;

public interface ILikeUserService extends IService<LikeBehavior> {
    Result getUserLikeList(LikeUserDTO dto);
}
