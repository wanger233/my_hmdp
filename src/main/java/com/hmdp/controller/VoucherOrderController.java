package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    private IVoucherOrderService voucherOrderService;

    public VoucherOrderController(IVoucherOrderService voucherOrderService) {
        this.voucherOrderService = voucherOrderService;
    }

    @PostMapping("/common/{id}")
    public Result commonlVoucher(@PathVariable("id") Long voucherId) {
        int buyNumber = 1;
        return voucherOrderService.commonVoucher(voucherId, buyNumber);
    }

    @PostMapping("/limit/{id}")
    public Result limitlVoucher(@PathVariable("id") Long voucherId) {
        int buyNumber = 1;
        return voucherOrderService.limitVoucher1(voucherId, buyNumber);
    }

    @PostMapping("/seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        int buyNumber = 1;
        return voucherOrderService.seckillVoucher(voucherId, buyNumber);
    }
}
