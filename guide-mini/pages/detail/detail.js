// pages/detail/detail.js
const api = require('../../utils/api')

// 效果中文名、生熟拆分、_COOKED 剥后缀全部由服务端做（接口契约.md §2.1、§8.1）。
// 这块原先有一份本地 EFFECT_ZH 与剥后缀逻辑，注释自己写着「归属后端：接口应该像
// ItemTag 那样带 nameZh，届时这块整删」—— 现在服务端带 nameZh 了，按约整删。

// 详情接口的字段名与列表不同（tags 不是 tag、descriptionZh 不是 description、
// 生熟是拆好的两个数组）。接口契约.md §2 是唯一定义，这里只做「接口字段 → 视图字段」的映射。
//
// wxml 里写不了逻辑，所以派生字段（chip、展示用的中文串）在这一步算好。

// 潘多拉魔盒是特殊道具：它的效果数值在游戏里是随机的，数据源拿 0 占位。
// 照 0 显示会被读成「零效果」，所以只对这一个道具把占位的 0 显示成 ?。
// 用 slug 认（契约 §0.3：slug 是条目的对外标识）；使用次数 3 是真实值，不受影响。
const RANDOM_VALUE_SLUG = "pandora's_lunchbox"

function buildView(item) {
  const tags = item.tags || []
  const tagsOf = code => tags.filter(t => t.code === code).map(t => t.nameZh || t.value)
  const randomValues = item.slug === RANDOM_VALUE_SLUG

  // raw / cooked 的元素已经带 nameZh（服务端效果字典），前端不再查表、不再剥后缀。
  // 直接把 nameZh 当显示文本：字典没收录的 code 服务端会留空，这时回落到 code 本身，
  // 免得那一行只剩下一个数值、看不出是什么效果
  const toRow = fx => ({
    code: fx.code,
    label: fx.nameZh || fx.code,
    // '?' 是字符串，wxml 里 `fx.value > 0 ? '+' : ''` 对它为假，所以不会多出 '+'；
    // 类名仍落 pos（橙 = 施加该状态），与这些状态确实都会被施加相符
    value: randomValues && fx.value === 0 ? '?' : fx.value
  })

  return Object.assign({}, item, {
    // 接口下发的是相对路径 /icons/xxx.png，小程序必须补上 origin 才能加载
    icon: api.assetUrl(item.icon),
    rawEffects: (item.raw || []).map(toRow),
    cookedEffects: (item.cooked || []).map(toRow),
    chips: tagsOf('type').concat(tagsOf('rarity')),
    rarity: tagsOf('rarity').join(' / '),
    biomes: tagsOf('biome').join(' / '),
    sources: tagsOf('source').join(' / ')
  })
}

Page({
  data: {
    item: null,
    notFound: false
  },

  onLoad(options) {
    // 路径参数是 slug（接口契约.md §2），不是 id
    const slug = options.slug
    if (!slug) {
      this.setData({ notFound: true })
      wx.showToast({ title: '缺少条目参数', icon: 'none' })
      return
    }

    api.request('/api/items/' + slug).then(item => {
      const view = buildView(item)
      this.setData({ item: view })
      wx.setNavigationBarTitle({ title: view.nameZh || view.nameEn })
    }).catch(err => {
      // slug 不存在时后端返 code=-100（契约 §2.3），request 会 reject 到这里。
      // 页面标题用列表传过来的名字兜底，不然「没有这条」会显示成空标题
      console.error('[detail] 拉取失败', slug, err)
      this.setData({ notFound: true })
      if (options.nameZh) wx.setNavigationBarTitle({ title: options.nameZh })
      wx.showToast({ title: err.message || '条目不存在', icon: 'none' })
    })
  }
})
