// 行为验收：对 frontend/src 下的纯逻辑 helper 与关键流程执行真实断言（非 regex 匹配源码）。
// 运行：bundled node（v24）直接执行，不依赖浏览器。
import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { acquire, settle, peek, newKey, readIntents, classify, settleIfApplied, clearScope } from '../src/intent.js';
import { enterAction, composing, composeAction } from '../src/iam.js';
import { orderDetails, reviewRows, canReview, alreadyReviewed, addReview, actionsFor, showConfirmReceived, governanceOrderId } from '../src/orders.js';
import { itemError, itemBody, priceError } from '../src/identity.js';
import { emptyPrivate, accountChanged, staleFor, verifyAction, favoriteView } from '../src/session.js';
import { statsQuery } from '../src/stats.js';
import { evidenceLog, authorLabel, evidenceView } from '../src/evidence.js';

const root = fileURLToPath(new URL('../', import.meta.url));
let passed = 0, failed = 0;
async function test(name, fn) {
  try { await fn(); passed++; console.log('PASS ' + name); }
  catch (e) { failed++; console.log('FAIL ' + name + '\n  ' + e.message); }
}
function mem() { const m = new Map(); return { getItem: k => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: k => m.delete(k) }; }
// 加载带 fetch 桩的 api.js 副本（每个测试独立模块实例，避免 csrf 串扰）。
async function loadApi() {
  const dir = mkdtempSync(join(tmpdir(), 'campus-api-'));
  writeFileSync(join(dir, 'api.js'), readFileSync(join(root, 'src', 'api.js')));
  return import(pathToFileURL(join(dir, 'api.js')).href + '?t=' + Math.random());
}
const A = { id: 'user-a' }, B = { id: 'user-b' };
const appSrc = () => readFileSync(join(root, 'src', 'App.vue'), 'utf8');

await test('#4 退出/换号后有返回值与无返回值两条路径都清空私有表单与材料标识', () => {
  const p = emptyPrivate();
  assert.deepEqual(p.verifyForm, { student_no: '', real_name: '', asset_id: '' });
  assert.equal(p.itemForm.image_id, '');
  assert.equal(emptyPrivate().itemForm.category, '教材书籍');
  assert.notEqual(emptyPrivate().verifyForm, p.verifyForm);
  assert.ok(accountChanged(A, B));
  assert.ok(accountChanged(A, null));
  assert.ok(!accountChanged({ id: 'user-a', nickname: '新昵称' }, A));
});

await test('#4 旧账户的迟到响应在换号后不得回填', () => {
  assert.ok(staleFor(A, B));
  assert.ok(staleFor(A, null));
  assert.ok(!staleFor(A, { id: 'user-a' }));
});

await test('#4 待认证标记：已登录进入个人中心，未登录打开登录框', () => {
  assert.equal(verifyAction(A), 'account');
  assert.equal(verifyAction(null), 'login');
});

await test('#5 A 未知→处理 B→重试 A 仍用 K1；不同订单互不覆盖', () => {
  const store = mem();
  const a1 = acquire(['user-a'], 'order:A:accept', 'user-a', '/orders/A/accept', { reason: '' }, store);
  const b1 = acquire(['user-a'], 'order:B:accept', 'user-a', '/orders/B/accept', { reason: '' }, store);
  assert.notEqual(a1.key, b1.key);
  settle(['user-a'], 'order:B:accept', b1.key, store);
  const a2 = acquire(['user-a'], 'order:A:accept', 'user-a', '/orders/A/accept', { reason: '' }, store);
  assert.equal(a2.key, a1.key, 'A 的重试必须沿用 K1');
  assert.ok(a2.reused);
  assert.equal(peek(['user-a'], 'order:B:accept', store), null);
});

