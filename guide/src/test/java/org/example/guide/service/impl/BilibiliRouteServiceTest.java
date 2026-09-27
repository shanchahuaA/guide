package org.example.guide.service.impl;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/**
 * 「每日路线」的链接构造（#44）。**不真连 B站**（票面 AC）：解析用假的响应文本，
 * 三级降级链的选取用 spy 桩掉 {@code fetchLatestVideos}。
 *
 * <p>真正会静默错的几处在这里钉死：
 * <ul>
 *   <li>两个 UP 主**都**要参与排序、取**最新**的那条（漏排序会让答案随列表顺序漂）；</li>
 *   <li>风控回非 0 的 {@code code} 时当"空间够不着"而不是抛异常，好让链子下退到全站搜索页；</li>
 *   <li>**三级都有明确的触发条件**，都能在应用里走到（票面 AC「三级都要能演示」）：
 *       有投稿 → 视频；够得着 UP 主但没投稿 → 空间搜索页；一个都够不着 → 全站搜索页；</li>
 *   <li>关键词里是**当天真实日期**，且三级链接都落在 {@code bilibili.com} 域下。</li>
 * </ul>
 */
class BilibiliRouteServiceTest {

    private static final String UID_A = "153410438";
    private static final String UID_B = "519862225";

    /** 解析与降级都不发请求，RestClient 只是构造出来的壳 */
    private final BilibiliRouteService service =
            new BilibiliRouteService(JsonMapper.builder().build());

    // ── 第一级：解析投稿 ──────────────────────────────────────────────────────

    @Test
    void 解析出投稿的bvid标题与发布时间() {
        BilibiliRouteService.LatestVideos parsed = service.parseLatestVideos("""
                {"code":0,"message":"0","data":{"list":{"vlist":[
                  {"bvid":"BV1old","title":"昨天的路线","pubdate":100},
                  {"bvid":"BV2new","title":"今天的路线","pubdate":200}
                ]}}}
                """);

        assertThat(parsed.spaceReachable()).isTrue();
        assertThat(parsed.videos()).hasSize(2);
        assertThat(parsed.videos().get(0).bvid()).isEqualTo("BV1old");
        assertThat(parsed.videos().get(0).title()).isEqualTo("昨天的路线");
        assertThat(parsed.videos().get(0).pubdate()).isEqualTo(100L);
        assertThat(parsed.videos().get(1).pubdate()).isEqualTo(200L);
    }

    @Test
    void 没有bvid的项被跳过() {
        BilibiliRouteService.LatestVideos parsed = service.parseLatestVideos("""
                {"code":0,"data":{"list":{"vlist":[
                  {"title":"没有 bvid"},
                  {"bvid":"BV9","title":"正常","pubdate":1}
                ]}}}
                """);

        assertThat(parsed.videos()).hasSize(1);
        assertThat(parsed.videos().get(0).bvid()).isEqualTo("BV9");
    }

    @Test
    void 空间够得着但没有投稿时空表但可达() {
        // code=0 说明 UP 主空间能查到（入口就是够得着的），只是这一批没有可用投稿。
        // 这一态正是第二级"空间内搜索页"的触发条件
        BilibiliRouteService.LatestVideos parsed =
                service.parseLatestVideos("{\"code\":0,\"data\":{\"list\":{\"vlist\":[]}}}");

        assertThat(parsed.videos()).isEmpty();
        assertThat(parsed.spaceReachable()).isTrue();
    }

    @Test
    void 风控的非零code当够不着而不是抛异常() {
        // -352 是 B站 风控的常见返回；这种时候应当降级到全站搜索页，而不是把整条链炸掉
        BilibiliRouteService.LatestVideos parsed =
                service.parseLatestVideos("{\"code\":-352,\"message\":\"风控校验失败\"}");

        assertThat(parsed.videos()).isEmpty();
        assertThat(parsed.spaceReachable()).isFalse();
    }

    @Test
    void 响应不是JSON时也当够不着() {
        BilibiliRouteService.LatestVideos parsed = service.parseLatestVideos("<html>412</html>");

        assertThat(parsed.videos()).isEmpty();
        assertThat(parsed.spaceReachable()).isFalse();
    }

