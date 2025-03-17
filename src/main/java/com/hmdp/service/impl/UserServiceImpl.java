package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    StringRedisTemplate stringRedisTemplate;

    public UserServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result sendCode(String phone, HttpSession session) {

        //校验手机号格式
        if (RegexUtils.isPhoneInvalid(phone)) {
            //不符合 返回错误
            return Result.fail("手机号格式不正确，请重试！");
        }
        //符合 生成验证码
        String code = RandomUtil.randomNumbers(6);
        //保存验证码到redis
        String key = LOGIN_CODE_KEY + phone;
        stringRedisTemplate.opsForValue().set(key, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.debug("发送短信验证码成功，验证码：{},有效时间：5分钟！", code);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        //校验手机号和验证码
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        //不一致 返回错误
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不正确，请重试！");
        }

        if (RegexUtils.isCodeInvalid(code)) {
            return Result.fail("验证码格式不正确，请重试！");
        }
        String userCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (userCode == null || !userCode.equals(code)) {
            return Result.fail("验证码不正确，请重试！");
        }

        //一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();
        if (user == null){
            //如果不存在，创建新用户，并保存到数据库
            user = createUserWithPhone(phone);
        }
        //保存到redis
        //随机生成token
        String token = UUID.randomUUID().toString(true);
        //将user转换为DTO
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //将userDTO转换为Hashmap存储
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,
                new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );
        String loginKey = LOGIN_USER_KEY + token;
        //将token存入redis
        stringRedisTemplate.opsForHash().putAll(loginKey, userMap);
        stringRedisTemplate.expire(loginKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        //返回token
        return Result.ok(token);
    }

    @Override
    public Result me() {
        //获取当前登录的用户并返回
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("用户未登录");
        }
        //返回用户信息
        return Result.ok(user);
    }

    @Override
    public Result logout() {
        //前端完成的，我们只需要返回ok即可
        return Result.ok();
    }

    private User createUserWithPhone(String phone) {

        User user = new User();
        user.setNickName("用户_" + RandomUtil.randomNumbers(8));
        user.setPhone(phone);
        save(user);
        return user;
    }
}
