// pages/archive/archive.js
const api = require('../../utils/api')

// 一级导航：CONTEXT.md 主类型节 8 取值，敌人（ENEMY）并入杂物（MISC）
// value 是主类型数组；筛选在小程序本地做 —— /api/items 不接受任何参数，一次返全量（接口契约.md §0.5）
const PRIMARY_TYPES = [
  { label: '全部', value: [] },
  { label: '食物', value: ['FOOD'] },
  { label: '消耗品', value: ['CONSUMABLE'] },
  { label: '装备', value: ['EQUIPMENT'] },
  { label: '可放置', value: ['DEPLOYABLE'] },
  { label: '护身符', value: ['AMULET'] },
  { label: '神秘', value: ['MYSTICAL'] },
  { label: '杂物', value: ['MISC', 'ENEMY'] }
]

Page({

  /**
   * 全部条目（接口返回的原始列表），只在本地过滤用；不进 data，免得 134 条
   * 每次都往视图层序列化一遍
   */
  allItems: [],

  /**
   * 页面的初始数据
   */
  data: {
    keyword: '',
    primaryTypes: PRIMARY_TYPES,
    activeTypeIndex: 0,
    items: [],
    loading: true,
    error: ''
  },

  onKeywordInput(e) {
    // 边打边筛：bindinput 每敲一个字都会触发，不用等回车。
    // onSearch（bindconfirm）保留，回车/点搜索键时再筛一次，行为一致
    this.setData({ keyword: e.detail.value })
    this.applyFilter()
  },

  onSearch() {
    this.applyFilter()
  },

  onClearKeyword() {
    this.setData({ keyword: '' })
    this.applyFilter()
  },

  onSelectType(e) {
    this.setData({ activeTypeIndex: Number(e.currentTarget.dataset.index) })
    this.applyFilter()
  },

  // 当前选中的主类型数组，用于在小程序本地过滤 app.ready 拿到的 items
  // （接口不接受筛选参数，primaryType 只出现在返回里，不是入参）
  getActiveTypes() {
    return PRIMARY_TYPES[this.data.activeTypeIndex].value
  },

  // 关键词与主类型都只在本地过滤。次序沿用接口返回的「按主类型分组、组内按 id 升序」，
  // 不重排 —— 重排就得在前端再抄一份归约规则
  applyFilter() {
    const types = this.getActiveTypes()
    const keyword = this.data.keyword.trim().toLowerCase()
    const items = this.allItems.filter(item => {
      if (types.length && types.indexOf(item.primaryType) === -1) return false
      if (!keyword) return true
      return (item.nameZh || '').toLowerCase().indexOf(keyword) !== -1 ||
        (item.nameEn || '').toLowerCase().indexOf(keyword) !== -1
    }).map(item => ({
      slug: item.slug,
      nameZh: item.nameZh,
      // 接口给的是 /icons/xxx.png 相对路径，得补上 origin 才能加载
      icon: api.assetUrl(item.icon)
    }))
    this.setData({ items })
  },

  // 点格子进详情页。路径参数是 slug（接口契约.md §2），不是 id。
  // nameZh 一并带上：详情拉取失败时页面标题用它兜底，不然「没有这条」会是空标题
  onTapItem(e) {
    const item = this.data.items[Number(e.currentTarget.dataset.index)]
    wx.navigateTo({
      url: '/pages/detail/detail?slug=' + item.slug + '&nameZh=' + encodeURIComponent(item.nameZh || '')
    })
  },

  /**
   * 生命周期函数--监听页面加载
   */
  onLoad(options) {
    // app.js 冷启动时就把图鉴全量 + 字典拉好了，这里只等它，不再自己发一次请求
    const app = getApp()
    app.ready.then(() => {
      this.allItems = app.globalData.items || []
      this.applyFilter()
      this.setData({ loading: false })
    }).catch(err => {
      console.error('[archive] 图鉴拉取失败', err)
      this.setData({ loading: false, error: err.message || '图鉴加载失败' })
    })
  },

  /**
   * 生命周期函数--监听页面初次渲染完成
   */
  onReady() {

  },

  /**
   * 生命周期函数--监听页面显示
   */
  onShow() {
    this.getTabBar().select('/pages/archive/archive')
  },

  /**
   * 生命周期函数--监听页面隐藏
   */
  onHide() {

  },

  /**
   * 生命周期函数--监听页面卸载
   */
  onUnload() {

  },

  /**
   * 页面相关事件处理函数--监听用户下拉动作
   */
  onPullDownRefresh() {

  },

  /**
   * 页面上拉触底事件的处理函数
   */
  onReachBottom() {

  },

  /**
   * 用户点击右上角分享
   */
  onShareAppMessage() {

  }
})
