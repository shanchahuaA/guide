// pages/teach/teach.js
const api = require('../../utils/api')

// 跳级后门：连点三次算升一级（契约 §7.6），计数到 3 才发请求
const TAPS_PER_LEVEL = 3

// 高手才配问路线（契约 §7.0 的等级矩阵）
const EXPERT_LEVEL = 2

Page({

  data: {
    profile: null,      // /api/teach/profile 的 data，null = 还没拿到（未登录 / 后端没起）
    loginError: null,   // 未登录时展示的原因（来自 app.js 记下的登录失败信息）
    isExpert: false,    // profile 的 level >= 2。路线分段靠它决定放行还是置灰
    taps: 0,            // 跳级按钮已点击数，显示成"跳级 x/3"
    tab: 'ask',         // 分段导航：ask / quiz / route
    // 「问答」分段。questions/answers 走聊天气泡流，messages 是 [{role, text, pending}]
    question: '',
    messages: [],
    scrollInto: '',
    asking: false,
    keyDraft: '',
    // 「路线」分段。routeAnswer 是引导语，routeLinks 是后端构造的 B站 链接，askingRoute 防重复
    routeAnswer: '',
    routeLinks: [],
    askingRoute: false,
    // 「练习」分段。quiz 是当前题目（**不含答案**），quizResult 是判题结果，
    // selectedIndex 是用户选的那一项，quizLoading 防重复出题
    quiz: null,
    quizResult: null,
    selectedIndex: null,
    quizLoading: false
  },

  onShow() {
    this.getTabBar().select('/pages/teach/teach')
    this.loadProfile()
  },

  // 身份条的数据源。没登录时后端返 401，api.request 走 reject 分支 —— 把 profile 归 null，
  // 并带上"为什么没登录"的原因（来自 app.js），页面据此显示明确提示而不是空白
  loadProfile() {
    const token = wx.getStorageSync('token')
    if (!token) {
      this.setData({ profile: null, isExpert: false, quiz: null, quizResult: null, loginError: this.loginErrorText() })
      return
    }
    api.request('/api/teach/profile', { header: { token } })
      .then(profile => {
        this.setData({ profile, isExpert: profile.level >= EXPERT_LEVEL, loginError: null })
        // 填过 Key 且还没出过题，进页面就把练习的第一题拉出来
        if (profile.hasApiKey && !this.data.quiz && !this.data.quizResult) {
          this.loadQuiz()
        }
      })
      .catch(err => this.setData({
        profile: null, isExpert: false, quiz: null, quizResult: null,
        loginError: err.message || this.loginErrorText()
      }))
  },

  loginErrorText() {
    const app = getApp()
    const fromApp = app && app.globalData && app.globalData.loginError
    return fromApp || wx.getStorageSync('loginError') || '未登录：后端没起，或 guide.wechat.appid / secret 没配'
  },

  onRetryLogin() {
    const app = getApp()
    if (app && app.retryLogin) {
      app.retryLogin()
    }
    wx.showToast({ title: '正在重试登录', icon: 'none' })
    setTimeout(() => this.loadProfile(), 1500)
  },

  // ── 分段导航 ──────────────────────────────────────────────────────────

  onSwitchTab(e) {
    this.setData({ tab: e.currentTarget.dataset.tab })
  },

  // ── 问答分段（契约 §7.4）──────────────────────────────────────────────

  onQuestionInput(e) {
    this.setData({ question: e.detail.value })
  },

  onKeyInput(e) {
    this.setData({ keyDraft: e.detail.value })
  },

  // 提交 Key：成功后就地把身份条刷成 hasApiKey=true，不必重新登录
  onSaveKey() {
    const apiKey = this.data.keyDraft.trim()
    if (!apiKey) {
      wx.showToast({ title: '请先填入 Key', icon: 'none' })
      return
    }
    api.request('/api/teach/apikey', {
      method: 'POST',
      header: { token: wx.getStorageSync('token') },
      data: { apiKey }
    }).then(() => {
      wx.showToast({ title: '已保存', icon: 'none' })
      this.setData({ keyDraft: '' })
      this.loadProfile()
    }).catch(err => {
      wx.showToast({ title: err.message || '保存失败', icon: 'none' })
    })
  },

  // 提问。先把用户那句和一条「思考中…」气泡推进列表，结果回来再把气泡替换掉。
  // 失败时后端已经给了一句中文 message（如「API Key 无效」），直接把它当回答气泡展示
  onAsk() {
    const question = this.data.question.trim()
    if (!question || this.data.asking) {
      return
    }
    const messages = this.data.messages.concat([
      { role: 'user', text: question },
      { role: 'ai', text: '思考中…', pending: true }
    ])
    this.setData({ asking: true, question: '', messages }, () => this.scrollToLatest())

    api.request('/api/teach/ask', {
      method: 'POST',
      header: { token: wx.getStorageSync('token') },
      data: { question }
    }).then(res => {
      this.replaceLatest(res.answer || '（空回答）')
      this.setData({ asking: false })
    }).catch(err => {
      this.replaceLatest(err.message || '提问失败')
      this.setData({ asking: false })
    })
  },

  // 把最后一条 ai 气泡的占位文本换成真结果
  replaceLatest(text) {
    const messages = this.data.messages.slice()
    const last = messages[messages.length - 1]
    if (!last || last.role !== 'ai') {
      return
    }
    messages[messages.length - 1] = { role: 'ai', text }
    this.setData({ messages }, () => this.scrollToLatest())
  },

  // scroll-view 的 scroll-into-view 需要一个 id，气泡的 id 就是 "m<下标>"
  scrollToLatest() {
    const index = this.data.messages.length - 1
    if (index >= 0) {
      this.setData({ scrollInto: 'm' + index })
    }
  },

  // ── 路线分段（契约 §7.4，仅高手）──────────────────────────────────────

  // 「今日路线」＝自动提问一句固定的路线问题。非高手只弹本地弹窗、**不发请求**（票面 AC）——
  // 后端那道硬拦是防手改前端的兜底，不是前端偷懒的替代。
  onAskRoute() {
    if (!this.data.isExpert) {
      wx.showModal({
        title: '还不能问',
        content: '路线类问题要成为高手才能问，先去练习升级吧！',
        showCancel: false
      })
      return
    }
    const token = wx.getStorageSync('token')
    if (!token) {
      wx.showToast({ title: '请先登录', icon: 'none' })
      return
    }
    if (this.data.askingRoute) {
      return
    }
    this.setData({ askingRoute: true })
    // 分级入口（契约 §7.7）：等级门禁在 Shiro 的 /api/teach/beginner/** 路径规则上，
    // 前端只负责发起，链接由后端构造。不走 /api/teach/ask 那条自由提问
    api.request('/api/teach/beginner/route', {
      method: 'POST',
      header: { token }
    }).then(res => {
      this.setData({ routeAnswer: res.answer || '', routeLinks: res.links || [], askingRoute: false })
    }).catch(err => {
      this.setData({ askingRoute: false })
      wx.showToast({ title: err.message || '获取路线失败', icon: 'none' })
    })
  },

  // 小程序不能直接打开外部网页，链接复制给用户自己去浏览器/App 里打开
  onCopyLink(e) {
    wx.setClipboardData({ data: e.currentTarget.dataset.url })
  },

  // ── 练习分段（契约 §7.3）──────────────────────────────────────────────

  // 抽一题。响应只有题号 / 题干 / 选项，答案要答完才由后端下发
  loadQuiz() {
    const token = wx.getStorageSync('token')
    if (!token || this.data.quizLoading) {
      return
    }
    this.setData({ quizLoading: true, quizResult: null, selectedIndex: null })
    api.request('/api/teach/quiz/next', { method: 'POST', header: { token } })
      .then(quiz => this.setData({ quiz: quiz || null, quizLoading: false }))
      .catch(err => {
        this.setData({ quizLoading: false })
        wx.showToast({ title: err.message || '出题失败', icon: 'none' })
      })
  },

  // 选一个选项作答。对错在后端比对下标，这里只把结果展开
  onChooseOption(e) {
    const quiz = this.data.quiz
    if (!quiz || this.data.quizResult) {
      return
    }
    const optionIndex = e.currentTarget.dataset.index
    api.request('/api/teach/quiz/answer', {
      method: 'POST',
      header: { token: wx.getStorageSync('token') },
      data: { questionId: quiz.questionId, choice: optionIndex }
    }).then(result => {
      this.setData({ quizResult: result, selectedIndex: optionIndex })
      // 连对与等级都可能变，就地把身份条刷新（loadProfile 里 quizResult 已在，不会重抽题）
      this.loadProfile()
    }).catch(err => {
      wx.showToast({ title: err.message || '判题失败', icon: 'none' })
    })
  },

  // 下一题：清掉上一题的判题结果再抽一道
  onNextQuestion() {
    this.loadQuiz()
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
