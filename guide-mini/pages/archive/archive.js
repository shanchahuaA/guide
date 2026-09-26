// pages/archive/archive.js

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

// 占位数据：只为调排版。接上 /api/items 后整块删掉，字段与契约一致（icon 是相对路径）
const PLACEHOLDER_ITEMS = [
  { id: 1, nameZh: '蓝蕉莓皮', icon: '' },
  { id: 2, nameZh: '棕莓蕉皮', icon: '' },
  { id: 3, nameZh: '粉蕉莓皮', icon: '' },
  { id: 4, nameZh: '黄蕉莓皮', icon: '' },
  { id: 5, nameZh: '仙人球', icon: '' },
  { id: 6, nameZh: '卷轴', icon: '' },
  { id: 7, nameZh: '好大蛋', icon: '' },
  { id: 8, nameZh: '宾邦', icon: '' },
  { id: 9, nameZh: '小小蛋', icon: '' },
  { id: 10, nameZh: '护照', icon: '' },
  { id: 11, nameZh: '椰子', icon: '' },
  { id: 12, nameZh: '海螺', icon: '' }
]

Page({

  /**
   * 页面的初始数据
   */
  data: {
    keyword: '',
    primaryTypes: PRIMARY_TYPES,
    activeTypeIndex: 0,
    items: PLACEHOLDER_ITEMS
  },

  onKeywordInput(e) {
    this.setData({ keyword: e.detail.value })
  },

  onSearch() {
    // 待接：用 keyword 过滤 app.ready 拿到的 items
  },

  onClearKeyword() {
    this.setData({ keyword: '' })
  },

  onSelectType(e) {
    this.setData({ activeTypeIndex: Number(e.currentTarget.dataset.index) })
  },

  // 当前选中的主类型数组，用于在小程序本地过滤 app.ready 拿到的 items
  // （接口不接受筛选参数，primaryType 只出现在返回里，不是入参）
  getActiveTypes() {
    return PRIMARY_TYPES[this.data.activeTypeIndex].value
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