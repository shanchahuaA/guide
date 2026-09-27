// pages/profile/profile.js
const api = require('../../utils/api')

// 跳级后门：连点三次算升一级（契约 §7.6），与 teach 页同一套口径
const TAPS_PER_LEVEL = 3

// 跳级三段确认：每点一次都要用户点头，第三次也确认了才真的发请求
const JUMP_CONFIRMS = [
  '你确定要跳级吗？',
  '你的实力真的已经达标了吗？',
  '最后确认一次，你要跳级吗？'
]

// 后端给的 avatar 是相对路径（/avatars/xxx.png），小程序 <image> 必须带 origin 才加载得出来。
// 这里自带一份而不去用 utils/api.js 的 assetUrl —— 那个文件上还有未提交的改动，避免缠在一起
function absoluteUrl(path) {
  return path ? api.BASE_URL + path : ''
}

// 上传头像。wx.uploadFile 不会解析 JSON，res.data 是字符串，要自己 parse。
// 后端存盘后回 { url: '/avatars/xxx.png' }，这个 url 才是能落库、长期有效的地址
// （chooseAvatar 给的是临时路径，进程一退就废）
function uploadAvatar(filePath, token) {
  return new Promise((resolve, reject) => {
    wx.uploadFile({
      url: api.BASE_URL + '/api/teach/avatar',
      filePath,
      name: 'file',
      header: { token },
      success: res => {
        let body = null
        try { body = JSON.parse(res.data) } catch (e) { /* 非 JSON 就当失败处理 */ }
        if (res.statusCode === 200 && body && body.success) {
          resolve((body.data && body.data.url) || '')
        } else {
          reject(new Error((body && body.message) || ('HTTP ' + res.statusCode)))
        }
      },
      fail: err => reject(new Error(err.errMsg))
    })
  })
}

