package org.example.guide.pojo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 采集报告里的一条失败明细。
 *
 * 单条失败只跳过这一条,不回滚整批,所以要能定位到具体是哪一行。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CrawlFailure {

    /** 数据源页面名,用来定位是哪一行 */
    private String page;

    /** 条目英文名(数据源 display);连它都拿不到时为 null */
    private String nameEn;

    /** 失败原因 */
    private String reason;
}
