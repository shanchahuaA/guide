package org.example.guide.crawler;

import org.example.guide.pojo.dto.CrawlFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 图标本地化:批量查直链 → 逐张下载到本地 icons/ 目录。
 *
 * 直链走 {@code api.php} 的 imageinfo,不走 {@code Special:FilePath} —— 后者被 Cloudflare 拦(403)。
 * 下完的文件名规则见 {@link IconFileNames},落盘位置见 {@link IconStorage}。
 *
 * <p>失败粒度是**单个文件**:某张下不动只记进报告、对应条目 icon 留空,
 * 既不中断整批采集,也不回滚旁边已经下好的那些。整个采集里只有"图标目录建不出来"
 * 才会整体失败 —— 那确实一张也存不下,装成 134 条单文件失败只会让报告更难读。
 */
@Component
public class ItemIconDownloader {

    private static final Logger log = LoggerFactory.getLogger(ItemIconDownloader.class);

    private final WikiApiClient wikiApiClient;
    private final IconStorage iconStorage;
    private final RestClient downloadClient;

    public ItemIconDownloader(WikiApiClient wikiApiClient, IconStorage iconStorage) {
        this.wikiApiClient = wikiApiClient;
        this.iconStorage = iconStorage;
        this.downloadClient = RestClient.builder()
                // 图片直链可能落在另一个主机上,但挡在前面的是同一家 Cloudflare、认的是 UA,
                // 所以沿用 api.php 那条通道的 UA 与超时
                .defaultHeader(HttpHeaders.USER_AGENT, WikiApiClient.USER_AGENT)
                .requestFactory(WikiApiClient.requestFactory())
                .build();
    }

    /**
     * 把这一批条目的图标全部落到本地 icons/ 目录。
     *
     * @param rows 采集到的数据源行;文件名由 display 推出
     * @return 条目英文名 → icon 列的相对路径(只有下成功的那些)+ 失败明细
     * @throws UncheckedIOException 图标目录建不出来时抛出,由调用方记进报告
     */
    public Result downloadAll(List<CargoItemRow> rows) {
        Map<String, CargoItemRow> byDisplay = distinctByDisplay(rows);
        List<CrawlFailure> failures = new ArrayList<>();
        if (byDisplay.isEmpty()) {
            return new Result(Map.of(), failures);
        }

        try {
            Files.createDirectories(iconStorage.directory());
        } catch (IOException e) {
            throw new UncheckedIOException("图标目录不可写:" + iconStorage.directory(), e);
        }

        // 直链是批量的(50 个标题一包),下载是一张一次的,所以先把直链整批查完再逐张下
        Map<String, String> urlByDisplay = resolveDirectUrls(byDisplay, failures);

        Map<String, String> pathByDisplay = new LinkedHashMap<>();
        for (Map.Entry<String, CargoItemRow> entry : byDisplay.entrySet()) {
            String display = entry.getKey();
            String url = urlByDisplay.get(display);
            if (url == null) {
                continue;   // 查直链那一步已经把这一个记进失败了
            }
            try {
                store(download(url), display);
                pathByDisplay.put(display, iconStorage.urlFor(IconFileNames.fileName(display)));
            } catch (Exception e) {
                log.warn("图标下载失败,已跳过:display={} url={}", display, url, e);
                failures.add(failure(entry.getValue(), "下载失败:" + failureReason(e)));
            }
        }

        log.info("图标:目标 {} 张,成功 {} 张,失败 {} 张", byDisplay.size(), pathByDisplay.size(), failures.size());
        return new Result(pathByDisplay, failures);
    }

    /** 同一个 display 出现在多行时只下一次(数据源里 display 是唯一的,这里只是不让重复请求) */
    private static Map<String, CargoItemRow> distinctByDisplay(List<CargoItemRow> rows) {
        Map<String, CargoItemRow> byDisplay = new LinkedHashMap<>();
        for (CargoItemRow row : rows) {
            if (row.getDisplay() != null && !row.getDisplay().isBlank()) {
                byDisplay.putIfAbsent(row.getDisplay(), row);
            }
        }
        return byDisplay;
    }

