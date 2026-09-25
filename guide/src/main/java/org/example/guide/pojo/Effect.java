package org.example.guide.pojo;

import lombok.Data;

/**
 * 状态效果,对应 item.effect 这个 JSON 列里的一个元素
 */
@Data
public class Effect {
    /** 效果代码,如 HUNGER / BONUS / POISON */
    private String code;

    /** 数值,可为负,负值表示消除该状态 */
    private Float value;

    /** 持续秒数,可空 */
    private Float duration;

    /** 延迟生效秒数,可空 */
    private Float startDelay;
}
