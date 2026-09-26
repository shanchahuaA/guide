package org.example.guide.service.impl;

import org.example.guide.mapper.UserMapper;
import org.example.guide.pojo.User;
import org.example.guide.service.UserLevels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录"建或取"逻辑的回归。mock 掉 mapper，**不加载 Spring、不连库** ——
 * 这里钉的是分支：已有行原样返回、没有行才插、且新行必须是 0 菜鸟。
 * 真库上的"同一 openid 不产生第二行"由 scripts/smoke-test.ps1 覆盖。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void existingUserIsReturnedWithoutInserting() {
        User existing = new User();
        existing.setOpenid("oExisting");
        existing.setLevel(UserLevels.BEGINNER);
        when(userMapper.selectById("oExisting")).thenReturn(existing);

        User result = userService.findOrCreateByOpenid("oExisting");

        assertThat(result).isSameAs(existing);
        assertThat(result.getLevel()).isEqualTo(UserLevels.BEGINNER);
        // 已有行不能再插 —— 这正是用户行被撞成两行的那条路径
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void missingUserIsCreatedAsNovice() {
        when(userMapper.selectById("oNew")).thenReturn(null);

        User result = userService.findOrCreateByOpenid("oNew");

        assertThat(result.getOpenid()).isEqualTo("oNew");
        assertThat(result.getLevel()).isEqualTo(UserLevels.NOVICE);
        assertThat(result.getCreateTime()).isNotNull();
        verify(userMapper, times(1)).insert(any(User.class));
    }
}
