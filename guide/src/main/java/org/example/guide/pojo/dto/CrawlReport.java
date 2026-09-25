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

    /** 警告,目前只有字典未知取值 */
    private List<String> warnings = new ArrayList<>();

    public void addFailure(String page, String nameEn, String reason) {
        failures.add(new CrawlFailure(page, nameEn, reason));
    }

    public void addWarning(String warning) {
        warnings.add(warning);
    }
}
