// MagicWebShop frontend
const state = {
    token: localStorage.getItem('mws_token') || null,
    name: localStorage.getItem('mws_name') || null,
    currency: '$',
    listings: [],
    filter: 'all',
    search: '',
    favorites: [],
    sellerUuid: null,
    sellerName: null,
    theme: 'scifi'
};

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));

function escapeHtml(s) {
    return String(s == null ? '' : s)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

async function api(path, opts = {}) {
    const headers = opts.headers || {};
    if (state.token) headers['X-Session-Token'] = state.token;
    if (opts.body) headers['Content-Type'] = 'application/json';
    const res = await fetch(path, { ...opts, headers });
    const text = await res.text();
    try { return JSON.parse(text); } catch { return text; }
}

function toast(msg, type = '') {
    const t = $('#toast');
    t.textContent = msg;
    t.className = 'toast ' + type;
    setTimeout(() => t.classList.add('hidden'), 2600);
}

// ---------------- account ----------------
function refreshAccountUi() {
    const loginBtn = $('#loginBtn');
    const mineBtn = $('#mineBtn');
    const favBtn = $('#favBtn');
    if (state.token && state.name) {
        loginBtn.textContent = '👤 ' + state.name;
        mineBtn.classList.remove('hidden');
        favBtn.classList.remove('hidden');
    } else {
        loginBtn.textContent = '登录';
        mineBtn.classList.add('hidden');
        favBtn.classList.add('hidden');
    }
}

function openModal(id) { $(id).classList.remove('hidden'); }
function closeModal(id) { $(id).classList.add('hidden'); }

// close buttons + backdrop click
document.addEventListener('click', (e) => {
    if (e.target.matches('[data-close]')) e.target.closest('.modal-mask').classList.add('hidden');
    if (e.target.classList.contains('modal-mask')) e.target.classList.add('hidden');
});

$('#loginBtn').addEventListener('click', () => {
    if (state.token) {
        // logout
        state.token = null; state.name = null;
        state.favorites = [];
        localStorage.removeItem('mws_token'); localStorage.removeItem('mws_name');
        refreshAccountUi();
        updateFavButton();
        toast('已退出登录');
        return;
    }
    openModal('#loginModal');
});

$('#loginSubmit').addEventListener('click', async () => {
    const name = $('#loginName').value.trim();
    const usePass = $('#passField').classList.contains('hidden') === false;
    const code = $('#loginCode').value.trim();
    const password = $('#loginPass').value;
    if (!name || (usePass ? !password : !code)) { toast('请填写昵称和' + (usePass ? '固定密码' : '登录码'), 'err'); return; }
    const body = usePass ? { name, password } : { name, code };
    const r = await api('/api/login', { method: 'POST', body: JSON.stringify(body) });
    if (r && r.ok) {
        state.token = r.token; state.name = r.name;
        localStorage.setItem('mws_token', r.token);
        localStorage.setItem('mws_name', r.name);
        refreshAccountUi();
        closeModal('#loginModal');
        toast('登录成功，欢迎 ' + r.name, 'ok');
        if (state.sellerUuid) updateFavButton();
    } else {
        toast((r && r.error) || '登录失败', 'err');
    }
});
// login mode tabs: 登录码 / 固定密码
function setLoginMode(pass) {
    $('#lmCode').classList.toggle('active', !pass);
    $('#lmPass').classList.toggle('active', pass);
    $('#codeField').classList.toggle('hidden', pass);
    $('#passField').classList.toggle('hidden', !pass);
}
$('#lmCode').addEventListener('click', () => setLoginMode(false));
$('#lmPass').addEventListener('click', () => setLoginMode(true));

// ---------------- catalog ----------------
function iconImg(l, cls) {
    const src = (l.isHead && l.headUrl) ? l.headUrl : l.iconUrl;
    return `<img loading="lazy" src="${escapeHtml(src)}" alt="${escapeHtml(l.displayName)}"
        onerror="this.onerror=null;this.src='/api/icon?material=${encodeURIComponent(l.material)}'"
        class="${cls || ''}">`;
}

function priceHtml(l) {
    if (l.type === 'MONEY') {
        const unit = (l.unitPrice > 0 && l.amount > 1)
            ? `<span class="unit-hint">单价 ${escapeHtml(state.currency)} ${formatNum(l.unitPrice)}</span>` : '';
        return `<div class="card-price">
            <span class="price-symbol">${escapeHtml(state.currency)}</span>
            <span class="price-value">${formatNum(l.price)}</span>${unit}</div>`;
    }
    return `<div class="card-price"><span class="barter-want">换 ${l.wantedAmount} × ${escapeHtml(l.wantedName)}</span></div>`;
}

function formatNum(n) {
    if (n == null) return '0';
    return (Math.round(n * 100) / 100).toLocaleString();
}

function card(l) {
    const badge = l.type === 'MONEY'
        ? '<span class="badge money">一口价</span>'
        : '<span class="badge barter">换物</span>';
    const buyClass = l.type === 'MONEY' ? '' : 'barter';
    const buyText = l.type === 'MONEY' ? '立即购买' : '用物品交换';
    const sellerLink = (l.sellerUuid && l.sellerName)
        ? `<button type="button" class="seller-chip btn-seller" data-uuid="${escapeHtml(l.sellerUuid)}"
            data-seller="${escapeHtml(l.sellerName)}" title="查看 ${escapeHtml(l.sellerName)} 的店铺">🧑 ${escapeHtml(l.sellerName)}</button>`
        : `<span class="chip">🧑 ${escapeHtml(l.sellerName)}</span>`;
    return `<article class="card">
        <div class="card-thumb ${l.enchanted ? 'enchanted' : ''}">
            ${badge}
            ${iconImg(l)}
            <span class="badge-amount">×${l.amount}</span>
        </div>
        <div class="card-body">
            <div class="card-title">${escapeHtml(l.displayName)}</div>
            <div class="card-meta">
                <span class="chip server">🖧 ${escapeHtml(l.server)}</span>
                ${sellerLink}
            </div>
            ${priceHtml(l)}
            <div class="card-actions">
                <button class="btn-buy ${buyClass}" data-id="${escapeHtml(l.id)}"
                    data-server="${escapeHtml(l.server)}">${buyText}</button>
            </div>
        </div>
    </article>`;
}

function applyFilters() {
    const q = state.search.toLowerCase();
    return state.listings.filter(l => {
        if (state.filter !== 'all' && l.type !== state.filter) return false;
        if (!q) return true;
        return (l.displayName || '').toLowerCase().includes(q)
            || (l.material || '').toLowerCase().includes(q)
            || (l.sellerName || '').toLowerCase().includes(q);
    });
}

// ---------------- display modes: pages (rows-per-page) or load-more ----------------
const PAGE_SIZE = 60;           // fallback "load more" batch
let rowsPerPage = 7;            // filled from /api/config
let viewMode = 'pages';         // 'pages' | 'loadmore'
let page = 1;
let shownCount = PAGE_SIZE;

function gridCols() {
    const g = $('#grid');
    const cols = getComputedStyle(g).gridTemplateColumns.split(' ').length;
    return Math.max(1, cols);
}
function pageSize() {
    return viewMode === 'pages' ? rowsPerPage * gridCols() : shownCount;
}
function setViewMode(mode) {
    viewMode = mode === 'loadmore' ? 'loadmore' : 'pages';
    localStorage.setItem('mws_view', viewMode);
    page = 1;
    shownCount = PAGE_SIZE;
    $('#modeToggle').textContent = viewMode === 'pages' ? '切换：加载更多' : '切换：翻页';
    $('#pager').classList.toggle('hidden', viewMode !== 'pages');
    render();
}

function render() {
    const list = applyFilters();
    const total = list.length;
    $('#count').textContent = total + ' 件商品';
    const grid = $('#grid');
    if (total === 0) {
        grid.innerHTML = '';
        grid.style.minHeight = '';
        $('#empty').classList.remove('hidden');
        $('#loadMoreWrap').classList.add('hidden');
        $('#pager').classList.add('hidden');
        return;
    }
    $('#empty').classList.add('hidden');

    let visible;
    if (viewMode === 'pages') {
        const size = pageSize();
        const totalPages = Math.max(1, Math.ceil(total / size));
        page = Math.min(page, totalPages);
        visible = list.slice((page - 1) * size, page * size);
        // reserve only the rows that are actually rendered, so filtered results
        // (fewer rows) never get stretched by grid align-content: stretch
        const rows = Math.max(1, Math.ceil(visible.length / gridCols()));
        grid.style.minHeight = (rows * 400) + 'px';
        $('#pager').classList.remove('hidden');
        $('#pageInfo').textContent = '第 ' + page + ' / ' + totalPages + ' 页';
        $('#prevPage').disabled = page <= 1;
        $('#nextPage').disabled = page >= totalPages;
        $('#loadMoreWrap').classList.add('hidden');
    } else {
        grid.style.minHeight = '';
        visible = list.slice(0, shownCount);
        $('#pager').classList.add('hidden');
        const wrap = $('#loadMoreWrap');
        if (shownCount < total) {
            wrap.classList.remove('hidden');
            $('#loadMore').textContent = '加载更多（还剩 ' + (total - shownCount) + ' 件）';
        } else {
            wrap.classList.add('hidden');
        }
    }
    grid.innerHTML = visible.map(card).join('');
}

async function loadListings() {
    const data = await api('/api/listings');
    const arr = Array.isArray(data) ? data : [];
    const changed = arr.length !== state.listings.length;
    state.listings = arr;
    if (changed) render();
}

// buy
let buyTarget = null;
let buyQty = 1;

function hasUnit(l) { return l.type === 'MONEY' && l.unitPrice > 0 && l.amount > 1; }

function buyCost(l, qty) {
    if (l.type !== 'MONEY') return 0;
    return qty >= l.amount ? l.price : Math.round(l.unitPrice * qty * 100) / 100;
}

function updateBuyCost() {
    const l = buyTarget;
    if (!l || l.type !== 'MONEY') return;
    const inp = $('#buyQty');
    if (inp) {
        let q = parseInt(inp.value) || 1;
        q = Math.max(1, Math.min(q, l.amount));
        buyQty = q; inp.value = q;
    } else {
        buyQty = l.amount;
    }
    const el = $('#buyCost');
    if (!el) return;
    const all = buyQty >= l.amount;
    el.innerHTML = all
        ? `全部买下(一口价) <b>${escapeHtml(state.currency)} ${formatNum(l.price)}</b>`
        : `单价 ${escapeHtml(state.currency)} ${formatNum(l.unitPrice)} × ${buyQty} = <b>${escapeHtml(state.currency)} ${formatNum(buyCost(l, buyQty))}</b>`;
}

$('#grid').addEventListener('click', (e) => {
    const chip = e.target.closest('.seller-chip');
    if (chip) {
        // explicit navigation: never rely on the <a> default hash jump
        e.preventDefault();
        const uuid = chip.dataset.uuid || '';
        if (uuid) { state.sellerUuid = uuid; state.sellerName = chip.dataset.seller || null; location.hash = '#/shop/' + encodeURIComponent(uuid); }
        return;
    }
    const btn = e.target.closest('.btn-buy');
    if (!btn) return;
    if (!state.token) { openModal('#loginModal'); toast('请先登录', 'err'); return; }
    const l = state.listings.find(x => x.id === btn.dataset.id && x.server === btn.dataset.server);
    if (!l) return;
    buyTarget = l;
    buyQty = hasUnit(l) ? 1 : l.amount;

    let body;
    if (l.type !== 'MONEY') {
        body = `<div class="p" style="color:var(--violet)">需要 ${l.wantedAmount} × ${escapeHtml(l.wantedName)}</div>`;
    } else if (hasUnit(l)) {
        body = `<div class="qtyrow">数量
            <button class="qbtn" data-q="-1">−</button>
            <input id="buyQty" type="number" min="1" max="${l.amount}" value="1">
            <button class="qbtn" data-q="1">＋</button>
            <button class="qbtn" id="buyAllBtn">全部(${l.amount})</button></div>
            <div class="p" id="buyCost"></div>`;
    } else {
        body = `<div class="p">${escapeHtml(state.currency)} ${formatNum(l.price)}</div>`;
    }
    $('#buyBody').innerHTML = `${iconImg(l)}
        <div class="buy-info">
            <div class="n">${escapeHtml(l.displayName)} ×${l.amount}</div>
            <div class="t">来自 ${escapeHtml(l.server)} · 卖家 ${escapeHtml(l.sellerName)}</div>
            ${body}
            ${l.type === 'MONEY' ? '<div class="t">物品将送达你的背包或邮箱（游戏内 /webshop delivery 可切换）。</div>' : ''}
        </div>`;
    $('#buyTitle').textContent = l.type === 'MONEY' ? '确认购买' : '确认换物';
    updateBuyCost();
    openModal('#buyModal');
});

$('#buyBody').addEventListener('click', (e) => {
    const b = e.target.closest('.qbtn');
    if (!b || !buyTarget) return;
    const inp = $('#buyQty');
    if (b.id === 'buyAllBtn') { inp.value = buyTarget.amount; }
    else { inp.value = (parseInt(inp.value) || 1) + parseInt(b.dataset.q); }
    updateBuyCost();
});
$('#buyBody').addEventListener('input', (e) => { if (e.target.id === 'buyQty') updateBuyCost(); });

$('#buyConfirm').addEventListener('click', async () => {
    if (!buyTarget) return;
    $('#buyConfirm').disabled = true;
    const r = await api('/api/purchase', {
        method: 'POST',
        body: JSON.stringify({ listingId: buyTarget.id, server: buyTarget.server, quantity: buyQty })
    });
    $('#buyConfirm').disabled = false;
    closeModal('#buyModal');
    if (r && r.ok) {
        toast(r.message || '交易成功', 'ok');
        loadListings();
    } else {
        toast((r && r.message) || '交易失败', 'err');
    }
});

// my listings
$('#mineBtn').addEventListener('click', async () => {
    openModal('#mineModal');
    const box = $('#mineList');
    box.innerHTML = '<div class="mine-empty">加载中…</div>';
    const data = await api('/api/mine');
    const list = Array.isArray(data) ? data : [];
    if (list.length === 0) { box.innerHTML = '<div class="mine-empty">你还没有上架任何物品。</div>'; return; }
    box.innerHTML = list.map(l => {
        const terms = l.type === 'MONEY'
            ? `${escapeHtml(state.currency)} ${formatNum(l.price)}`
            : `换 ${l.wantedAmount} × ${escapeHtml(l.wantedName)}`;
        return `<div class="mine-row">
            ${iconImg(l)}
            <div class="m-info"><div class="n">${escapeHtml(l.displayName)} ×${l.amount}</div>
            <div class="t">${terms}</div></div>
            <button data-cancel="${escapeHtml(l.id)}">下架</button>
        </div>`;
    }).join('');
});

$('#mineList').addEventListener('click', async (e) => {
    const btn = e.target.closest('[data-cancel]');
    if (!btn) return;
    const r = await api('/api/cancel', { method: 'POST', body: JSON.stringify({ listingId: btn.dataset.cancel }) });
    if (r && r.ok) { toast('已下架，物品将退回', 'ok'); btn.closest('.mine-row').remove(); loadListings(); }
    else toast((r && r.message) || '下架失败', 'err');
});

// tabs + search
$$('.tab').forEach(tab => tab.addEventListener('click', () => {
    $$('.tab').forEach(t => t.classList.remove('active'));
    tab.classList.add('active');
    state.filter = tab.dataset.filter;
    page = 1; shownCount = PAGE_SIZE;
    render();
}));
$('#searchBtn').addEventListener('click', () => { state.search = $('#search').value.trim(); page = 1; shownCount = PAGE_SIZE; render(); });
$('#search').addEventListener('input', () => { state.search = $('#search').value.trim(); page = 1; shownCount = PAGE_SIZE; render(); });
$('#loadMore').addEventListener('click', () => { shownCount += PAGE_SIZE; render(); });

// view mode + pagination controls
$('#modeToggle').addEventListener('click', () => setViewMode(viewMode === 'pages' ? 'loadmore' : 'pages'));
$('#prevPage').addEventListener('click', () => { if (page > 1) { page--; render(); } });
$('#nextPage').addEventListener('click', () => { const t = Math.max(1, Math.ceil(applyFilters().length / pageSize())); if (page < t) { page++; render(); } });
let resizeTimer = null;
window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => { if (viewMode === 'pages') render(); }, 200);
});