await test('#5 明确失败后编辑可用新意图；同账户不同内容不同 key', () => {
  const store = mem();
  const a1 = acquire(['user-a'], 'buy:i1', 'user-a', '/orders', { item_id: 'i1', version: 1 }, store);
  settle(['user-a'], 'buy:i1', a1.key, store);
  const a2 = acquire(['user-a'], 'buy:i1', 'user-a', '/orders', { item_id: 'i1', version: 1 }, store);
  assert.notEqual(a1.key, a2.key);
});

await test('#5 账户隔离：B 不会拿到 A 的未决 key', () => {
  const store = mem();
  const a1 = acquire(['user-a'], 'buy:i1', 'user-a', '/orders', { item_id: 'i1', version: 1 }, store);
  const b1 = acquire(['user-b'], 'buy:i1', 'user-b', '/orders', { item_id: 'i1', version: 1 }, store);
  assert.notEqual(a1.key, b1.key);
});

await test('#5/#1 局域网 HTTP 无 crypto.randomUUID 时回退，key 仍满足后端 [A-Za-z0-9_-]{8,100}', () => {
  const saved = globalThis.crypto;
  Object.defineProperty(globalThis, 'crypto', { value: { getRandomValues: a => { for (let i = 0; i < a.length; i++) a[i] = (i * 37 + 11) & 255; return a; } }, configurable: true, writable: true });
  try {
    for (let i = 0; i < 50; i++) assert.match(newKey(), /^[A-Za-z0-9_-]{8,100}$/);
  } finally { Object.defineProperty(globalThis, 'crypto', { value: saved, configurable: true, writable: true }); }
  Object.defineProperty(globalThis, 'crypto', { value: undefined, configurable: true, writable: true });
  try { assert.match(newKey(), /^[A-Za-z0-9_-]{8,100}$/); } finally { Object.defineProperty(globalThis, 'crypto', { value: saved, configurable: true, writable: true }); }
});

await test('#1 发布/修改请求带 Idempotency-Key，同内容重试同 key', async () => {
  const api = await loadApi();
  const seen = [];
  globalThis.fetch = async (url, init) => { seen.push({ url, key: init.headers['Idempotency-Key'], body: init.body, method: init.method }); return { ok: true, json: async () => ({ id: 'item-1' }) }; };
  const store = mem();
  const body = itemBody({ title: '台灯', category: '宿舍生活', description: '九成新', price: '19.9', image_id: 'asset-1' });
  assert.equal(itemError({ ...body }), '');
  const k1 = acquire(['u'], 'item:new' + JSON.stringify(body), 'u', '/items', body, store);
  const r1 = await api.api('/items', 'POST', body, k1.key);
  const k2 = acquire(['u'], 'item:new' + JSON.stringify(body), 'u', '/items', body, store);
  await api.api('/items', 'POST', body, k2.key);
  assert.equal(seen[0].key, seen[1].key);
  assert.equal(seen[0].key, k1.key);
  assert.equal(r1.id, 'item-1');
});

await test('#1/#5 HTTP 网络错误可被识别为“结果未知”，4xx 为明确失败', async () => {
  const api = await loadApi();
  globalThis.fetch = async () => { throw new TypeError('failed to fetch'); };
  await assert.rejects(api.api('/orders', 'POST', {}, 'k-12345678'), e => e.transport === true && !e.status);
  globalThis.fetch = async () => ({ ok: false, status: 409, json: async () => ({ message: '版本变化' }) });
  await assert.rejects(api.api('/orders', 'POST', {}, 'k-12345678'), e => e.status === 409 && !e.transport);
});

await test('#9 订单详情读取 {order,delivery,reviews}，缺失不伪造', () => {
  const d = orderDetails({ order: { id: 'o1' }, delivery: { actor_id: 's', note: '图书馆东门', created_at: '2026-11-04T13:55:56Z' }, reviews: [{ author_id: 's', score: 5, content: '顺利' }] });
  assert.equal(d.order.id, 'o1');
  assert.equal(d.delivery.note, '图书馆东门');
  assert.equal(d.reviews.length, 1);
  assert.deepEqual(orderDetails(null), { order: null, delivery: null, reviews: [] });
  assert.deepEqual(orderDetails({}), { order: null, delivery: null, reviews: [] });
});

