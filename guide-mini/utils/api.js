// 统一请求封装。后端响应壳固定为 {success, code, message, data}
const BASE_URL = 'http://localhost:8080'

// options 可省略 —— 省略时就是一个裸 GET，图鉴那三条调用照旧。
// 教学端点要的是 POST + token 头 + JSON body，都从 options 走。
function request(path, options) {
  const opts = options || {}
  return new Promise((resolve, reject) => {
    wx.request({
      url: BASE_URL + path,
      method: opts.method || 'GET',
      data: opts.data,
      header: Object.assign({ 'content-type': 'application/json' }, opts.header),
      success: res => {
        const body = res.data
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

module.exports = { BASE_URL, request, fetchAll }