    /**
     * 分批查直链。
     *
     * 一包的 HTTP 失败只影响那一包里的文件(逐个记进报告),其余包照常查 ——
     * 数据源抖一下不该让整批图标全空。
     */
    private Map<String, String> resolveDirectUrls(Map<String, CargoItemRow> byDisplay, List<CrawlFailure> failures) {
        List<String> displays = new ArrayList<>(byDisplay.keySet());
        Map<String, String> urlByDisplay = new LinkedHashMap<>();

        for (int from = 0; from < displays.size(); from += WikiApiClient.MAX_TITLES_PER_REQUEST) {
            int to = Math.min(from + WikiApiClient.MAX_TITLES_PER_REQUEST, displays.size());
            List<String> chunk = displays.subList(from, to);
            List<String> titles = chunk.stream().map(IconFileNames::wikiTitle).toList();

            Map<String, String> urlByTitle;
            try {
                urlByTitle = withRetry("查询图标直链", () -> wikiApiClient.fetchImageUrls(titles));
            } catch (Exception e) {
                log.warn("图标直链查询失败,本包 {} 个标题跳过:{}", titles.size(), e.toString());
                chunk.forEach(display -> failures.add(failure(byDisplay.get(display), "查询直链失败:" + failureReason(e))));
                continue;
            }

            chunk.forEach(display -> {
                String url = urlByTitle.get(IconFileNames.wikiTitle(display));
                if (url == null) {
                    failures.add(failure(byDisplay.get(display), "数据源上没有这个文件:" + IconFileNames.wikiTitle(display)));
                } else {
                    urlByDisplay.put(display, url);
                }
            });
        }
        return urlByDisplay;
    }

    /** 下一次;失败由调用方记报告,这里只负责把 HTTP 响应变成字节 */
    private byte[] download(String url) {
        return withRetry("下载图标 " + url, () -> {
            byte[] bytes = downloadClient.get()
                    .uri(URI.create(url))
                    .retrieve()
                    .body(byte[].class);
            if (bytes == null || bytes.length == 0) {
                // 200 也可能是空体(被拦下时对方偶尔就这么回),不能当成下好了往磁盘上写
                throw new IllegalStateException("响应体为空");
            }
            return bytes;
        });
    }

    /**
     * 写盘:先落临时文件再改名。
     * 中途失败不会在 icons/ 里留下半张 PNG —— 那种文件会被静态映射当成成品发出去。
     */
    private void store(byte[] bytes, String display) throws IOException {
        String fileName = IconFileNames.fileName(display);
        if (IconFileNames.needsRewrite(display)) {
            log.info("图标文件名含 URL / Windows 文件名不安全字符,已改写:{} → {}", display, fileName);
        }

        Path temp = Files.createTempFile(iconStorage.directory(), "icon-", ".part");
        try {
            Files.write(temp, bytes);
            Files.move(temp, iconStorage.directory().resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * 重试一次。
     *
     * 数据源在境外,单次失败多是抖动而不是真没有;采集本身可以重复触发,
     * 所以兜底只做一层重试,失败的那些照样进报告,不在这儿硬扛。
     */
    private static <T> T withRetry(String what, Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException first) {
            log.warn("{} 第一次失败,重试一次:{}", what, first.toString());
            try {
                return call.get();
            } catch (RuntimeException second) {
                second.addSuppressed(first);
                throw second;
            }
        }
    }

    private static CrawlFailure failure(CargoItemRow row, String reason) {
        return new CrawlFailure(row.getPage(), row.getDisplay(), reason);
    }

    /** 异常没有 message 时退回类名,报告里不留空白 */
    private static String failureReason(Exception e) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? e.getClass().getSimpleName() : message;
    }

    /**
     * 一次图标本地化的结果。
     *
     * @param pathByDisplay 条目英文名 → icon 列的相对路径;只有下成功的那些在里面
     * @param failures 失败明细,原样进采集报告
     */
    public record Result(Map<String, String> pathByDisplay, List<CrawlFailure> failures) {
    }
}
