package org.example.guide.service.impl;

import org.example.guide.crawler.CargoItemRow;
import org.example.guide.crawler.ConvertedItem;
import org.example.guide.crawler.ItemConverter;
import org.example.guide.crawler.ItemIconDownloader;
import org.example.guide.crawler.ItemPageParser;
import org.example.guide.crawler.WikiApiClient;
import org.example.guide.crawler.WikitextParams;
import org.example.guide.pojo.dto.CrawlReport;
import org.example.guide.service.ICrawlerService;
import org.example.guide.service.IItemService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采集编排:拉取 → 页面源文 → 图标本地化 → 逐条转换 → 逐条 upsert → 汇总报告 → 缓存失效。
 */
@Service
public class CrawlerServiceImpl implements ICrawlerService {

    private static final Logger log = LoggerFactory.getLogger(CrawlerServiceImpl.class);

    private final WikiApiClient wikiApiClient;
    private final ItemPageParser itemPageParser;
    private final ItemConverter itemConverter;
    private final IItemService itemService;
    private final ItemIconDownloader iconDownloader;

    public CrawlerServiceImpl(WikiApiClient wikiApiClient,
                              ItemPageParser itemPageParser,
                              ItemConverter itemConverter,
                              IItemService itemService,
                              ItemIconDownloader iconDownloader) {
        this.wikiApiClient = wikiApiClient;
        this.itemPageParser = itemPageParser;
        this.itemConverter = itemConverter;
        this.itemService = itemService;
        this.iconDownloader = iconDownloader;
    }

    /**
     * 刻意**不加事务**:本方法是"逐条独立提交"的语义。
     * 一旦套上事务,被 catch 掉的那条异常会把事务标记成 rollback-only,
     * 结果是单条失败反倒把整批都回滚掉 —— 与"单条失败跳过"的要求正好相反。
     */
    @Override
    public CrawlReport crawlAll() {
        CrawlReport report = new CrawlReport();

        List<CargoItemRow> rows;
        try {
            rows = wikiApiClient.fetchAllItems();
        } catch (Exception e) {
            // 拉取阶段的 HTTP / 解析失败也落进报告,不冒成 500:
            // 数据源被拦时报告依然可读(拉取行数为 0 + 失败原因),不用靠猜
            log.error("采集拉取阶段失败", e);
            report.setFetchError(failureReason(e));
            return report;
        }

        report.setFetchedRows(rows.size());
        if (rows.isEmpty()) {
            report.addWarning("数据源返回 0 行,请确认接口与字段清单是否仍然有效");
        }

        // 页面源文必须在转换之前到手:熟食覆盖值与可烹饪判据只存在于源文里(见 #14)
        Map<String, String> wikitextByPage = fetchWikitext(rows, report);

        // 图标先整批下好再逐条填:下载只依赖 display,不依赖转换结果
        Map<String, String> iconPathByDisplay = downloadIcons(rows, report);

        Map<String, Integer> unknownDictionaryValues = new LinkedHashMap<>();
        for (CargoItemRow row : rows) {
            importRow(row, report, unknownDictionaryValues, wikitextByPage, iconPathByDisplay);
        }
        unknownDictionaryValues.forEach((value, count) -> report.addWarning(
                "字典未知取值 " + value + "(出现 " + count + " 条),已保留原值、中文名留空"));

        evictItemCache();
        return report;
    }