Page({

  data: {
    // profile 为 null = 还没拿到（未登录 / 后端没起）。个人页据此整段换成"未登录"提示
    profile: null,
    loginError: null,
    taps: 0,
    // 头像昵称草稿：nicknameDraft 是输入中的昵称；avatarDraft 是**刚选的本地临时路径**（还没上传）；
    // avatarUrl 是用来显示的绝对地址（库里存的 http 地址，或刚选的临时路径）
    nicknameDraft: '',
    avatarDraft: '',
    avatarUrl: '',
    savingProfile: false,
    // API Key 草稿。填过也能再改，所以这里不按 hasApiKey 隐藏
    keyDraft: '',
    savingKey: false
  },

  onShow() {
    this.getTabBar().select('/pages/profile/profile')
    this.loadProfile()
  },

  // 身份 + 昵称头像 + 有没有 Key，一张 profile 全给
  loadProfile() {
    const token = wx.getStorageSync('token')
    if (!token) {
      this.setData({ profile: null, loginError: this.loginErrorText() })
      return
    }
    api.request('/api/teach/profile', { header: { token } })
      .then(profile => {
        this.setData({
          profile,
          loginError: null,
          nicknameDraft: profile.nickname || '',
          avatarUrl: absoluteUrl(profile.avatar)
        })
      })
      .catch(err => this.setData({ profile: null, loginError: err.message || '资料加载失败' }))
  },

  // 登录失败的原因在 app.js 里记着（不再静默吞掉）—— 没有就退化成一句通用提示
  loginErrorText() {
    const app = getApp()
    const fromApp = app && app.globalData && app.globalData.loginError
    return fromApp || wx.getStorageSync('loginError') || '未登录：后端没起，或 guide.wechat.appid / secret 没配'
  },

  // ── 头像 / 昵称（契约 §7.1 / §7.8）────────────────────────────────────

  onChooseAvatar(e) {
    // e.detail.avatarUrl 是本地临时路径，先拿来显示，保存时再上传换成正式 url
    const avatarUrl = e.detail.avatarUrl
    this.setData({ avatarDraft: avatarUrl, avatarUrl })
  },

  onNicknameInput(e) {
    this.setData({ nicknameDraft: e.detail.value })
  },

  // 先用头像换了正式 url（如果这次选了新头像），再一起落库。只改昵称时不传 avatar
  onSaveProfile() {
    const token = wx.getStorageSync('token')
    if (!token) {
      wx.showToast({ title: '请先登录', icon: 'none' })
      return
    }
    const nickname = this.data.nicknameDraft.trim()
    const avatarDraft = this.data.avatarDraft
    if (!nickname && !avatarDraft) {
      wx.showToast({ title: '先选个头像或填个昵称', icon: 'none' })
      return
    }
    if (this.data.savingProfile) {
      return
    }
    this.setData({ savingProfile: true })

    const upload = avatarDraft ? uploadAvatar(avatarDraft, token) : Promise.resolve('')
    upload
      .then(url => api.request('/api/teach/profile', {
        method: 'POST',
        header: { token },
        data: { nickname, avatar: url || '' }
      }))
      .then(() => {
        this.setData({ savingProfile: false, avatarDraft: '' })
        wx.showToast({ title: '已保存', icon: 'none' })
        this.loadProfile()
      })
      .catch(err => {
        this.setData({ savingProfile: false })
        wx.showToast({ title: err.message || '保存失败', icon: 'none' })
      })
  },

  // ── API Key（契约 §7.2）────────────────────────────────────────────────

  onKeyInput(e) {
    this.setData({ keyDraft: e.detail.value })
  },

  // 随时可改：填过之后仍然给输入框，换一次 Key 就重存一次
  onSaveKey() {
    const apiKey = this.data.keyDraft.trim()
    if (!apiKey) {
      wx.showToast({ title: '请先填入 Key', icon: 'none' })
      return
    }
    if (this.data.savingKey) {
      return
    }
    this.setData({ savingKey: true })
    api.request('/api/teach/apikey', {
      method: 'POST',
      header: { token: wx.getStorageSync('token') },
      data: { apiKey }
    }).then(() => {
      this.setData({ savingKey: false, keyDraft: '' })
      wx.showToast({ title: '已保存', icon: 'none' })
      this.loadProfile()
    }).catch(err => {
      this.setData({ savingKey: false })
      wx.showToast({ title: err.message || '保存失败', icon: 'none' })
    })
  },

  // ── 重置进度（契约 §7.3）────────────────────────────────────────────────

  // 连对、排除集、等级**一起**归零（高手也退回菜鸟），动静不小，所以先弹一次确认
  onResetProgress() {
    const token = wx.getStorageSync('token')
    if (!token) {
      wx.showToast({ title: '请先登录', icon: 'none' })
      return
    }
    wx.showModal({
      title: '重置进度',
      content: '会清空连对进度，并把等级退回菜鸟。确定吗？',
      confirmText: '确定',
      cancelText: '取消',
      success: res => {
        if (!res.confirm) {
          return
        }
        api.request('/api/teach/quiz/reset', {
          method: 'POST',
          header: { token }
        }).then(() => {
          wx.showToast({ title: '已重置', icon: 'none' })
          this.loadProfile()
        }).catch(err => {
          wx.showToast({ title: err.message || '重置失败', icon: 'none' })
        })
      }
    })
  },

  // ── 身份：演示用跳级（契约 §7.6）────────────────────────────────────────

  // **每次点击都先弹一次确认**，三次都点「确定」才真的发请求；中途取消不计次、也不发请求
  onTapJump() {
    const step = this.data.taps
    wx.showModal({
      title: '跳级确认',
      content: JUMP_CONFIRMS[step],
      confirmText: '确定',
      cancelText: '取消',
      success: res => {
        if (!res.confirm) {
          return
        }
        const taps = step + 1
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
          // 后端在 dev/level 里已清掉连对进度；这里刷新身份条（练习面板下次进入会重抽）
          this.loadProfile()
        }).catch(err => {
          wx.showToast({ title: err.message || '跳级失败', icon: 'none' })
        })
      }
    })
  },

  // ── 未登录态 ───────────────────────────────────────────────────────────

  onRetryLogin() {
    const app = getApp()
    if (app && app.retryLogin) {
      app.retryLogin()
    }
    wx.showToast({ title: '正在重试登录', icon: 'none' })
    // 登录是异步的，给它一点时间再回头读 profile
    setTimeout(() => this.loadProfile(), 1500)
  }
})
