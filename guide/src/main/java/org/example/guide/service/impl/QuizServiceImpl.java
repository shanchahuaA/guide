package org.example.guide.service.impl;

import org.example.guide.ai.AiException;
import org.example.guide.ai.QuizGenerator;
import org.example.guide.cache.ItemCache;
import org.example.guide.cache.QuizBankCache;
import org.example.guide.cache.QuizProgressCache;
import org.example.guide.pojo.Item;
import org.example.guide.pojo.QuizProgress;
import org.example.guide.pojo.QuizQuestion;
import org.example.guide.pojo.User;
import org.example.guide.pojo.dto.QuizAnswerDto;
import org.example.guide.pojo.dto.QuizNextDto;
import org.example.guide.service.IQuizService;
import org.example.guide.service.IUserService;
import org.example.guide.service.QuizStreak;
import org.example.guide.service.UserLevels;
import org.example.guide.utils.BaseResult;
import org.example.guide.utils.ResultCodeEnum;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 练习的编排（#42）：按等级抽题、判题、维护连对与升级。
 *
 * <p><b>抽题</b>：题库按等级懒生成（不在缓存里才调大模型），抽题随机，且**当前连对周期内
 * 已答对的题不再出**（排除集来自连对缓存）。响应只吐题号 / 题干 / 选项。
 *
 * <p><b>判题</b>：正确与否由这里比对 {@code answerIndex}，**不交给大模型** ——
 * 让模型判对错既有延迟又有不确定性，而下标比对是纯函数。
 *
 * <p>连对与升级的全部规则收在 {@link QuizStreak}（纯函数），本类只负责把它的结果落库、落缓存。
 */
@Service
public class QuizServiceImpl implements IQuizService {

    private final IUserService userService;
    private final ItemCache itemCache;
    private final QuizGenerator quizGenerator;
    private final QuizBankCache quizBankCache;
    private final QuizProgressCache quizProgressCache;

    /** 抽题随机。演示环境单机单用户，{@code Random} 够用，不必上 {@code SecureRandom} */
    private final Random random = new Random();

    public QuizServiceImpl(IUserService userService,
                           ItemCache itemCache,
                           QuizGenerator quizGenerator,
                           QuizBankCache quizBankCache,
                           QuizProgressCache quizProgressCache) {
        this.userService = userService;
        this.itemCache = itemCache;
        this.quizGenerator = quizGenerator;
        this.quizBankCache = quizBankCache;
        this.quizProgressCache = quizProgressCache;
    }

    @Override
    public BaseResult next(String openid, Integer bankLevel) {
        User user = userService.findByOpenid(openid);
        if (user == null) {
            return fail("请先登录");
        }
        int level = levelOf(user);
        int bank = bankLevel == null ? level : bankLevel;
        if (bank < UserLevels.NOVICE || bank > level) {
            return locked();
        }

        List<QuizQuestion> questions = quizBankCache.get(bank);
        if (questions == null || questions.isEmpty()) {
            // 懒生成要用用户自己的 Key（服务端不持有 Key，见 CONTEXT.md「AI 助手」）。
            // 顺序是"先看题库、再看 Key"：题库一旦生成过，之后谁来抽题都不再需要 Key
            if (user.getApiKey() == null || user.getApiKey().isBlank()) {
                return fail("请先填写你的 DeepSeek API Key");
            }
            List<Item> items = itemCache.getAll();
            if (items.isEmpty()) {
                return fail("图鉴数据还取不到，请稍后再试");
            }
            try {
                questions = quizGenerator.generate(user.getApiKey(), items, bank);
            } catch (AiException e) {
                return fail(e.getMessage());
            }
            quizBankCache.put(bank, questions);
        }

        // 题库不少于 QuizGenerator.MIN_QUESTIONS（10）道，而一个周期的排除集最多 9 个
        // （满 10 就清零/升级），所以这里必定还挑得出一道没答对的题 —— 不需要"排空就回退全量"的兜底
        QuizProgress progress = quizProgressCache.get(openid);
        if (!sameBank(progress, bank)) {
            // 换档从零开始（题号跨档重号，旧排除集只会误排），并把归零落盘 ——
            // 这样身份条上的连对立刻跟着归零，而不是等下一题答完才变
            progress = QuizProgress.empty(bank);
            quizProgressCache.put(openid, progress);
        }
        Set<Integer> answered = progress.answeredIdsOrEmpty();
        List<QuizQuestion> candidates = questions.stream()
                .filter(question -> question.getId() != null && !answered.contains(question.getId()))
                .toList();

        QuizQuestion question = candidates.get(random.nextInt(candidates.size()));
        QuizNextDto dto = new QuizNextDto(question.getId(), question.getStem(), question.getOptions());
        return BaseResult.setResult(ResultCodeEnum.SUCCESS, dto.toMap());
    }