await test('#9 author_id 映射买卖双方名字', () => {
  const order = { id: 'o1', buyer_id: 'b', seller_id: 's', buyer_name: '买方小明', seller_name: '卖方小红' };
  const rows = reviewRows([{ id: 'r1', author_id: 'b', score: 4 }, { id: 'r2', author_id: 's', score: 5 }], order);
  assert.equal(rows[0].authorName, '买方小明');
  assert.equal(rows[0].targetName, '卖方小红');
  assert.equal(rows[1].authorName, '卖方小红');
  assert.equal(rows[1].targetName, '买方小明');
});

await test('#9 本人已评不再显示提交入口（重新打开列表后，服务端已返回评价）', () => {
  const order = { id: 'o1', stage: '已完成', buyer_id: 'b', seller_id: 's' };
  const me = { id: 'b' };
  assert.equal(canReview(order, [], me), true);
  assert.equal(canReview(order, [{ author_id: 's' }], me), true);
  assert.equal(canReview(order, [{ author_id: 'b' }], me), false);
  assert.equal(alreadyReviewed(order, [{ author_id: 'b' }], me), true);
  assert.equal(canReview({ ...order, stage: '待买方确认' }, [], me), false);
});

await test('#9 评价提交成功后本地已评标记在重新读取前仍生效', () => {
  const order = { id: 'o1', stage: '已完成', buyer_id: 'b', seller_id: 's' };
  const marked = { o1: 'b' };
  assert.equal(alreadyReviewed(order, [], { id: 'b' }, marked), true);
  assert.equal(alreadyReviewed(order, [], { id: 's' }, marked), false);
  assert.equal(addReview([{ author_id: 'b', id: 'r1' }], { author_id: 'b', id: 'r1' }).length, 1);
});

await test('买方确认后真实刷新为已完成；卖方只有登记交付且没有改字“完成”入口', () => {
  const seller = { id: 's' }, buyer = { id: 'b' };
  const delivered = { id: 'o1', stage: '待买方确认', seller_id: 's', buyer_id: 'b', disputed: 0 };
  assert.deepEqual(actionsFor(delivered, seller), ['remind']);
  assert.equal(showConfirmReceived(delivered, seller), false);
  assert.deepEqual(actionsFor(delivered, buyer), ['confirm']);
  const done = { ...delivered, stage: '已完成' };
  assert.deepEqual(actionsFor(done, seller), []);
  assert.deepEqual(actionsFor(done, buyer), []);
  assert.deepEqual(actionsFor({ ...delivered, disputed: 1 }, buyer), []);
  assert.deepEqual(actionsFor({ ...delivered, stage: '待交付' }, seller), ['deliver', 'cancel']);
  assert.deepEqual(actionsFor(delivered, { id: 'x' }), []);
});

await test('#8/#12 收藏请求表达目标状态，重试同目标不会反向', async () => {
  const api = await loadApi();
  const calls = [];
  globalThis.fetch = async (url, init) => { calls.push({ url, method: init.method, body: init.body }); return { ok: true, json: async () => ({ ok: true }) }; };
  await api.setFavorite('i1', true);
  await api.setFavorite('i1', true);
  await api.setFavorite('i1', false);
  assert.equal(calls[0].method, 'PUT');
  assert.equal(calls[0].url, '/api/favorites/i1');
  assert.deepEqual(calls.map(c => JSON.parse(c.body).favorite), [true, true, false]);
});

