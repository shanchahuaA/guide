// pages/main/main.js
Page({
  data: {
    dragY: 0,       // 布的当前位移（px），正数=往下
    dragging: false
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
