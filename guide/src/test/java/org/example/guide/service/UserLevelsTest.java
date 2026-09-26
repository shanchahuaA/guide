package org.example.guide.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 等级名映射与升级落点的回归。不加载 Spring、不连库 —— 纯函数。
 */
class UserLevelsTest {

    @Test
    void levelNameFollowsTheThreeStepDictionary() {
        assertEquals("菜鸟", UserLevels.nameOf(0));
        assertEquals("入门", UserLevels.nameOf(1));
        assertEquals("高手", UserLevels.nameOf(2));
    }

    @Test
    void nullFallsBackToNovice() {
        assertEquals("菜鸟", UserLevels.nameOf(null));
    }

    @Test
    void outOfRangeFallsBackToNoviceInsteadOfThrowing() {
        // 库里理论上不会有越界值，这里只钉住"不抛、不返回 null"
        assertEquals("菜鸟", UserLevels.nameOf(-1));
        assertEquals("菜鸟", UserLevels.nameOf(99));
    }

    @Test
    void nextLevelCapsAtExpert() {
        assertEquals(1, UserLevels.nextLevel(0));
        assertEquals(2, UserLevels.nextLevel(1));
        // 已是高手则原地不动 —— 跳级后门封顶在这里
        assertEquals(2, UserLevels.nextLevel(2));
    }
}
