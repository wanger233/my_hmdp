package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.CommonVoucher;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.mapper.CommonVoucherMapper;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.service.ICommonVoucherService;
import com.hmdp.service.ISeckillVoucherService;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 秒杀优惠券表，与优惠券是一对一关系 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2022-01-04
 */
@Service
public class CommonVoucherServiceImpl extends ServiceImpl<CommonVoucherMapper, CommonVoucher> implements ICommonVoucherService {

}
