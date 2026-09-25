package org.example.guide.utils;

import lombok.Getter;

@Getter
public enum ResultCodeEnum {

    SUCCESS(true,200,"操作成功"),
    FAILURE(false,-100,"操作失败"),
    SYSTEM_ERROR(false,500,"系统错误"),
    PARAM_ERROR(false,-200,"参数错误");

    private Boolean success;
    private Integer code;
    private String message;

    ResultCodeEnum(Boolean success, Integer code, String message) {
        this.success = success;
        this.code = code;
        this.message = message;
    }

}
