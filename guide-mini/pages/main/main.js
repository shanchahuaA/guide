// pages/main/main.js
Page({
  data: {
    dragY: 0,       // 布的当前位移（px），正数=往下
    dragging: false,
    // 官方外链。小程序不能直接打开任意外部网址（要「业务域名」白名单，那要求域名归自己
    // 且能放校验文件），所以点一下是复制链接，由用户自己去浏览器打开
    links: [
      { text: 'Official PEAK website', icon: '/images/official_website.jpg', url: 'https://peakpeakpeak.com/' },
      { text: 'Official PEAK Discord', icon: '/images/Discord.png', url: 'https://discord.gg/peakgame' },
      { text: 'PEAK on Steam', icon: '/images/Steam.png', url: 'https://s.team/a/3527290' }
    ]
  },

  // 与其它 tab 页同一套：每次显示都告诉 tabBar 高亮自己。
  // 这里必须调 —— custom-tab-bar 的 selected 初值是 -1、页面隐藏时又归零，
  // 不调的话停在主页时任何 tab 都不高亮
  onShow() {
    this.getTabBar().select('/pages/main/main')
  },

  onTapLink(e) {
    const { url } = e.currentTarget.dataset
    wx.setClipboardData({
      data: url,
      success: () => wx.showToast({ title: '链接已复制', icon: 'none' })
    })
  },

  onDragStart(e) {
    this.startY = e.touches[0].clientY
    this.startDragY = this.data.dragY
    this.measureLimit()
    this.setData({ dragging: true })
  },

  // 下拉上限 = 布原始位置到图片下沿的距离，也就是这条布完全退出图片、下沿贴住图底为止。
  // 现场量而不是写死：图片高度、屏幕高度都随机型变，写死的数换个手机就错
  measureLimit() {
    const q = wx.createSelectorQuery().in(this)
    q.select('.sheet').boundingClientRect()
    q.select('.hero-img').boundingClientRect()
    q.exec(([sheet, hero]) => {
      if (!sheet || !hero) return
      this.maxDragY = Math.max(0, Math.round(hero.bottom - sheet.top))
    })
  },

  onDragMove(e) {
    if (!this.data.dragging) return
    const delta = e.touches[0].clientY - this.startY
    let y = this.startDragY + delta
    // 只允许往下拉，且不越过图片下沿；上限还没量出来时按 0 处理（等于拖不动）
    if (y < 0) y = 0
    const max = this.maxDragY || 0
    if (y > max) y = max
    this.setData({ dragY: y })
  },

  // 松手：去掉 dragging 类，transition 会把布弹回原位（dragY 归 0）
  onDragEnd() {
    this.setData({ dragging: false, dragY: 0 })
  }
})