// ---------------- player shop page (hash route #/shop/<uuid>) ----------------
const shopListings = [];

function currentShopUuid() {
    const m = location.hash.match(/^#\/shop\/(.+)$/);
    return m ? decodeURIComponent(m[1]) : null;
}

function showView() {
    const uuid = currentShopUuid();
    if (uuid) {
        $('#marketView').classList.add('hidden');
        $('#shopView').classList.remove('hidden');
        loadShop(uuid);
        document.title = '魔法集市 · 玩家店铺';
    } else {
        $('#shopView').classList.add('hidden');
        $('#marketView').classList.remove('hidden');
        document.title = 'MagicWebShop · 魔法集市';
    }
}

window.addEventListener('hashchange', showView);

$('#shopBack').addEventListener('click', (e) => {
    e.preventDefault();
    location.hash = '';
});

async function loadShop(uuid) {
    // remember the display name from the catalog if we have it
    if (!state.sellerName || state.sellerUuid !== uuid) {
        const hit = state.listings.find(l => l.sellerUuid === uuid);
        if (hit) state.sellerName = hit.sellerName;
    }
    state.sellerUuid = uuid;
    const params = new URLSearchParams({ uuid, name: state.sellerName || '' });
    const data = await api('/api/seller?' + params.toString());
    if (!data || typeof data !== 'object') { toast('加载店铺失败', 'err'); return; }

    const name = data.name || state.sellerName || '玩家';
    $('#shopName').textContent = name;
    $('#shopAvatar').src = data.avatarUrl || '/api/icon?material=PLAYER_HEAD';
    $('#shopAvatar').onerror = function () {
        this.onerror = null;
        this.src = '/api/icon?material=PLAYER_HEAD';
    };

    const stats = data.stats || {};
    const listings = Array.isArray(data.listings) ? data.listings : [];
    shopListings.length = 0;
    shopListings.push(...listings);

    $('#stOnSale').textContent = listings.length;
    $('#stListed').textContent = stats.listedCount || 0;
    $('#stSold').textContent = stats.soldCount || 0;
    $('#stSoldItems').textContent = stats.soldItems || 0;
    $('#stEarned').textContent = state.currency + ' ' + formatNum(stats.earned || 0);
    $('#stBarter').textContent = stats.barterCount || 0;

    const servers = Array.from(new Set(listings.map(l => l.server)));
    $('#shopMeta').textContent = servers.length
        ? '活跃于 ' + servers.join(' · ')
        : (data.stats ? '跨服卖家' : '');

    if (listings.length === 0) {
        $('#shopGrid').innerHTML = '';
        $('#shopEmpty').classList.remove('hidden');
    } else {
        $('#shopEmpty').classList.add('hidden');
        $('#shopGrid').innerHTML = listings.map(card).join('');
    }
    await updateFavButton();
    window.scrollTo({ top: 0, behavior: 'smooth' });
}

// buy from the shop page works exactly like the market grid
$('#shopGrid').addEventListener('click', (e) => {
    const chip = e.target.closest('.seller-chip');
    if (chip) {
        e.preventDefault();
        const uuid = chip.dataset.uuid || '';
        if (uuid) { state.sellerUuid = uuid; state.sellerName = chip.dataset.seller || null; location.hash = '#/shop/' + encodeURIComponent(uuid); }
        return;
    }
    const btn = e.target.closest('.btn-buy');
    if (!btn) return;
    if (!state.token) { openModal('#loginModal'); toast('请先登录', 'err'); return; }
    const l = shopListings.find(x => x.id === btn.dataset.id && x.server === btn.dataset.server);
    if (!l) return;
    buyTarget = l;
    buyQty = hasUnit(l) ? 1 : l.amount;

    let body;
    if (l.type !== 'MONEY') {
        body = `<div class="p" style="color:var(--violet)">需要 ${l.wantedAmount} × ${escapeHtml(l.wantedName)}</div>`;
    } else if (hasUnit(l)) {
        body = `<div class="qtyrow">数量
            <button class="qbtn" data-q="-1">−</button>
            <input id="buyQty" type="number" min="1" max="${l.amount}" value="1">
            <button class="qbtn" data-q="1">＋</button>
            <button class="qbtn" id="buyAllBtn">全部(${l.amount})</button></div>
            <div class="p" id="buyCost"></div>`;
    } else {
        body = `<div class="p">${escapeHtml(state.currency)} ${formatNum(l.price)}</div>`;
    }
    $('#buyBody').innerHTML = `${iconImg(l)}
        <div class="buy-info">
            <div class="n">${escapeHtml(l.displayName)} ×${l.amount}</div>
            <div class="t">来自 ${escapeHtml(l.server)} · 卖家 ${escapeHtml(l.sellerName)}</div>
            ${body}
            ${l.type === 'MONEY' ? '<div class="t">物品将送达你的背包或邮箱（游戏内 /webshop delivery 可切换）。</div>' : ''}
        </div>`;
    $('#buyTitle').textContent = l.type === 'MONEY' ? '确认购买' : '确认换物';
    updateBuyCost();
    openModal('#buyModal');
});

// ---------------- favorite sellers ----------------
function isFavorited(uuid) {
    return state.favorites.some(f => f.uuid === uuid);
}

async function updateFavButton() {
    const uuid = state.sellerUuid;
    const btn = $('#shopFav');
    if (!uuid) { btn.classList.add('hidden'); return; }
    if (!state.token) {
        btn.textContent = '☆ 收藏卖家';
        btn.classList.remove('faved');
        btn.classList.remove('hidden');
        return;
    }
    await loadFavorites(false);
    const faved = isFavorited(uuid);
    btn.classList.remove('hidden');
    btn.classList.toggle('faved', faved);
    btn.textContent = faved ? '★ 已收藏' : '☆ 收藏卖家';
}

async function loadFavorites(notifyError = true) {
    if (!state.token) { state.favorites = []; return []; }
    const r = await api('/api/favorites');
    if (Array.isArray(r)) { state.favorites = r; return r; }
    if (notifyError) toast('获取收藏失败', 'err');
    return [];
}

$('#shopFav').addEventListener('click', async () => {
    if (!state.token) { openModal('#loginModal'); toast('收藏需要先登录', 'err'); return; }
    const uuid = state.sellerUuid;
    const name = $('#shopName').textContent;
    if (!uuid) return;
    if (isFavorited(uuid)) {
        await api('/api/unfavorite', { method: 'POST', body: JSON.stringify({ uuid }) });
        toast('已取消收藏', 'ok');
    } else {
        await api('/api/favorite', { method: 'POST', body: JSON.stringify({ uuid, name }) });
        toast('已收藏卖家 ' + name, 'ok');
    }
    await loadFavorites();
    updateFavButton();
});

$('#favBtn').addEventListener('click', async () => {
    openModal('#favModal');
    const box = $('#favList');
    box.innerHTML = '<div class="mine-empty">加载中…</div>';
    const list = await loadFavorites();
    if (list.length === 0) { box.innerHTML = '<div class="mine-empty">你还没有收藏任何卖家。<br>在商品卡上点击卖家名进入店铺，点「收藏卖家」即可。</div>'; return; }
    box.innerHTML = list.map(f => {
        const url = '#/shop/' + encodeURIComponent(f.uuid);
        return `<div class="mine-row">
            <img loading="lazy" src="/api/avatar?u=${encodeURIComponent(f.uuid)}" alt="头像"
                onerror="this.onerror=null;this.src='/api/icon?material=PLAYER_HEAD'">
            <div class="m-info"><div class="n">${escapeHtml(f.name)}</div>
            <div class="t">收藏于 ${new Date(f.createdAt).toLocaleDateString()}</div></div>
            <button data-favgo="${url}" class="btn-ghost" style="flex:0 0 auto">进入</button>
        </div>`;
    }).join('');
});

$('#favList').addEventListener('click', (e) => {
    const btn = e.target.closest('[data-favgo]');
    if (!btn) return;
    closeModal('#favModal');
    location.hash = btn.dataset.favgo;
});

// Auto-login when the player opened a magic link from /webshop web (?u=name&c=code)
async function autoLoginFromUrl() {
    const params = new URLSearchParams(location.search);
    const u = params.get('u');
    const c = params.get('c');
    if (!u || !c) return;
    // strip the credentials from the address bar immediately
    history.replaceState(null, '', location.pathname);
    const r = await api('/api/login', { method: 'POST', body: JSON.stringify({ name: u, code: c }) });
    if (r && r.ok) {
        state.token = r.token; state.name = r.name;
        localStorage.setItem('mws_token', r.token);
        localStorage.setItem('mws_name', r.name);
        toast('已自动登录，欢迎 ' + r.name, 'ok');
    } else {
        toast((r && r.error) || '自动登录失败，请用登录码手动登录', 'err');
    }
}

// theme
function applyTheme(t) {
    state.theme = t;
    document.body.classList.toggle('theme-cute', t === 'cute');
    localStorage.setItem('mws_theme', t);
    const btn = $('#themeBtn');
    if (btn) {
        btn.textContent = t === 'cute' ? '🚀' : '🌸';
        btn.title = t === 'cute' ? '切换到科幻风格' : '切换到可爱风格';
    }
}
$('#themeBtn').addEventListener('click', () => {
    applyTheme(state.theme === 'cute' ? 'scifi' : 'cute');
});

// init
async function init() {
    applyTheme(localStorage.getItem('mws_theme') || 'scifi');
    const cfg = await api('/api/config');
    if (cfg && cfg.serverName) {
        $('#serverName').textContent = cfg.serverName + ' · 魔法集市';
        state.currency = cfg.currencySymbol || '$';
        rowsPerPage = cfg.rowsPerPage || 7;
        viewMode = localStorage.getItem('mws_view') || cfg.defaultView || 'pages';
        viewMode = viewMode === 'loadmore' ? 'loadmore' : 'pages';
        $('#modeToggle').textContent = viewMode === 'pages' ? '切换：加载更多' : '切换：翻页';
        $('#pager').classList.toggle('hidden', viewMode !== 'pages');
    }
    await autoLoginFromUrl();
    refreshAccountUi();
    await loadListings();
    if (state.token) loadFavorites(false);
    showView(); // honour deep links like #/shop/<uuid>
    setInterval(loadListings, 15000);
}
init();
