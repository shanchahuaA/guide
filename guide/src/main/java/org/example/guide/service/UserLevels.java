package org.example.guide.service;

import java.util.List;

/**
 * 用户等级的取值与中文名映射。等级字典只有这一处，别在别处再写一遍。
 *
 * <p>口径见 CONTEXT.md「用户等级」：0/1/2 = 菜鸟/入门/高手，新用户默认 0。
 * 纯函数，无 Spring、无库 —— 单测直接跑。
 */
public final class UserLevels {

    public static final int NOVICE = 0;
    public static final int BEGINNER = 1;
    public static final int EXPERT = 2;
    /** 等级封顶在高手 */
    public static final int MAX = EXPERT;

    private static final List<String> NAMES = List.of("菜鸟", "入门", "高手");

    private UserLevels() {
    }

    /** 等级中文名；越界一律归到菜鸟（库里理论上不会有越界值，这里只是兜底不抛） */
    public static String nameOf(Integer level) {
        int i = level == null ? NOVICE : level;
        if (i < 0 || i >= NAMES.size()) {
            return NAMES.get(NOVICE);
        }
        return NAMES.get(i);
    }

    /** 升一级的落点：封顶在高手 */
    public static int nextLevel(int level) {
        return Math.min(level + 1, MAX);
    }
}
