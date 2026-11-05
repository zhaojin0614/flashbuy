// 闪购 FlashBuy 公共脚本：axios 配置 + 导航渲染 + 提示
axios.defaults.baseURL = '/api';
axios.defaults.timeout = 5000;

// 防御：历史遗留的坏 token（如字符串 "undefined"）自动清除
if (sessionStorage.getItem('token') === 'undefined') sessionStorage.removeItem('token');

// 单飞刷新的共享 Promise：同一时刻只有一次 /user/refresh 在路上
let refreshingPromise = null;

// 请求拦截：携带 token
axios.interceptors.request.use(config => {
  const token = sessionStorage.getItem('token');
  if (token) config.headers['authorization'] = token;
  return config;
}, error => Promise.reject(error));

// 响应拦截：解包 Result；401 跳登录
axios.interceptors.response.use(response => {
  if (!response.data.success) return Promise.reject(response.data.errorMsg || '操作失败');
  return response.data;
}, error => {
  const resp = error.response;
  if (resp && resp.status === 401) {
    const cfg = resp.config || {};
    const rt = sessionStorage.getItem('refreshToken');
    // 单飞刷新：并发多个 401 只发一次 /user/refresh，成功后统一重放原请求
    if (rt && !cfg._retried) {
      if (!refreshingPromise) {
        refreshingPromise = axios.post('/user/refresh', { refreshToken: rt }).then(r => {
          sessionStorage.setItem('token', r.data.accessToken);
          sessionStorage.setItem('refreshToken', r.data.refreshToken);
          refreshingPromise = null;
        }).catch(e => {
          refreshingPromise = null;
          sessionStorage.clear();
          if (location.pathname.indexOf('login.html') === -1) {
            setTimeout(() => location.href = '/login.html', 200);
          }
          throw e;
        });
      }
      return refreshingPromise.then(() => {
        cfg._retried = true;   // 防止刷新后仍 401 造成二次循环
        cfg.headers = cfg.headers || {};
        cfg.headers['authorization'] = sessionStorage.getItem('token');
        return axios(cfg);
      });
    }
    // 已在登录页时不再跳转，避免登录过程中的 401 造成死循环
    if (location.pathname.indexOf('login.html') === -1) {
      setTimeout(() => location.href = '/login.html', 300);
    }
    return Promise.reject('请先登录');
  }
  return Promise.reject('网络异常，请稍后重试');
});

const isLoggedIn = () => !!sessionStorage.getItem('token');
const goLogin = () => location.href = '/login.html';
const goBack = () => history.back();

// 导航栏渲染
function renderNav(active) {
  const nav = document.getElementById('nav');
  nav.innerHTML = `
  <div class="nav-inner">
    <div class="logo" onclick="location.href='/index.html'">闪购 FlashBuy</div>
    <a class="nav-link ${active === 'index' ? 'active' : ''}" href="/index.html">首页</a>
    <a class="nav-link ${active === 'post' ? 'active' : ''}" href="/post.html">晒单</a>
    <div class="nav-right">
      ${isLoggedIn()
        ? `<div class="nav-user" onclick="location.href='/profile.html'">
             <img src="${sessionStorage.getItem('avatar') || '/imgs/icons/user.png'}" alt="">
             <span>${sessionStorage.getItem('nick') || '我'}</span>
           </div>`
        : `<button class="btn-login" onclick="goLogin()">登录</button>`}
    </div>
  </div>`;
}

// me 接口刷新导航用户信息
async function loadMe() {
  if (!isLoggedIn()) return;
  try {
    const r = await axios.get('/user/me');
    sessionStorage.setItem('nick', r.data.nickName || r.data.nick || '我');
    sessionStorage.setItem('avatar', r.data.icon || '/imgs/icons/user.png');
    sessionStorage.setItem('uid', r.data.id);
    return r.data;
  } catch (e) { /* 静默 */ }
}

// 提示气泡
let toastTimer = null;
function toast(msg, type) {
  let el = document.querySelector('.toast');
  if (!el) {
    el = document.createElement('div');
    document.body.appendChild(el);
  }
  el.className = 'toast ' + (type || '');
  el.textContent = msg;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.remove(), 2400);
}

// 图片取第一张
const firstImg = imgs => (imgs || '').split(',')[0] || '';
