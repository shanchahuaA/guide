package org.example.guide.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.example.guide.mapper.UserMapper;
import org.example.guide.pojo.User;
import org.example.guide.service.IUserService;
import org.example.guide.utils.BaseResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper,User> implements IUserService {
    @Autowired
    private UserMapper userMapper;
    @Override
    public int insertUser(User user) {
        return  baseMapper.insert(user);
    }

    @Override
    public User findByOpenid(String openid) {
        return baseMapper.selectById(openid);
    }

    @Override
    public boolean updateLevel(String openid, Integer level) {
        User user = new User();
        user.setLevel(level);
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getOpenid, openid);
        return baseMapper.update(user, wrapper) > 0;
    }

    @Override
    public boolean updateApiKey(String openid, String apiKey) {
        User user = new User();
        user.setApiKey(apiKey);
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getOpenid, openid);
        return baseMapper.update(user, wrapper) > 0;
    }

}
