package org.example.guide.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「每日路线」的链接构造（契约 §7.4 / #44）：高手问路线类问题时，回一串 B站 攻略链接。
 *
 * <p><b>只构造链接，不爬页面</b>（CONTEXT.md「每日路线」）：搜索页反爬严格，抓不到数据，
 * 而把链接交给用户自己点，演示效果"AI 识别出这是路线问题并给出参考链接"完全成立。
 * 唯一一次网络调用是为了拿到**具体视频**（{@link #latestFromUps}），打的是 B站 的 API、
 * 不是页面；它失败就降级，不影响整体可用。
 *
 * <p><b>三级降级链</b>（票面）：{@link #links} 按下面的顺序取第一条非空的结果。
 * 三级都能在运行中的应用里走到（票面 AC「三级都要能演示」）：
 * <ol>
 *   <li>拿到具体投稿 → 发布时间最新的那条视频链接；</li>
 *   <li>API 回得来（至少一个 UP 主空间够得着）但没有可用投稿 → UP 主空间内搜索页；</li>
 *   <li>API 一个都够不着（风控 / 网络失败）→ B站 全站搜索页。</li>
 * </ol>
 * 第二与第三级的分界是 {@link LatestVideos#spaceReachable}：够不着 UP 主时，给它的空间内
 * 搜索页没有意义，退到全站搜索。解析、链的选取、链接成形都在**包级可见的方法**里，
 * 单测用假的响应/桩钉住它们，不真连 B站（票面 AC）。
 *
 * <p><b>为什么不复用 {@code RestClientConfig} 那个共享的 {@code RestClient.Builder}</b>：
 * 那个 builder 是可变对象、又是单例，{@code DeepSeekClient} 与它共用同一个实例；
 * 在这里给它 {@code baseUrl} / 默认头会**串到 DeepSeek 的请求上**。路线这一路自己建一个，
 * 顺带把超时调短 —— 它卡住时要尽快降级到搜索页，不能陪着用户的提问一起干等。
 */
@Component
public class BilibiliRouteService {

    private static final Logger log = LoggerFactory.getLogger(BilibiliRouteService.class);

    /** 两个并列的 UP 主（契约 §7.4）。结果按发布时间排序取最新，不分先后 */
    static final List<String> UIDS = List.of("153410438", "519862225");

    private static final String BASE_URL = "https://api.bilibili.com";

    /** 按 uid 拉最新投稿。走 API 不走页面 —— 页面的反爬认不出，这里只要一个 bvid */
    private static final String LATEST_VIDEOS_PATH = "/x/space/arc/search";

    /** 拉最近几条投稿足够排序取最新；再多只是白花一次请求 */
    private static final int PAGE_SIZE = 5;

    /** 具体视频的落地页 */
    private static final String VIDEO_URL = "https://www.bilibili.com/video/%s";

    /** 第二级：UP 主空间内的搜索页 */
    private static final String SPACE_SEARCH_URL = "https://space.bilibili.com/%s/search/video?keyword=%s";

    /** 第三级：B站 全站搜索页 */
    private static final String GLOBAL_SEARCH_URL = "https://search.bilibili.com/all?keyword=%s";

    /** B站 的风控对没有 UA 的请求不友好；Referer 也一并给上，贴近真实浏览器 */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final String REFERER = "https://space.bilibili.com/";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public BilibiliRouteService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .defaultHeader(HttpHeaders.REFERER, REFERER)
                .requestFactory(requestFactory())
                .build();
    }

    /**
     * 三级降级链的入口：取第一条非空的链接清单。
     *
     * <p>返回的每个元素形如 {@code {"title": "...", "url": "..."}}，与契约 §7.4 的 {@code links} 一致。
     * 第三级恒非空，所以本方法**不会返回空表**。
     */
    public List<Map<String, Object>> links() {
        LatestVideos attempt = latestFromUps();
        if (!attempt.videos().isEmpty()) {
            // 票面 AC：两个 UP 主的视频都参与排序，取**发布时间最新**的那条
            Video latest = attempt.videos().stream().max(Comparator.comparingLong(Video::pubdate)).orElseThrow();
            return List.of(link(latest.title(), VIDEO_URL.formatted(latest.bvid())));
        }
        if (attempt.spaceReachable()) {
            return spaceSearchLinks();
        }
        return globalSearchLink();
    }

    /**
     * 遍历两个 UP 主，合并投稿并记录"是否至少有一个空间够得着"。
     *
     * <p>某一个 UP 主拉失败只记日志、不影响另一个（票面 AC）。**包级可见是为了单测** ——
     * 单测用 Mockito 桩掉 {@link #fetchLatestVideos} 的返回值，直接钉住排序与降级，
     * 不真连 B站（票面 AC）。
     */
    LatestVideos latestFromUps() {
        List<Video> all = new ArrayList<>();
        boolean spaceReachable = false;
        for (String uid : UIDS) {
            try {
                LatestVideos result = fetchLatestVideos(uid);
                spaceReachable |= result.spaceReachable();
                all.addAll(result.videos());
            } catch (Exception e) {
                // 一个 UP 主拉不到不该拖垮另一个：B站 风控、网络抖动都算这一类
                log.warn("拉 UP 主 {} 的最新投稿失败，改试下一个", uid, e);
            }
        }
        return new LatestVideos(all, spaceReachable);
    }

    /** 第二级：每个 UP 主一条"空间内搜索页"链接 */
    List<Map<String, Object>> spaceSearchLinks() {
        String keyword = keywordOf(LocalDate.now());
        String encoded = encode(keyword);
        List<Map<String, Object>> links = new ArrayList<>();
        for (String uid : UIDS) {
            links.add(link("在 UP 主空间里搜「" + keyword + "」", SPACE_SEARCH_URL.formatted(uid, encoded)));
        }
        return links;
    }

    /** 第三级：B站 全站搜索页。恒返回一条，是整条链的兜底 */
    List<Map<String, Object>> globalSearchLink() {
        String keyword = keywordOf(LocalDate.now());
        return List.of(link("B站 全站搜「" + keyword + "」", GLOBAL_SEARCH_URL.formatted(encode(keyword))));
    }

    /**
     * 按 uid 拉最新投稿。
     *
     * @throws Exception HTTP 失败（含风控）时抛出，由 {@link #latestFromUps} 记日志后跳过；
     *                   响应解析不了不抛，返回 {@code spaceReachable=false} 的空结果
     */
    LatestVideos fetchLatestVideos(String uid) {
        String body = restClient.get()
                .uri(builder -> builder
                        .path(LATEST_VIDEOS_PATH)
                        .queryParam("mid", uid)
                        .queryParam("ps", PAGE_SIZE)
                        .queryParam("pn", 1)
                        .build())
                .retrieve()
                .body(String.class);
        return parseLatestVideos(body);
    }

    /**
     * 把 B站 的投稿列表响应解析成"投稿 + 空间够不够得着"。**包级可见是为了单测**：
     * 给它一段假的响应文本，看它取出什么。
     *
     * <p>{@code code == 0} 才认为空间够得着（{@code spaceReachable=true}）；风控回非 0
     * （如 -352）或响应不是合法 JSON 都当"够不着"，**不抛异常** —— 降级链据此退到全站搜索页。
     */
    LatestVideos parseLatestVideos(String json) {
        LatestVideosResponse response;
        try {
            response = objectMapper.readValue(json, LatestVideosResponse.class);
        } catch (Exception e) {
            log.warn("B站 投稿响应解析失败，按够不着处理", e);
            return new LatestVideos(List.of(), false);
        }
        if (response == null || response.code() != 0 || response.data() == null
                || response.data().list() == null || response.data().list().vlist() == null) {
            log.info("B站 投稿响应没有可用数据，按够不着处理：code={}",
                    response == null ? null : response.code());
            return new LatestVideos(List.of(), false);
        }
        List<Video> videos = new ArrayList<>();
        for (LatestVideosResponse.VideoNode node : response.data().list().vlist()) {
            if (node != null && node.bvid() != null && !node.bvid().isBlank()) {
                // pubdate 是包装类型：投稿项缺这个字段时别让整包解析失败，缺就当 0（排到最后）
                long pubdate = node.pubdate() == null ? 0L : node.pubdate();
                videos.add(new Video(node.bvid(), node.title() == null ? "" : node.title(), pubdate));
            }
        }
        return new LatestVideos(videos, true);
    }

    /** 搜索关键词：日期用**当天真实日期**（契约 §7.4 / story 29） */
    static String keywordOf(LocalDate date) {
        return "peak" + date + "路线";
    }

    private static String encode(String keyword) {
        return UriUtils.encodeQueryParam(keyword, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> link(String title, String url) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", title);
        map.put("url", url);
        return map;
    }

    /** 路线这一路自己的超时：卡住时要尽快降级，不能陪着用户的提问干等 */
    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    /** 一条投稿。只留排序与拼链接用得上的字段 */
    record Video(String bvid, String title, long pubdate) {
    }

    /**
     * 第一级的一次尝试：两个 UP 主合并后的投稿，以及**是否至少有一个空间够得着**。
     *
     * <p>{@code spaceReachable} 是第二级与第三级的分界：够得着才给 UP 主空间内搜索页，
     * 否则退到全站搜索页。
     */
    record LatestVideos(List<Video> videos, boolean spaceReachable) {
    }

    /** B站 {@code x/space/arc/search} 的响应外壳（只取用到的层级） */
    record LatestVideosResponse(int code, String message, Data data) {

        record Data(ListWrapper list) {
        }

        record ListWrapper(List<VideoNode> vlist) {
        }

        record VideoNode(String bvid, String title, Long pubdate) {
        }
    }
}
