const TABS = [
  { pagePath: '/pages/main/main', text: '主页', icon: '/images/main.png' },
  { pagePath: '/pages/archive/archive', text: '图鉴', icon: '/images/item.png' },
  { pagePath: '/pages/teach/teach', text: '快速上手', icon: '/images/guidebook.png' },
  // 图标先复用 map.png —— images/ 里还没有人物图标，等有新的再换
  { pagePath: '/pages/profile/profile', text: '个人', icon: '/images/map.png' }
]

Component({
  data: {
    list: TABS,
    // -1 = 没有任何 tab 处于选中态。初始值和 hide() 都归到这个值，
    // 这样每次显示页面时 selected 都有一段真实的变化，放大过渡才会播放；
    // 否则组件实例缓存着上次的下标，setData 传入相同值不会触发任何动画。
    selected: -1
  },

  pageLifetimes: {
    hide() {
      // 页面隐藏时归零。此刻 tabBar 不可见，所以不会被看到。
      this.setData({ selected: -1 })
    }
  },

  methods: {
    // 由各 tab 页的 onShow 调用，告诉 tabBar 现在该高亮谁。
    select(pagePath) {
      const index = TABS.findIndex(tab => tab.pagePath === pagePath)
      if (index < 0 || index === this.data.selected) return
      this.setData({ selected: index })
    },

    onTap(e) {
      const { index, path } = e.currentTarget.dataset
      if (index === this.data.selected) return
      wx.switchTab({ url: path })
    }
  }
})
