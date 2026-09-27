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
    public BaseResult next(String openid) {
        User user = userService.findByOpenid(openid);
        if (user == null) {
            return fail("请先登录");
        }
        int level = levelOf(user);

        List<QuizQuestion> bank = quizBankCache.get(level);
        if (bank == null || bank.isEmpty()) {
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
                bank = quizGenerator.generate(user.getApiKey(), items, level);
            } catch (AiException e) {
                return fail(e.getMessage());
            }
            quizBankCache.put(level, bank);
        }

        // 题库不少于 QuizGenerator.MIN_QUESTIONS（10）道，而一个周期的排除集最多 9 个
        // （满 10 就清零/升级），所以这里必定还挑得出一道没答对的题 —— 不需要"排空就回退全量"的兜底
        Set<Integer> answered = quizProgressCache.get(openid).answeredIdsOrEmpty();
        List<QuizQuestion> candidates = bank.stream()
                .filter(question -> question.getId() != null && !answered.contains(question.getId()))
                .toList();

        QuizQuestion question = candidates.get(random.nextInt(candidates.size()));
        QuizNextDto dto = new QuizNextDto(question.getId(), question.getStem(), question.getOptions());
        return BaseResult.setResult(ResultCodeEnum.SUCCESS, dto.toMap());
    }

    @Override
    public BaseResult answer(String openid, Integer questionId, Integer choice) {
        User user = userService.findByOpenid(openid);
        if (user == null) {
            return fail("请先登录");
        }
        if (questionId == null || choice == null) {
            return fail("请先选择答案");
        }

        int level = levelOf(user);
        QuizQuestion question = find(quizBankCache.get(level), questionId);
        if (question == null) {
            // 题库被采集失效过、或用户换了等级 —— 这一题已无从判起，让他重抽
            return fail("题目已过期，请重新开始");
        }

        boolean correct = choice.equals(question.getAnswerIndex());
        QuizStreak.Outcome outcome = QuizStreak.advance(correct, level, questionId, quizProgressCache.get(openid));
        if (outcome.levelUp()) {
            userService.updateLevel(openid, outcome.level());
        }
        quizProgressCache.put(openid, new QuizProgress(outcome.streak(), outcome.answeredIds()));

        QuizAnswerDto dto = new QuizAnswerDto(
                outcome.correct(),
                question.getAnswerIndex(),
                question.getExplanation(),
                outcome.streak(),
                outcome.level(),
                outcome.levelUp());
        return BaseResult.setResult(ResultCodeEnum.SUCCESS, dto.toMap());
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
        // 只清进度，不碰 level —— 等级是"练出来的成果"，重置练习不该把等级也退了
        quizProgressCache.evict(openid);
        return BaseResult.setResult(ResultCodeEnum.SUCCESS, null);
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
