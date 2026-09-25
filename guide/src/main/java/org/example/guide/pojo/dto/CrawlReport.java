package org.example.guide.pojo.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 采集报告:一次采集跑完给管理员看的东西。
 *
 * 结构固定,不让采集内部的异常直接冒成 500 —— 报告本身在数据源出问题时也要可读。
 */
@Data
public class CrawlReport {

    /** 从数据源拉到的行数;拉取阶段就失败时为 0,原因见 fetchError */
    private int fetchedRows;

    /** 拉取阶段(HTTP / 响应解析)的失败原因;正常拉到数据时保持 null */
    private String fetchError;

    /** 落库成功的条数 */
    private int successCount;

    /** 单条失败明细;失败的那条被跳过,库里原有的条目不受影响(不反删) */
    private List<CrawlFailure> failures = new ArrayList<>();

    /**
     * 页面源文(wikitext)整体不可得时的原因;取到源文时保持 null。
     *
     * 与 fetchError 是两种不同性质的失败,分开报:
     * 源文只影响熟食覆盖值/可烹饪判据,拉取全挂却是"这次采集什么都没成"。
     * 源文取不到时熟食值退回模板公式,采集照常跑完。
     */
    private String wikitextError;

    /** 图标下载成功的张数 */
    private int iconSuccessCount;

    /** 图标失败明细;失败的那张图对应的条目 icon 留空,条目本身照常落库 */
    private List<CrawlFailure> iconFailures = new ArrayList<>();

    /** 警告,目前只有字典未知取值 */
    private List<String> warnings = new ArrayList<>();

    public void addFailure(String page, String nameEn, String reason) {
        failures.add(new CrawlFailure(page, nameEn, reason));
    }

    public void addIconFailure(String page, String nameEn, String reason) {
        iconFailures.add(new CrawlFailure(page, nameEn, reason));
    }

    public void addWarning(String warning) {
        warnings.add(warning);
    }
}