// #12 收藏不可访问条目：后端只给 {id,unavailable:true} 时前端显示明确“暂不可用”，不猜测标题，仍可取消收藏。
await test('#12 收藏不可访问条目显示“暂不可用”占位，可访问条目显示标题价格', () => {
  const hidden = favoriteView({ id: 'item-1', unavailable: true });
  assert.equal(hidden.id, 'item-1');
  assert.equal(hidden.unavailable, true);
  assert.equal(hidden.title, '该物品暂不可用');
  assert.ok(!hidden.title.includes('NOT-APPROVED'), '不露出任何待审/隐藏内容');
  assert.match(hidden.detail, /收藏已保留/);
  const shown = favoriteView({ id: 'item-2', unavailable: false, title: '台灯', price: '25.5', status: '在售' });
  assert.equal(shown.unavailable, false);
  assert.equal(shown.title, '台灯');
  assert.equal(shown.detail, '¥ 25.50 · 在售');
  assert.equal(favoriteView({ id: 'legacy', title: '旧后端可访问', price: null, status: '在售' }).unavailable, false, '旧后端无字段但有标题→可访问');
  assert.equal(favoriteView({ id: 'legacy2', price: '1.00', status: '在售' }).unavailable, true, '缺标题→不可用');
  assert.equal(favoriteView(null).unavailable, true);
  assert.equal(favoriteView(undefined).unavailable, true);
  // 模板：不可用占位显示明确说明，且取消收藏按钮始终可用
  const tpl = appSrc().slice(appSrc().indexOf('我的收藏'), appSrc().indexOf('</section>', appSrc().indexOf('我的收藏')));
  assert.ok(tpl.includes('favoriteView(i).title') && tpl.includes('favoriteView(i).detail'), '收藏卡片使用占位投影');
  assert.ok(tpl.includes('favorite(i,false)'), '不可访问期间仍可取消收藏');
});

await test('聊天 readOnly 来自服务端；enterAction 基本语义', () => {
  assert.equal(enterAction({ key: 'Enter' }), 'send');
  assert.equal(enterAction({ key: 'Enter', shiftKey: true }), 'newline');
  assert.equal(enterAction({ key: 'Enter', isComposing: true }), null);
  assert.equal(enterAction({ key: 'Enter', keyCode: 229 }), null);
  assert.ok(composing({ keyCode: 229 }));
  const src = appSrc();
  assert.ok(src.includes('c.readOnly=data.readOnly!==false'), 'readOnly 只认服务端字段');
  assert.ok(src.includes('/messages/context'), '读取会话上下文接口');
  // #15 卖方发送时 chatState 携带真实咨询人；买方咨询由服务端按认证身份确定，前端不自行强塞 uid
  assert.ok(src.includes('chatQuery(c)'), '读取与发送使用同一上下文参数');
  assert.ok(!src.includes('peer:user.value.id') && !src.includes('peer:user.value?.id'), '不在前端把本人 uid 当作咨询人参数');
});

// 实际按键事件模拟：用 App.vue 中 onEnter 的真实函数体 + textarea 值模型驱动。
function makeComposer() {
  const src = appSrc();
  const start = src.indexOf('function onEnter(e){');
  const block = src.slice(start, src.indexOf('\n}', start) + 2);
  const state = { sends: [], textarea: { value: '' }, chatState: { value: { readOnly: false } } };
  const send = () => { state.sends.push(state.textarea.value); state.textarea.value = ''; };
  const onEnter = new Function('composeAction', 'send', 'chatState', block + '\nreturn onEnter;')(composeAction, send, state.chatState);
  // 浏览器 textarea 默认行为：未 preventDefault 的 Enter 在光标处插入换行
  const key = (init) => {
    const e = { key: 'Enter', type: 'keydown', isComposing: false, keyCode: 13, shiftKey: false, ctrlKey: false, altKey: false, metaKey: false, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; }, ...init };
    onEnter(e);
    if (!e.defaultPrevented && e.key === 'Enter' && !e.isComposing && e.keyCode !== 229) state.textarea.value += '\n';
    return e;
  };
  return { state, key };
}

