// 统一请求封装。后端响应壳固定为 {success, code, message, data}
const BASE_URL = 'http://localhost:8080'

// options 可省略 —— 省略时就是一个裸 GET，图鉴那三条调用照旧。
// 教学端点要的是 POST + token 头 + JSON body，都从 options 走。
// token 头由这里统一带：登录时 app.js 已把 token 写进 storage，每个请求都会带上，
// 页面里不必再手动传 header: { token }。
function request(path, options) {
  const opts = options || {}
  const token = wx.getStorageSync('token')
  const header = Object.assign(
    { 'content-type': 'application/json' },
    token ? { token } : {},
    opts.header || {}
  )
  return new Promise((resolve, reject) => {
    wx.request({
      url: BASE_URL + path,
      method: opts.method || 'GET',
      data: opts.data,
      header,
      success: res => {
        const body = res.data
        // 401 = 登录失效：清掉本地登录态，静默重登（微信登录无需人工页面），
        // 下个请求自然带上新 token
        if (res.statusCode === 401 || (body && body.code === 401)) {
          wx.removeStorageSync('token')
          wx.removeStorageSync('openid')
          const app = getApp()
          if (app && app.login) app.login()
          reject(new Error((body && body.message) || '未登录'))
          return
        }
        if (res.statusCode === 200 && body && body.success) resolve(body.data)
        else reject(new Error((body && body.message) || 'HTTP ' + res.statusCode))
      },
      fail: err => reject(new Error(err.errMsg))
    })
  })
}

// 启动时一次拉全：图鉴条目 + 标签 + 生态
function fetchAll() {
  return Promise.all([
    request('/api/items'),
    request('/api/tags'),
    request('/api/biomes')
  ]).then(([items, tags, biomes]) => ({
    items: items.items || [],
    tags: tags.tags || [],
    biomes: biomes.biomes || []
  }))
}

// 接口里的 icon 是相对路径（/icons/Hot_Dog.png），那是给后端静态映射看的；
// 小程序里 <image src> 必须带 origin，否则会被当成包内文件而加载失败。
//
// 逐段百分号编码是必需的，不是保险：库里有 11 个文件名带 ' 或 ()——
// Pandora's_Lunchbox、Pirate's_Compass、Scout's_*（5 个）、Scoutmaster's_Bugle、
// 以及 3 个 *_Shroom_(Poisonous)。原样丢给小程序，这几个 <image> 加载不出来。
// split/join 是为了不把路径里的 / 也编成 %2F；其余文件名编码后与原串一致。
function assetUrl(path) {
  if (!path) return ''
  // encodeURIComponent 不转义 ' ( ) ! *，得自己补上 —— 库里那 11 个文件名正好用到了 ' 和 ()
  const strict = s => encodeURIComponent(s).replace(/[!'()*]/g, c => '%' + c.charCodeAt(0).toString(16).toUpperCase())
  return BASE_URL + path.split('/').map(strict).join('/')
}

module.exports = { BASE_URL, request, fetchAll, assetUrl }