    @Override
    public BaseResult answer(String openid, Integer bankLevel, Integer questionId, Integer choice) {
        User user = userService.findByOpenid(openid);
        if (user == null) {
            return fail("请先登录");
        }
        int level = levelOf(user);
        int bank = bankLevel == null ? level : bankLevel;
        if (bank < UserLevels.NOVICE || bank > level) {
            return locked();
        }
        if (questionId == null || choice == null) {
            return fail("请先选择答案");
        }

        QuizQuestion question = find(quizBankCache.get(bank), questionId);
        if (question == null) {
            // 题库被采集失效过、或换了档 —— 这一题已无从判起，让他重抽
            return fail("题目已过期，请重新开始");
        }

        boolean correct = choice.equals(question.getAnswerIndex());
        QuizProgress stored = quizProgressCache.get(openid);
        // 换档当空进度（**不落盘**：下面那次 put 会带上新档位）
        QuizProgress current = sameBank(stored, bank) ? stored : QuizProgress.empty(bank);
        QuizStreak.Outcome outcome = QuizStreak.advance(correct, level, questionId, current);

        // 升级只认**练自己那一档**：回头刷低档可以练手，但不推进等级（契约 §7.3）。
        // 连对满 10 照样进新周期（否则排除集会无上限地攒），只是不升级 —— 与"已是高手"同一种处理
        boolean ownBank = bank == level;
        boolean levelUp = ownBank && outcome.levelUp();
        if (levelUp) {
            userService.updateLevel(openid, outcome.level());
        }
        quizProgressCache.put(openid, new QuizProgress(outcome.streak(), outcome.answeredIds(), bank));

        QuizAnswerDto dto = new QuizAnswerDto(
                outcome.correct(),
                question.getAnswerIndex(),
                question.getExplanation(),
                outcome.streak(),
                ownBank ? outcome.level() : level,
                levelUp);
        return BaseResult.setResult(ResultCodeEnum.SUCCESS, dto.toMap());
    }

    /**
     * 这份进度是不是属于这一档。**老记录没有 {@code bankLevel}，一律按"不是"处理** ——
     * 那意味着排除集来自上一版（或上一档），不可信，换档从零开始最安全。
     */
    private static boolean sameBank(QuizProgress progress, int bank) {
        return progress != null && progress.getBankLevel() != null && progress.getBankLevel() == bank;
    }

    /** 档位高于自己的等级：是"还没解锁"，不是系统错误（等级单调包含，见 IQuizService.next） */
    private static BaseResult locked() {
        BaseResult result = BaseResult.setResult(ResultCodeEnum.FORBIDDEN, null);
        result.setMessage("这个档位还没解锁，先把等级练上去");
        return result;
    }

    @Override
    public int streakOf(String openid) {
        return quizProgressCache.get(openid).getStreak();
    }

    @Override
    public BaseResult reset(String openid) {
        User user = userService.findByOpenid(openid);
        if (user == null) {
            return fail("请先登录");
        }
        // 连对、排除集、**等级**一起归零：这是"重置进度"而不是"清缓存" ——
        // 高手也要退回菜鸟、从第一档重新练起
        quizProgressCache.evict(openid);
        userService.updateLevel(openid, UserLevels.NOVICE);
        return BaseResult.setResult(ResultCodeEnum.SUCCESS, Map.of("level", UserLevels.NOVICE));
    }

    @Override
    public void resetProgress(String openid) {
        // 跳级换档专用：只清过程状态，**不动等级**（等级是刚加完的那一格）
        quizProgressCache.evict(openid);
    }

    private static int levelOf(User user) {
        return user.getLevel() == null ? UserLevels.NOVICE : user.getLevel();
    }

    private static QuizQuestion find(List<QuizQuestion> bank, Integer questionId) {
        if (bank == null) {
            return null;
        }
        for (QuizQuestion question : bank) {
            if (question != null && questionId.equals(question.getId())) {
                return question;
            }
        }
        return null;
    }

    private static BaseResult fail(String message) {
        BaseResult result = BaseResult.setResult(ResultCodeEnum.FAILURE, null);
        result.setMessage(message);
        return result;
    }
}