await test('composeAction：Enter 发送、Shift+Enter 换行、IME 忽略', () => {
  assert.deepEqual(composeAction({ key: 'Enter', type: 'keydown' }), { action: 'send', preventDefault: true });
  assert.deepEqual(composeAction({ key: 'Enter', type: 'keydown', shiftKey: true }), { action: 'newline', preventDefault: false });
  assert.deepEqual(composeAction({ key: 'Enter', type: 'keydown', isComposing: true }), { action: 'ignore', preventDefault: false });
  assert.deepEqual(composeAction({ key: 'Enter', type: 'keydown', keyCode: 229 }), { action: 'ignore', preventDefault: false });
  assert.deepEqual(composeAction({ key: 'a', type: 'keydown' }), { action: 'ignore', preventDefault: false });
});

await test('聊天多行输入：Enter 发送并阻止默认换行', () => {
  const c = makeComposer();
  c.state.textarea.value = '明天东门见';
  const e = c.key({});
  assert.deepEqual(c.state.sends, ['明天东门见']);
  assert.equal(e.defaultPrevented, true, '发送时阻止 textarea 插入换行');
  assert.equal(c.state.textarea.value, '', '发送后清空');
});

await test('聊天多行输入：Shift+Enter 真正插入换行且不发送', () => {
  const c = makeComposer();
  c.state.textarea.value = '第一行';
  const e = c.key({ shiftKey: true });
  assert.deepEqual(c.state.sends, [], '不得发送');
  assert.equal(e.defaultPrevented, false, '不阻止默认');
  assert.equal(c.state.textarea.value, '第一行\n', '换行已插入草稿');
  c.key({ shiftKey: true });
  assert.equal(c.state.textarea.value, '第一行\n\n');
  assert.deepEqual(c.state.sends, []);
});

await test('聊天多行输入：中文输入法 Enter 不误发也不拦截，组合结束后 Enter 才发送', () => {
  const c = makeComposer();
  c.state.textarea.value = 'ni';
  for (const ime of [{ isComposing: true }, { keyCode: 229 }]) {
    const e = c.key(ime);
    assert.deepEqual(c.state.sends, [], 'IME 组合中不发送');
    assert.equal(e.defaultPrevented, false, '不拦截输入法');
  }
  c.state.textarea.value = '你好';
  c.key({ keyCode: 13 });
  assert.deepEqual(c.state.sends, ['你好']);
});

await test('聊天只读时不发送；可写聊天使用多行 textarea', () => {
  const c = makeComposer();
  c.state.chatState.value = { readOnly: true };
  c.state.textarea.value = '已结束';
  c.key({});
  assert.deepEqual(c.state.sends, []);
  const src = appSrc();
  const q = String.fromCharCode(34);
  const formAt = src.indexOf('v-else class=' + q + 'search chat-compose' + q + ' @submit.prevent=' + q + 'send' + q + '><textarea');
  assert.ok(formAt > 0, '可写聊天模板是 textarea');
  const form = src.slice(formAt, src.indexOf('</form>', formAt));
  assert.ok(!form.includes('<input'), '不再是单行 input');
  assert.ok(form.includes('@keydown.enter=' + q + 'onEnter' + q), 'Enter 由 onEnter 处理');
  assert.ok(src.includes('v-if=' + q + 'chatState.value.readOnly' + q), '终态不渲染可写输入框');
});

await test('#11 统计只提交合法时间，统一毫秒 UTC；非法不请求', () => {
  const ok = statsQuery('2026-11-04T21:55:56', '2026-11-05T00:00:00');
  assert.deepEqual(ok.errors, []);
  assert.equal(ok.from, new Date('2026-11-04T21:55:56').toISOString());
  assert.equal(ok.to, new Date('2026-11-05T00:00:00').toISOString());
  assert.match(ok.from, /Z$/);
  assert.deepEqual(statsQuery('', '').errors, []);
  assert.equal(statsQuery('2026-11-04T21:55:56', '').from, new Date('2026-11-04T21:55:56').toISOString());
  assert.equal(statsQuery('2026-11-05T00:00:00', '2026-11-04T00:00:00').ok, false);
  assert.equal(statsQuery('not-a-date', '').ok, false);
});