    // ── 第一级：两个 UP 主取最新 ─────────────────────────────────────────────

    @Test
    void 两个UP主的视频都参与排序取最新那条() {
        BilibiliRouteService spyService = spy(service);
        doReturn(reachable(new BilibiliRouteService.Video("BVold", "昨天的路线", 1000L)))
                .when(spyService).fetchLatestVideos(UID_A);
        doReturn(reachable(new BilibiliRouteService.Video("BVnew", "今天的路线", 2000L)))
                .when(spyService).fetchLatestVideos(UID_B);

        List<Map<String, Object>> links = spyService.links();

        assertThat(links).hasSize(1);
        assertThat(links.get(0).get("url")).isEqualTo("https://www.bilibili.com/video/BVnew");
        assertThat(links.get(0).get("title")).isEqualTo("今天的路线");
    }

    @Test
    void 一个UP主拉失败不影响另一个() throws Exception {
        BilibiliRouteService spyService = spy(service);
        doThrow(new RuntimeException("风控")).when(spyService).fetchLatestVideos(UID_A);
        doReturn(reachable(new BilibiliRouteService.Video("BVok", "能拿到的路线", 1000L)))
                .when(spyService).fetchLatestVideos(UID_B);

        List<Map<String, Object>> links = spyService.links();

        assertThat(links).hasSize(1);
        assertThat(links.get(0).get("url")).isEqualTo("https://www.bilibili.com/video/BVok");
    }

    @Test
    void 两个UP主都没投稿时第一级为空() {
        BilibiliRouteService spyService = spy(service);
        doReturn(new BilibiliRouteService.LatestVideos(List.of(), true))
                .when(spyService).fetchLatestVideos(UID_A);
        doReturn(new BilibiliRouteService.LatestVideos(List.of(), true))
                .when(spyService).fetchLatestVideos(UID_B);

        // 只是钉住第一级的出口，降级由下面的链测试覆盖
        assertThat(spyService.latestFromUps().videos()).isEmpty();
    }

    // ── 三级降级链 ──────────────────────────────────────────────────────────

    @Test
    void 够得着UP主但没投稿时退到空间搜索页() {
        BilibiliRouteService spyService = spy(service);
        doReturn(new BilibiliRouteService.LatestVideos(List.of(), true))
                .when(spyService).fetchLatestVideos(UID_A);
        doReturn(new BilibiliRouteService.LatestVideos(List.of(), true))
                .when(spyService).fetchLatestVideos(UID_B);

        List<Map<String, Object>> links = spyService.links();

        assertThat(links).hasSize(2);
        assertThat(urls(links)).allMatch(url -> url.startsWith("https://space.bilibili.com/"));
        assertThat(urls(links)).anyMatch(url -> url.contains(UID_A));
        assertThat(urls(links)).anyMatch(url -> url.contains(UID_B));
        assertThat(urls(links)).allMatch(url -> url.contains("peak" + LocalDate.now()));
    }

    @Test
    void 一个UP主都够不着时退到B站全站搜索页() throws Exception {
        BilibiliRouteService spyService = spy(service);
        doThrow(new RuntimeException("风控")).when(spyService).fetchLatestVideos(UID_A);
        doThrow(new RuntimeException("风控")).when(spyService).fetchLatestVideos(UID_B);

        List<Map<String, Object>> links = spyService.links();

        assertThat(links).hasSize(1);
        assertThat(links.get(0).get("url").toString())
                .startsWith("https://search.bilibili.com/all?keyword=")
                .contains("peak" + LocalDate.now());
    }

    @Test
    void 关键词用当天真实日期() {
        assertThat(BilibiliRouteService.keywordOf(LocalDate.now()))
                .contains(LocalDate.now().toString())
                .contains("路线");
    }

    /** 一个"空间够得着、带着这些投稿"的结果 —— 第一级桩件的夹具 */
    private static BilibiliRouteService.LatestVideos reachable(BilibiliRouteService.Video... videos) {
        return new BilibiliRouteService.LatestVideos(List.of(videos), true);
    }

    private static List<String> urls(List<Map<String, Object>> links) {
        return links.stream().map(link -> link.get("url").toString()).toList();
    }
}
