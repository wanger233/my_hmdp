package com.like.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class LikeUserDTO {
    /**
     * 用户ID
     */
    @NotBlank(message = "用户ID不能为空")
    private Long userId;
}