    /**
     * 批量拉页面源文。
     *
     * 整体失败(HTTP / 解析)只记进报告,**不中断采集**:源文少了只会让熟食覆盖值退回模板公式,
     * 而结构化那半边数据是完全独立的、丢不起。所以这里与图标本地化同样"降级不放弃"。
     *
     * @return 页面名 → 页面源文;这次没取到源文的页面不出现在结果里
     */
    private Map<String, String> fetchWikitext(List<CargoItemRow> rows, CrawlReport report) {
        // 一个页面可能对应多行(3 个毒蘑菇变体),标题去重后再打包,避免用同一页占满一个包
        List<String> pageNames = rows.stream()
                .map(CargoItemRow::getPage)
                .filter(page -> page != null && !page.isBlank())
                .distinct()
                .toList();
        try {
            Map<String, String> wikitextByPage = wikiApiClient.fetchPageWikitext(pageNames);
            if (wikitextByPage.size() < pageNames.size()) {
                report.addWarning("有 " + (pageNames.size() - wikitextByPage.size())
                        + " 个页面没取到源文,这些条目的熟食值按公式算、不判可烹饪");
            }
            return wikitextByPage;
        } catch (Exception e) {
            log.error("采集页面源文阶段失败,熟食值退回公式计算", e);
            report.setWikitextError(failureReason(e));
            report.addWarning("页面源文没取到,本次采集的熟食覆盖值全部按公式算");
            return Map.of();
        }
    }

    /**
     * 图标本地化。
     *
     * 失败只记进报告、对应条目 icon 留空,不停下整批采集 —— 图标是展示层的东西,
     * 不该因为它把 130 多条数据挡在门外。
     *
     * @return 条目英文名 → icon 列的相对路径(只有下成功的那些)
     */
    private Map<String, String> downloadIcons(List<CargoItemRow> rows, CrawlReport report) {
        try {
            ItemIconDownloader.Result result = iconDownloader.downloadAll(rows);
            report.setIconSuccessCount(result.pathByDisplay().size());
            result.failures().forEach(failure -> report.addIconFailure(
                    failure.getPage(), failure.getNameEn(), failure.getReason()));
            return result.pathByDisplay();
        } catch (Exception e) {
            // 整体性故障(例如图标目录建不出来)也进报告,采集继续走完
            log.error("图标本地化整体失败", e);
            report.addWarning("图标本地化整体失败:" + failureReason(e));
            return Map.of();
        }
    }

    /**
     * 落库一行:解析源文 → 转换 → 填图标 → upsert。
     * 失败只跳过这一条并记进报告,库中已有的条目不动(只 upsert 不反删)。
     */
    private void importRow(CargoItemRow row, CrawlReport report,
                           Map<String, Integer> unknownDictionaryValues,
                           Map<String, String> wikitextByPage,
                           Map<String, String> iconPathByDisplay) {
        String page = row.getPage();
        String nameEn = row.getDisplay();
        try {
            // 源文没抓到、或页面上没有 Infobox 时走 EMPTY:熟食值按公式算,不判可烹饪
            WikitextParams params = wikitextByPage.containsKey(page)
                    ? itemPageParser.paramsFor(wikitextByPage.get(page), nameEn)
                    : WikitextParams.EMPTY;

            ConvertedItem converted = itemConverter.convert(row, params);
            converted.unknownDictionaryValues()
                    .forEach(value -> unknownDictionaryValues.merge(value, 1, Integer::sum));
            // 图标按 display 对应;这张图没下成功就是 null,条目照常落库(icon 留空)
            converted.item().setIcon(iconPathByDisplay.get(nameEn));

            if (itemService.batchImportItems(List.of(converted.item()))) {
                report.setSuccessCount(report.getSuccessCount() + 1);
            } else {
                report.addFailure(page, nameEn, "落库返回失败");
            }
        } catch (Exception e) {
            log.warn("采集单条失败,已跳过:page={} display={}", page, nameEn, e);
            report.addFailure(page, nameEn, failureReason(e));
        }
    }

    /**
     * 缓存失效占位:缓存尚未接入(见 issue #4),接入后在这里删掉图鉴的全量缓存。
     * 现在至少留一条日志痕迹,证明采集确实走到了这一步。
     */
    private void evictItemCache() {
        // TODO issue #4 接入缓存后:删除 guide:item: 系列 key
        log.info("缓存失效占位:缓存尚未接入,本次不做任何事");
    }

    private static String failureReason(Exception e) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? e.getClass().getSimpleName() : message;
    }
}
