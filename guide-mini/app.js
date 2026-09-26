// app.js
const api = require('./utils/api')

const LOGIN_URL = 'http://localhost:8080/api/auth/login'

App({
  onLaunch() {
    this.login()

    // 启动即拉全量数据；页面里用 getApp().ready.then(data => ...) 等待
    this.ready = api.fetchAll().then(data => {
      Object.assign(this.globalData, data)
      return data
    }).catch(err => {
      console.error('[api] 启动拉取失败', err)
      this.globalData.error = err.message
      throw err
    })
  },

  // 只在冷启动时跑一次。wx.login 给的是 js_code，后端带 appid + secret 调
  // code2Session 换 openid，再回一个占位 token（值就是 openid）。两个都写进 storage：
  // 教学端点靠请求头 token 认人，详情页等以后也可能要看 openid。
  login() {
    wx.login({
      success: ({ code }) => wx.request({
        url: LOGIN_URL,
        method: 'POST',
        data: { js_code: code },
        success: ({ data: res }) => {
          if (!res || !res.success || !res.data) return
          this.globalData.openid = res.data.openid
          wx.setStorageSync('openid', res.data.openid)
          wx.setStorageSync('token', res.data.token)
        },
        // 登录失败不弹 toast 打扰用户：身份条拿不到 profile 时自己会退化成"未登录"
        fail: () => {}
      })
    })
  },

  globalData: { openid: null, items: [], tags: [], biomes: [], error: null }
})