await test('发布校验与后端一致；提交体字段固定', () => {
  assert.equal(priceError('19.9'), '');
  assert.equal(priceError('19.999'), '价格必须是最多两位小数的正数。');
  assert.equal(priceError('0'), '价格超出范围。');
  const ok = { title: '书', category: '教材书籍', description: '描述', price: '10', image_id: 'a1' };
  assert.equal(itemError(ok), '');
  assert.deepEqual(Object.keys(itemBody(ok)).sort(), ['category', 'description', 'image_id', 'price', 'title']);
  assert.match(itemError({ ...ok, title: '  ' }), /物品名称/);
  assert.match(itemError({ ...ok, image_id: '' }), /图片/);
});

await test('#5 多槽位持久化：同一账户多个未决意图并存', () => {
  const store = mem();
  for (let i = 0; i < 5; i++) acquire(['u'], 'order:O' + i + ':accept', 'u', '/orders/O' + i + '/accept', {}, store);
  const map = readIntents(store, ['u']);
  assert.equal(Object.keys(map).length, 5);
  assert.equal(new Set(Object.values(map).map(v => v.key)).size, 5);
});

await test('#5 5xx/网关错误不释放 key：同内容重试仍用原 key', () => {
  const store = mem();
  const k1 = acquire(['u'], 'buy:i1', 'u', '/orders', { item_id: 'i1', version: 1 }, store);
  assert.equal(settleIfApplied(['u'], 'buy:i1', k1.key, { status: 500 }, store), 'unknown');
  assert.equal(peek(['u'], 'buy:i1', store).key, k1.key, '500 后 key 保留');
  assert.equal(settleIfApplied(['u'], 'buy:i1', k1.key, { status: 502 }, store), 'unknown');
  assert.equal(settleIfApplied(['u'], 'buy:i1', k1.key, { status: 408 }, store), 'unknown');
  assert.equal(settleIfApplied(['u'], 'buy:i1', k1.key, { status: 409 }, store), 'unknown', '冲突可能已提交');
  const k2 = acquire(['u'], 'buy:i1', 'u', '/orders', { item_id: 'i1', version: 1 }, store);
  assert.equal(k2.key, k1.key, '重试沿用原 key');
  assert.equal(k2.reused, true);
});

await test('#5 仅成功或可证明无效果的 4xx 释放 key，之后编辑可获新意图', () => {
  const store = mem();
  for (const status of [400, 401, 403, 404, 413, 415, 422]) {
    const k = acquire(['u'], 'order:O:' + status, 'u', '/o', {}, store);
    assert.equal(settleIfApplied(['u'], 'order:O:' + status, k.key, { status }, store), 'settled');
    assert.equal(peek(['u'], 'order:O:' + status, store), null, status + ' 释放');
  }
  assert.equal(classify('ok'), 'settled');
  assert.equal(classify({ status: 429 }), 'unknown');
  assert.equal(classify({}), 'unknown', '网络错误无 status → 未知');
  assert.equal(classify({ transport: true }), 'unknown');
  const k1 = acquire(['u'], 'buy:i2', 'u', '/orders', { item_id: 'i2', version: 2 }, store);
  settleIfApplied(['u'], 'buy:i2', k1.key, { status: 422 }, store);
  const k2 = acquire(['u'], 'buy:i2', 'u', '/orders', { item_id: 'i2', version: 2 }, store);
  assert.notEqual(k2.key, k1.key, '明确失败后新意图');
});

