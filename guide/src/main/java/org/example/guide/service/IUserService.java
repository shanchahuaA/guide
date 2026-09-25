package org.example.guide.service;

import com.baomidou.mybatisplus.spring.service.IService;
import org.example.guide.pojo.User;
import org.example.guide.utils.BaseResult;

public interface IUserService  extends IService<User> {
    int insertUser(User user);

    /** 按 openid 查用户（openid 即主键），无则返回 null */
    User findByOpenid(String openid);

    /** 答题通过后升级：直接改 level 字段 */
    boolean updateLevel(String openid, Integer level);

    /** 用户提交/更新自己的 AI key */
    boolean updateApiKey(String openid, String apiKey);
}
