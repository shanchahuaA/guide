package org.example.guide.utils;

import lombok.Data;

import java.util.Map;

@Data
public class BaseResult {

    private Boolean success;
    private Integer code;
    private String message;
    Map<String, Object> data;

    public static BaseResult successResult() {
        BaseResult baseResult = new BaseResult();
        baseResult.setSuccess(ResultCodeEnum.SUCCESS.getSuccess());
        baseResult.setCode(ResultCodeEnum.SUCCESS.getCode());
        baseResult.setMessage(ResultCodeEnum.SUCCESS.getMessage());
        return baseResult;
    }

    public static BaseResult failureResult() {
        BaseResult baseResult = new BaseResult();
        baseResult.setSuccess(ResultCodeEnum.FAILURE.getSuccess());
        baseResult.setCode(ResultCodeEnum.FAILURE.getCode());
        baseResult.setMessage(ResultCodeEnum.FAILURE.getMessage());
        return baseResult;
    }

    //通用方法
    public static BaseResult setResult(ResultCodeEnum result, Map<String, Object> data) {
        BaseResult baseResult = new BaseResult();
        baseResult.setSuccess(result.getSuccess());
        baseResult.setCode(result.getCode());
        baseResult.setMessage(result.getMessage());
        baseResult.setData(data);
        return baseResult;
    }
}
