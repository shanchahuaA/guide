package org.example.guide.controller;

import org.example.guide.pojo.Item;
import org.example.guide.pojo.User;
import org.example.guide.service.IUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
public class UserController {
    @Autowired
    private IUserService iUserService;
    @RequestMapping("testInsertUser")
    public String testInsertUser () {
        User user = new User ( "10086" , 1 , "ABCD" , LocalDateTime.now(), "测试用户" , null );
        int result = iUserService.insertUser(user);
        return result > 0 ? "插入成功！" : "插入失败！" ;
    }


}