await test('#4 换号清理旧 scope 的意图表与输入指纹，不触碰无关键', () => {
  const store = mem();
  acquire(['user-a'], 'buy:i1', 'user-a', '/orders', { item_id: 'i1', version: 1 }, store);
  acquire(['anon'], 'buy:i9', 'anon', '/orders', { item_id: 'i9', version: 1 }, store);
  store.setItem('campus:unknown:user-a', JSON.stringify({ slot: 'buy:i1' }));
  store.setItem('theme', 'dark');
  store.setItem('other:user-a', 'keep');
  clearScope(['user-a'], store);
  assert.deepEqual(readIntents(store, ['user-a']), {}, 'A 的意图表已清空');
  assert.equal(store.getItem('campus:unknown:user-a'), null);
  assert.equal(store.getItem('theme'), 'dark', '非本应用键保留');
  assert.equal(store.getItem('other:user-a'), 'keep');
  assert.ok(peek(['anon'], 'buy:i9', store), '其他账户作用域不动');
});

await test('#6 治理详情：争议用 order_id，订单类举报用 object_id，物品举报不打开', () => {
  assert.equal(governanceOrderId('dispute', { order_id: 'o-1' }), 'o-1');
  assert.equal(governanceOrderId('report', { object_type: 'ORDER', object_id: 'o-2' }), 'o-2');
  assert.equal(governanceOrderId('report', { object_type: 'ITEM', object_id: 'i-2' }), '');
  assert.equal(governanceOrderId('report', { object_type: 'ACCOUNT', object_id: 'u' }), '');
  assert.equal(governanceOrderId('dispute', null), '');
});

