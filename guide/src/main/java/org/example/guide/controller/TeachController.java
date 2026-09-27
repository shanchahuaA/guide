package org.example.guide.controller;

import lombok.extern.slf4j.Slf4j;
import org.example.guide.config.AvatarStorage;
import org.example.guide.pojo.User;
import org.example.guide.pojo.dto.ProfileDto;
import org.example.guide.service.IQuizService;
import org.example.guide.service.ITeachService;
import org.example.guide.service.IUserService;
import org.example.guide.service.QuizStreak;
import org.example.guide.service.TeachGate;
import org.example.guide.service.UserLevels;
import org.example.guide.utils.BaseResult;
import org.example.guide.utils.ResultCodeEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * AI 教学的身份、问答与练习（契约 §7.0 / §7.1 / §7.2 / §7.3 / §7.4 / §7.6）。
 *
 * <p><b>鉴权：Shiro 过滤链已收口 {@code /api/teach/**}（{@code tokenAuthc}）</b>（2026-09-27 合入）——
 * 缺 token 或查不到人时由 {@code config/TokenAuthFilter} 直接回 HTTP 401 + 响应壳
 * {@code code = 401}，请求根本到不了这里。控制器里保留的手工校验（{@code userByToken} /
 * {@code unauthorized}）只是兜底：过滤链被绕过、或单测直调控制器方法时才会用到；
 * 它同时负责取出 User 行（等级、Key），所以不能整个删掉。
 *
 * <p><b>越级门禁分两层</b>（契约 §7.0）：
 * <ul>
 *   <li><b>路径层</b>在 Shiro 过滤链上 —— {@code /api/teach/beginner/**} 要 beginner（≥入门，
 *       如「今日路线」）、{@code /api/teach/expert/**} 要 expert（仅高手，如速通），拦下的回 HTTP 403；</li>
 *   <li><b>内容层</b>是 {@link TeachGate} —— 同一个 {@code /api/teach/ask} 里按问题分类拦越级
 *       （自由文本的兜底），拦下的回 {@code code = 403} + 按等级分支的中文文案。</li>
 * </ul>
 * 两层缺一不可：路径拦不住"把越级问题塞进 {@code /ask}"，内容层又表达不了"哪条路径归哪一档"。
 */
@Slf4j
@RestController
public class TeachController {

    @Autowired
    private IUserService userService;

    @Autowired
    private ITeachService teachService;

    @Autowired
    private IQuizService quizService;

    @Autowired
    private AvatarStorage avatarStorage;

    /** 身份条与分段门禁都靠它。streak 来自连对缓存（#42），level 来自 user 行 */
    @GetMapping("/api/teach/profile")
    public BaseResult profile(@RequestHeader(value = "token", required = false) String token) {
        User user = userByToken(token);
        if (user == null) {
            return unauthorized();
        }
        ProfileDto dto = new ProfileDto(
                user.getLevel(),
                UserLevels.nameOf(user.getLevel()),
                quizService.streakOf(user.getOpenid()),
                QuizStreak.TARGET,
                hasApiKey(user),
                user.getNickname(),
                user.getAvatar());
        return BaseResult.setResult(ResultCodeEnum.SUCCESS, dto.toMap());
    }

    /**
     * 保存微信昵称 / 头像（个人页）。
     *
     * <p>body 传 {@code {nickname, avatar}}，**只写传了的那个**（一个空白不影响另一个）。
     * 两个都空白才回参数错误 —— 允许"只改昵称"或"只换头像"。
     */
    @PostMapping("/api/teach/profile")
    public BaseResult updateProfile(@RequestHeader(value = "token", required = false) String token,
                                    @RequestBody(required = false) Map<String, String> body) {
        User user = userByToken(token);
        if (user == null) {
            return unauthorized();
        }
        String nickname = body == null ? null : body.get("nickname");
        String avatar = body == null ? null : body.get("avatar");
        if (blank(nickname) && blank(avatar)) {
            BaseResult bad = BaseResult.setResult(ResultCodeEnum.PARAM_ERROR, null);
            bad.setMessage("昵称和头像不能都为空");
            return bad;
        }
        userService.updateProfile(user.getOpenid(), nickname, avatar);
        return BaseResult.setResult(ResultCodeEnum.SUCCESS, null);
    }

    /**
     * 收下并保存头像文件（个人页）。入参是 multipart 的 {@code file}，出参 {@code {url}}。
     *
     * <p>为什么必须真上传：小程序 {@code chooseAvatar} 给的只是一个**临时路径**（{@code wxfile://…}），
     * 进程一退就失效，直接把它写进库等于存了个废链接。所以先落到后端本地、再回一个稳定 URL
     * （静态映射见 {@code config/WebMvcConfig}），个人页拿这个 URL 再调 {@link #updateProfile} 落库。
     *
     * <p>文件名用 openid（经 {@link AvatarStorage#safeFileName} 白名单收敛，防路径穿越），
     * 扩展名沿用上传的（默认 png）—— 静态映射按扩展名给 Content-Type，写错后缀会让图片打不开。
     */
    @PostMapping(value = "/api/teach/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BaseResult uploadAvatar(@RequestHeader(value = "token", required = false) String token,
                                   @RequestParam(value = "file", required = false) MultipartFile file) {
        User user = userByToken(token);
        if (user == null) {
            return unauthorized();
        }
        if (file == null || file.isEmpty()) {
            BaseResult bad = BaseResult.setResult(ResultCodeEnum.PARAM_ERROR, null);
            bad.setMessage("file 不能为空");
            return bad;
        }
        String fileName = AvatarStorage.safeFileName(user.getOpenid()) + imageExtension(file.getOriginalFilename());
        try {
            Files.createDirectories(avatarStorage.directory());
            Path target = avatarStorage.directory().resolve(fileName);
            file.transferTo(target);
        } catch (IOException e) {
            // 上传失败如实回错误壳，**不是 500**（契约 §7.5 的口径）
            log.warn("保存头像失败：{}", e.getMessage());
            BaseResult failed = BaseResult.setResult(ResultCodeEnum.FAILURE, null);
            failed.setMessage("头像保存失败：" + e.getMessage());
            return failed;
        }
        return BaseResult.setResult(ResultCodeEnum.SUCCESS, Map.of("url", avatarStorage.urlFor(fileName)));
    }

    /** 只认图片后缀（白名单），其余一律按 .png 存 —— 后缀决定了静态映射给的 Content-Type */
    private static String imageExtension(String originalFilename) {
        if (originalFilename != null) {
            int dot = originalFilename.lastIndexOf('.');
            if (dot >= 0) {
                String ext = originalFilename.substring(dot + 1).toLowerCase();
                if (ext.matches("png|jpg|jpeg|gif|webp")) {
                    return "." + ext;
                }
            }
        }
        return ".png";
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    /**
     * 收下用户自己的 DeepSeek Key（契约 §7.2）。
     *
     * <p><b>不预校验</b>：写进 {@code user.api_key} 即回 200，Key 有没有效要到真正调用时才知道
     * （错误码见 §7.5）。拿这一条去做一次"试调"看似稳妥，实际是把用户的 Key 多用一次、
     * 还多一个"预校验说有效、真调用说无效"的中间态。
     */
    @PostMapping("/api/teach/apikey")
    public BaseResult saveApiKey(@RequestHeader(value = "token", required = false) String token,
                                 @RequestBody(required = false) Map<String, String> body) {
        User user = userByToken(token);
        if (user == null) {
            return unauthorized();
        }
        String apiKey = body == null ? null : body.get("apiKey");
        if (apiKey == null || apiKey.isBlank()) {
            BaseResult bad = BaseResult.setResult(ResultCodeEnum.PARAM_ERROR, null);
            bad.setMessage("apiKey 不能为空");
            return bad;
        }
        userService.updateApiKey(user.getOpenid(), apiKey);
        return BaseResult.setResult(ResultCodeEnum.SUCCESS, null);
    }

    /**
     * 图鉴内问答（契约 §7.4）。入参 {@code {question}}，出参 {@code data.answer}。
     *
     * <p>编排在 {@link ITeachService#ask}：缓存 → Key → 图鉴上下文 → DeepSeek → 回填缓存。
     * 大模型的失败在这里变成 {@code -100} + 中文提示（**不是 500**），映射的唯一一处在
     * {@code ai/DeepSeekClient}。
     */
    @PostMapping("/api/teach/ask")
    public BaseResult ask(@RequestHeader(value = "token", required = false) String token,
                          @RequestBody(required = false) Map<String, String> body) {
        User user = userByToken(token);
        if (user == null) {
            return unauthorized();
        }
        String question = body == null ? null : body.get("question");

        // 越级门禁（#43）拦在**路由层、服务之前**：拦下的不查回答缓存、不调大模型（票面 AC）。
        // 放在这一层而不是服务里，是因为等级只有拿到 user 行才知道，而回答缓存的 key 只认问题文本 ——
        // 若先查缓存，一个高手问过的速通问题会把缓存里的答案漏给随后提问的菜鸟。
        TeachGate.Category category = TeachGate.classify(question);
        if (TeachGate.blocked(user.getLevel(), category)) {
            return blocked(TeachGate.message(user.getLevel()));
        }
        return teachService.ask(user.getOpenid(), question, user.getLevel());
    }

    /**
     * 「今日路线」的分级入口（契约 §7.4 / §7.7）。
     *
     * <p>等级门禁在 **Shiro 路径层**：{@code /api/teach/beginner/**} 要 beginner（≥入门），
     * 菜鸟在过滤链上就被 HTTP 403 拦下、到不了这里。链接由后端构造、不调大模型。
     */
    @PostMapping("/api/teach/beginner/route")
    public BaseResult route(@RequestHeader(value = "token", required = false) String token) {
        User user = userByToken(token);
        if (user == null) {
            return unauthorized();
        }
        return teachService.route();
    }

    /**
     * 速通类的分级入口（契约 §7.0 / §7.7）。
     *
     * <p>等级门禁在 **Shiro 路径层**：{@code /api/teach/expert/**} 要 expert（仅高手）。
     * 路径已保证等级够，所以不再走 {@link #ask} 的内容门禁，直接按问答那条链答。
     */
    @PostMapping("/api/teach/expert/speedrun")
    public BaseResult speedrun(@RequestHeader(value = "token", required = false) String token,
                               @RequestBody(required = false) Map<String, String> body) {
        User user = userByToken(token);
        if (user == null) {
            return unauthorized();
        }
        String question = body == null ? null : body.get("question");
        return teachService.ask(user.getOpenid(), question, user.getLevel());
    }

    /** 内容门禁拦下：{@code code = 403} + 按等级分支的文案，前端 reject 分支直接弹它 */
    private static BaseResult blocked(String message) {
        BaseResult result = BaseResult.setResult(ResultCodeEnum.FORBIDDEN, null);
        result.setMessage(message);
        return result;
    }

    /**
     * 抽一道题（契约 §7.3）。响应**只有题号 / 题干 / 选项**，答案只在
     * {@link #quizAnswer} 的响应里出现 —— 抓包也不该能推出正确项。
     *
     * <p>题库懒生成在 {@code IQuizService.next} 里：该级题库不在缓存时才调大模型。
     */
    @PostMapping("/api/teach/quiz/next")
    public BaseResult quizNext(@RequestHeader(value = "token", required = false) String token) {
        User user = userByToken(token);
        if (user == null) {
            return unauthorized();
        }
        return quizService.next(user.getOpenid());
    }

    /**
     * 作答（契约 §7.3）。body 传 {@code {questionId, choice}}，对错由后端比对下标。
     *
     * <p>返回里带正确项与解析，同时带作答后的 {@code streak / level / upgraded} ——
     * 前端据此就地把身份条刷新，不必再打一次 profile。
     */
    @PostMapping("/api/teach/quiz/answer")
    public BaseResult quizAnswer(@RequestHeader(value = "token", required = false) String token,
                                 @RequestBody(required = false) Map<String, Object> body) {
        User user = userByToken(token);
        if (user == null) {
            return unauthorized();
        }
        return quizService.answer(user.getOpenid(), asInt(body, "questionId"), asInt(body, "choice"));
    }

    /** JSON 体里的数字可能是 Integer / Double / String（前端序列化不定），统一收敛成 Integer */
    private static Integer asInt(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 重置练习进度（契约 §7.3）：连对与排除集归零，之后重新抽题。
     *
     * <p>顺便也修掉一个真实的坑：跳级换档后排除集里还留着**旧题库的题号**，跨等级不成立。
     */
    @PostMapping("/api/teach/quiz/reset")
    public BaseResult quizReset(@RequestHeader(value = "token", required = false) String token) {
        User user = userByToken(token);
        if (user == null) {
            return unauthorized();
        }
        return quizService.reset(user.getOpenid());
    }

    /**
     * 演示用跳级后门（契约 §7.6）：当前用户 level + 1，封顶 2。
     *
     * <p>与 {@code UserController.testInsertUser} 同一类东西 —— 演示后门，留着不删。
     *
     * <p>换档后**顺带把连对进度清掉**：题库是按等级的，排除集里是旧等级的题号，
     * 不清则新等级的抽题会被一堆不相干的题号误排，且前端重抽时容易撞"题目已过期"。
     */
    @PostMapping("/api/teach/dev/level")
    public BaseResult devLevel(@RequestHeader(value = "token", required = false) String token) {
        User user = userByToken(token);
        if (user == null) {
            return unauthorized();
        }
        int next = UserLevels.nextLevel(user.getLevel());
        userService.updateLevel(user.getOpenid(), next);
        quizService.reset(user.getOpenid());
        return BaseResult.setResult(ResultCodeEnum.SUCCESS, Map.of("level", next));
    }

    /** 身份条靠它决定要不要引导用户先填 Key（契约 §7.1） */
    private static boolean hasApiKey(User user) {
        return user.getApiKey() != null && !user.getApiKey().isBlank();
    }

    /** token 即 openid；空 token 或查不到人都当未登录 */
    private User userByToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        return userService.findByOpenid(token);
    }

    private BaseResult unauthorized() {
        return BaseResult.setResult(ResultCodeEnum.UNAUTHORIZED, null);
    }
}
