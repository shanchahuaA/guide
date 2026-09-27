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
          if (!res || !res.success || !res.data) {
            // 失败**不再静默**：把后端给的中文原因记下来（存 globalData + storage），
            // 个人页/身份条据此显示"为什么没登录"，而不是空白一片
            this.failLogin((res && res.message) || '登录失败：后端没返回 token')
            return
          }
          this.globalData.openid = res.data.openid
          this.globalData.loginError = null
          wx.removeStorageSync('loginError')
          wx.setStorageSync('openid', res.data.openid)
          wx.setStorageSync('token', res.data.token)
        },
        // 请求根本没发出去（后端没起 / 没勾"不校验合法域名"）也要留痕，别让页面无从解释
        fail: err => this.failLogin((err && err.errMsg) || '登录请求没发出去')
      })
    })
  },

  failLogin(message) {
    this.globalData.loginError = message
    wx.setStorageSync('loginError', message)
    console.warn('[login]', message)
  },

  // 个人页的"重试登录"按钮走这里
  retryLogin() {
    this.login()
  },

  globalData: { openid: null, items: [], tags: [], biomes: [], error: null, loginError: null }
})