await test('#6 治理详情只读：不含确认收货/评价/写操作', () => {
  const src = appSrc();
  const body = src.slice(src.indexOf('async function openGovOrder'), src.indexOf('let poll;'));
  assert.ok(body.includes('adminOrderDetails('), '走 GET /admin/orders/{id}/details');
  assert.ok(!/confirm\(|review\(|deliver\(|'POST'/.test(body), '治理详情函数不含写操作');
  const api = readFileSync(join(root, 'src', 'api.js'), 'utf8');
  assert.ok(/export function adminOrderDetails\(id\)\{return api\('\/admin\/orders\/'\+id\+'\/details'\);/.test(api), 'GET 只读');
});

await test('#4 登录/表单弹窗没有背景自动关闭；关闭依赖明确按钮', () => {
  const src = appSrc();
  const login = src.slice(src.indexOf('<div v-if="dialog.value" class="backdrop"'), src.indexOf('<div v-if="chatState.value"'));
  assert.ok(!login.includes('@click.self'), '登录弹窗不得点背景关闭');
  assert.ok(login.includes('@click="dialog.value=null"'), '保留明确返回/关闭按钮');
});

await test('#5 App.vue 不保留“有 status 就释放”的旧判定', () => {
  const src = appSrc();
  assert.ok(src.includes('settleIfApplied('), '统一走可证明无效果判定');
  assert.ok(!/settleFailure|const known=!!e\.status/.test(src), '旧“有 status 即失败释放”逻辑已删除');
});

await test('证据日志：原始 evidence 与 evidence_log 分开，补证按时间升序编号', () => {
  const d = {
    evidence: '原始：已交付',
    evidence_log: [
      { id: 'e1', author_id: 'b', content: '买方补充一', created_at: '2026-11-04T10:00:00Z' },
      { id: 'e2', author_id: 's', content: '卖方补充二', created_at: '2026-11-04T11:00:00Z' },
    ],
  };
  const order = { id: 'o1', buyer_id: 'b', seller_id: 's', buyer_name: '买方小明', seller_name: '卖方小红' };
  const v = evidenceView(d, order, { id: 'b', nickname: '买方小明' });
  assert.equal(v.original, '原始：已交付', '原始证据不被合并或改写');
  assert.equal(v.hasOriginal, true);
  assert.equal(v.count, 2);
  assert.deepEqual(v.supplements.map(x => x.index), [1, 2]);
  assert.deepEqual(v.supplements.map(x => x.content), ['买方补充一', '卖方补充二']);
  assert.deepEqual(v.supplements.map(x => x.author), ['买方小明', '卖方小红']);
  assert.deepEqual(v.supplements.map(x => x.isMine), [true, false]);
  assert.equal(v.supplements[0].created_at, '2026-11-04T10:00:00Z');
});

await test('证据日志：缺失/非数组/空内容不伪造补充；无原始证据标注为空', () => {
  assert.deepEqual(evidenceLog(undefined), []);
  assert.deepEqual(evidenceLog({ evidence_log: 'x' }), []);
  assert.deepEqual(evidenceLog({ evidence_log: [null, { id: 'a', content: '  ' }] }), []);
  const v = evidenceView({ evidence: '', evidence_log: [] }, null, null);
  assert.equal(v.hasOriginal, false);
  assert.equal(v.count, 0);
  assert.deepEqual(v.supplements, []);
});

await test('证据日志：补证人匹配不到只显示编号，不冒造姓名', () => {
  const known = new Map([['b', '买方小明']]);
  assert.equal(authorLabel('b', known), '买方小明');
  assert.equal(authorLabel('zz99887766', known), '用户·zz998877');
  assert.equal(authorLabel('<b>x</b>', known), '用户·未知编号·<b>x</b>', '非法 id 也不冒造姓名');
  assert.equal(authorLabel('', known), '提交人未知');
  const v = evidenceView({ evidence: 'x', evidence_log: [{ id: 'e1', author_id: 'stranger-1', content: 'c' }] }, { buyer_id: 'b', buyer_name: '买方小明' }, { id: 'b' });
  assert.equal(v.supplements[0].author, '用户·stranger');
  assert.ok(!v.supplements[0].author.includes('买方小明'), '不把未知作者冒用为买方');
});

await test('证据日志：原样字符串渲染，全模板不使用 v-html', () => {
  const raw = '<img src=x onerror=alert(1)>';
  const v = evidenceView({ evidence: raw, evidence_log: [{ id: 'e1', author_id: 'b', content: raw }] }, null, null);
  assert.equal(v.original, raw);
  assert.equal(v.supplements[0].content, raw, '原样字符串，交给模板插值转义');
  const tpl = appSrc().slice(appSrc().indexOf('<template>'));
  assert.ok(!tpl.includes('v-html'), '全模板不使用 v-html');
  assert.ok(tpl.includes('{{ e.content }}') && tpl.includes('evidenceFor(d,disputeOrder(d)).original'), '以插值文本渲染证据');
});

await test('我的订单与管理台争议卡都渲染 evidence_log（补证人+时间）', () => {
  const src = appSrc();
  const mine = src.slice(src.indexOf('我的履约争议'), src.indexOf('</article>', src.indexOf('我的履约争议')) + 10);
  assert.ok(mine.includes('原始证据') && mine.includes('evidenceFor(d,disputeOrder(d)).original'), '我的订单显示原始证据');
  assert.ok(mine.includes('evidenceFor(d,disputeOrder(d)).supplements') && mine.includes('{{ e.author }}') && mine.includes('{{ date(e.created_at) }}'), '我的订单显示补证作者与时间');
  const marker = 'ui2.adminTab===' + String.fromCharCode(39) + 'disputes' + String.fromCharCode(39);
  const at = src.indexOf(marker);
  const admin = src.slice(at, src.indexOf('</template>', at));
  assert.ok(admin.includes('d.evidence_log') && admin.includes('userLabel(e.author_id)') && admin.includes('{{ date(e.created_at) }}'), '管理台显示补证作者与时间');
  assert.ok(admin.includes('d.evidence') && admin.includes('原始证据'), '管理台原始证据仍单独可见');
});

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
