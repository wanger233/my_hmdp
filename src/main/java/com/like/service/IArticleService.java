package com.like.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.like.entity.Article;

import java.util.List;

public interface IArticleService extends IService<Article> {
    List<Long> queryHotArticle();


    Result queryArticlesByAuthorId(Long authorId);

    Result queryArticleById(Long articleId);
}
