package org.example.guide.crawler;

/**
 * 从物品页面源文(wikitext)里提取出来的、Cargo 表里查不到的那几个参数。
 *
 * 熟食数值在数据源里**不是存储字段** —— 模板 Infobox item 渲染时才按公式现算,
 * 少部分页面用模板参数显式覆盖。所以熟食覆盖值只能靠解析页面源文拿到,
 * 这也是本票要建批量 wikitext 管道的原因。
 *
 * 覆盖值很少见(实测 HungerCooked 只有 4 个页面用、BonusCooked 只有 15 个),
 * 绝大多数条目这里会是 {@link #EMPTY},熟食值照公式算。
 *
 * @param hungerCooked    HungerCooked 覆盖值;页面没写时为 null,熟食饱食退回"生值×2"
 * @param bonusCooked     BonusCooked 覆盖值;页面没写时为 null,熟食加成退回"生值×1.5,无生值时 10"
 * @param hasCookingBonus HasCookingBonus 的开关值(yes/no/breaks,已归一成小写);页面没写时为 null(模板默认 yes)
 * @param cookingNotes    烹饪说明原文(带 wikitext 标记);本票不消费它,由 #15 写进 description
 */
public record WikitextParams(
        Float hungerCooked,
        Float bonusCooked,
        String hasCookingBonus,
        String cookingNotes) {

    /** 页面源文不可得(没抓到、或页面上没有 Infobox item)时的兜底:全部按公式算 */
    public static final WikitextParams EMPTY = new WikitextParams(null, null, null, null);

    /**
     * 这个条目要不要生成熟食饱食/加成。
     *
     * 数据源的模板里 no 与 breaks 是同一个意思:烹饪不给饱食/加成(表现为熟食值不生成)。
     * 实测有 62 个页面用了这个开关,基本都是"熟了没什么可看"的东西。
     *
     * 注意判否有**两**条独立的路径,别把它们混为一谈:
     * 道具(First Aid Kit / Dynamite)是先被"仅食物参与判定"挡在外面的(见 CookedEffects),
     * 与本开关无关;而带 Food 标签的 Fortified Milk 才需要这个开关来挡。
     * 所以本方法**不是**那三个反例的唯一依据。
     */
    public boolean suppressesCookingBonus() {
        return "no".equalsIgnoreCase(hasCookingBonus) || "breaks".equalsIgnoreCase(hasCookingBonus);
    }
}
