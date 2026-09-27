package org.example.guide.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 越级门禁（#43）：矩阵逐格（3 等级 × 2 类别）+ 文案选取 + 提示词边界。
 * **纯函数，不加载 Spring、不连 Redis / MySQL、不调大模型。**
 */
class TeachGateTest {

    // ── 归类 ────────────────────────────────────────────────────────────────

    @Test
    void 普通图鉴问题不归类空问题也不归类() {
        assertThat(TeachGate.classify("蘑菇有什么用")).isEqualTo(TeachGate.Category.NONE);
        assertThat(TeachGate.classify(null)).isEqualTo(TeachGate.Category.NONE);
        assertThat(TeachGate.classify("  ")).isEqualTo(TeachGate.Category.NONE);
    }

    @Test
    void 路线与速通类能识别且支持英文词() {
        assertThat(TeachGate.classify("今天的最佳路线")).isEqualTo(TeachGate.Category.ROUTE);
        assertThat(TeachGate.classify("what route should I take")).isEqualTo(TeachGate.Category.ROUTE);
        assertThat(TeachGate.classify("怎么速通")).isEqualTo(TeachGate.Category.SPEEDRUN);
        assertThat(TeachGate.classify("any speedrun tip")).isEqualTo(TeachGate.Category.SPEEDRUN);
    }

    @Test
    void 两类词同时出现按更严的速通算() {
        // 「速通的最佳路线」若先判路线会被放行 —— 那正好漏掉入门等级唯一的限制
        assertThat(TeachGate.classify("速通的最佳路线")).isEqualTo(TeachGate.Category.SPEEDRUN);
    }

    @Test
    void 带走的图鉴内问法不被误判为路线类() {
        // 「这蘑菇走哪刷」问的是生态（在哪捡），不是路线 —— 把"走哪"算作路线类会拦掉菜鸟的正常问题
        assertThat(TeachGate.classify("这蘑菇走哪刷")).isEqualTo(TeachGate.Category.NONE);
    }

    // ── 矩阵逐格（3 等级 × 2 类别）────────────────────────────────────────────

    @Test
    void 菜鸟路线与速通都拦() {
        assertThat(TeachGate.blocked(UserLevels.NOVICE, TeachGate.Category.ROUTE)).isTrue();
        assertThat(TeachGate.blocked(UserLevels.NOVICE, TeachGate.Category.SPEEDRUN)).isTrue();
    }

    @Test
    void 入门只拦速通() {
        assertThat(TeachGate.blocked(UserLevels.BEGINNER, TeachGate.Category.ROUTE)).isFalse();
        assertThat(TeachGate.blocked(UserLevels.BEGINNER, TeachGate.Category.SPEEDRUN)).isTrue();
    }

    @Test
    void 高手两类都不拦() {
        assertThat(TeachGate.blocked(UserLevels.EXPERT, TeachGate.Category.ROUTE)).isFalse();
        assertThat(TeachGate.blocked(UserLevels.EXPERT, TeachGate.Category.SPEEDRUN)).isFalse();
    }

    @Test
    void 普通问题任何等级都不拦() {
        assertThat(TeachGate.blocked(UserLevels.NOVICE, TeachGate.Category.NONE)).isFalse();
        assertThat(TeachGate.blocked(UserLevels.EXPERT, TeachGate.Category.NONE)).isFalse();
    }

    @Test
    void 越界或空的等级按菜鸟处理() {
        // 门禁是"拦"的功能：等级取不到/是脏值时宁可多拦，也不要漏
        assertThat(TeachGate.blocked(null, TeachGate.Category.ROUTE)).isTrue();
        assertThat(TeachGate.blocked(-1, TeachGate.Category.ROUTE)).isTrue();
    }

    // ── 文案 ────────────────────────────────────────────────────────────────

    @Test
    void 文案按等级分支且入门那句不含新手() {
        assertThat(TeachGate.message(UserLevels.NOVICE))
                .isEqualTo("这个对新手还是太难了，等成为高手再来吧！");
        assertThat(TeachGate.message(UserLevels.BEGINNER))
                .isEqualTo("速通的事等你成为高手再聊吧！");
        // 票面明写：同一句套两级会把入门用户叫成新手，所以入门那句绝不能出现"新手"
        assertThat(TeachGate.message(UserLevels.BEGINNER)).doesNotContain("新手");
    }

    // ── 提示词边界（软约束那一半）─────────────────────────────────────────────

    @Test
    void 边界散文按等级给出且带等级名与受限类别() {
        assertThat(TeachGate.boundaryFor(UserLevels.NOVICE)).contains("菜鸟").contains("路线").contains("速通");
        assertThat(TeachGate.boundaryFor(UserLevels.BEGINNER)).contains("入门").contains("速通");
        // 入门可以问路线 —— 边界里不该把路线也禁掉
        assertThat(TeachGate.boundaryFor(UserLevels.BEGINNER)).contains("路线类问题可以正常回答");
        assertThat(TeachGate.boundaryFor(UserLevels.EXPERT)).contains("高手");
    }
}
