package org.example.guide.pojo;

import lombok.Data;

/**
 * Peak游戏物品表 item
 */
@Data
public class Item {
    private Long id;
    private String tag;
    private Integer weight;
    private String name;
    private String effect;
    private String description;
    private String achievement;
}