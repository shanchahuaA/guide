package org.example.guide.utils;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ExceptionAutoUtil {

    @ExceptionHandler(RuntimeException.class)//要捕获的异常类型
    public BaseResult error(RuntimeException re) {
        //日志记录
        re.printStackTrace();
        //返回Json格式的报错信息
        BaseResult baseResult = BaseResult.setResult(ResultCodeEnum.SYSTEM_ERROR, null);
        baseResult.setMessage(re.getMessage());
        return baseResult;
    }

    @ExceptionHandler(MyException.class)
    public BaseResult error(MyException me){
        //日志记录
        me.printStackTrace();
        //返回Json格式的报错信息
        BaseResult baseResult = BaseResult.setResult(ResultCodeEnum.PARAM_ERROR, null);
        baseResult.setMessage(me.getMessage());
        return baseResult;
    }


}
