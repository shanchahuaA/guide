// pages/teach/teach.js
const api = require('../../utils/api')

// 跳级后门：连点三次算升一级（契约 §7.6），计数到 3 才发请求
const TAPS_PER_LEVEL = 3

Page({

  data: {
    profile: null,      // /api/teach/profile 的 data，null = 还没拿到（未登录 / 后端没起）
    taps: 0             // 跳级按钮已点击数，显示成"跳级 x/3"
  },

  onShow() {
    this.getTabBar().select('/pages/teach/teach')
    this.loadProfile()
  },

  // 身份条的数据源。没登录时后端返 code=401，api.request 走 reject 分支，
  // 这里把 profile 归 null —— 页面退化成"未登录"，不弹窗
  loadProfile() {
    const token = wx.getStorageSync('token')
    if (!token) {
      this.setData({ profile: null })
      return
    }
    api.request('/api/teach/profile', { header: { token } })
      .then(profile => this.setData({ profile }))
      .catch(() => this.setData({ profile: null }))
  },

  // 演示后门：连点三次升一级。第三次才打后端，成功后计数归零并提示
  onTapJump() {
    const taps = this.data.taps + 1
    if (taps < TAPS_PER_LEVEL) {
      this.setData({ taps })
      return
    }
    this.setData({ taps: 0 })
    api.request('/api/teach/dev/level', {
      method: 'POST',
      header: { token: wx.getStorageSync('token') }
    }).then(() => {
      wx.showToast({ title: '成功跳级', icon: 'none' })
      this.loadProfile()
    }).catch(err => {
      wx.showToast({ title: err.message || '跳级失败', icon: 'none' })
    })
  }
})
