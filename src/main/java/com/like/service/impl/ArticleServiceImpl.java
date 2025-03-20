package com.like.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.vo.ArticleVO;
import com.like.service.IArticleService;
import com.like.entity.Article;
import com.like.mapper.ArticleMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ArticleServiceImpl extends ServiceImpl<ArticleMapper, Article> implements IArticleService {
    @Override
    public List<Long> queryHotArticle() {
        //这里是模拟数据，实际应用中应该利用热点算法接口获取
        List<Long> hotList = new ArrayList<>();
        hotList.add(1L);
        hotList.add(2L);
        hotList.add(5L);
        return hotList;
    }

    @Override
    public Result queryArticlesByAuthorId(Long authorId) {
        List<Article> articles = baseMapper.selectList(new QueryWrapper<Article>()
                .eq("author_id", authorId)
                .eq("status", 1));// 假设 1 表示已发布的状态
        if (articles.isEmpty()) {
            return Result.fail("Article not found");
        }
        ArticleVO articleVO = BeanUtil.copyProperties(articles, ArticleVO.class);
        return Result.ok(articleVO);
    }

    @Override
    public Result queryArticleById(Long articleId) {
        List<Article> articles = baseMapper.selectList(new QueryWrapper<Article>()
                .eq("article_id", articleId)
                .eq("status", 1));// 假设 1 表示已发布的状态
        if (articles.isEmpty()) {
            return Result.fail("Article not found");
        }
        ArticleVO articleVO = BeanUtil.copyProperties(articles, ArticleVO.class);
        return Result.ok(articleVO);
    }


}
