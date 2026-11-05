// 未决操作意图：仅为服务端幂等请求生成/查找可重复的 Idempotency-Key。
// Key 只是操作标识，不是安全凭据；直接用于 Idempotency-Key 头（[A-Za-z0-9_-]）。

const KEY_RE = /^[A-Za-z0-9_-]{8,100}$/;

function entropy() {
  const c = globalThis.crypto;
  if (c && typeof c.getRandomValues === 'function') {
    const b = new Uint8Array(16);
    c.getRandomValues(b);
    return Array.from(b, x => x.toString(16).padStart(2, '0')).join('');
  }
  // 无 getRandomValues 时用多次随机数 + 计数拼出 32 位十六进制，降低相撞概率（仅作操作标识）。
  let s = '';
  let seq = (Math.random() * 0xffffff) | 0;
  while (s.length < 32) {
    seq = (seq + 1) >>> 0;
    s += Math.random().toString(16).slice(2).padEnd(8, '0') + seq.toString(16).padStart(6, '0');
  }
  return s.slice(0, 32);
}

// 局域网 HTTP 非安全上下文中 crypto.randomUUID 不存在，必须回退；回退值不用于鉴权。
export function newKey() {
  try {
    const c = globalThis.crypto;
    if (c && typeof c.randomUUID === 'function') return c.randomUUID();
  } catch (e) { /* 非安全上下文，走回退 */ }
  const e = entropy();
  return 'k-' + Date.now().toString(36) + '-' + e.slice(0, 8) + '-' + e.slice(8, 16) + '-' + e.slice(16, 24) + '-' + e.slice(24, 32);
}

// 请求内容指纹：同时含身份、路径和体，账户或内容不同即视为不同意图。
export function fingerprint(actor, path, body) {
  return JSON.stringify([actor || '', path, body || {}]);
}

function scopeKey(scopes) {
  return 'campus:intent:' + (scopes || []).join('|');
}

// 存储适配：浏览器 sessionStorage；不可用时退化为内存，调用方用返回值写回自身状态。
export function storage() {
  try {
    const s = globalThis.sessionStorage;
    const probe = 'campus:probe';
    s.setItem(probe, '1');
    s.removeItem(probe);
    return s;
  } catch (e) {
    const m = new Map();
    return { getItem: k => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: k => m.delete(k) };
  }
}

export function readIntents(store, scopes) {
  try {
    const v = JSON.parse(store.getItem(scopeKey(scopes)) || '{}');
    return v && typeof v === 'object' ? v : {};
  } catch (e) {
    return {};
  }
}

export function writeIntents(store, scopes, map) {
  store.setItem(scopeKey(scopes), JSON.stringify(map || {}));
}

// 查找同内容未决意图的 key，没有则创建并持久化（按操作槽位，不同订单/内容互不覆盖）。
export function acquire(scopes, slot, actor, path, body, store = storage()) {
  const map = readIntents(store, scopes);
  const fp = fingerprint(actor, path, body);
  const hit = map[slot];
  if (hit && hit.fp === fp && KEY_RE.test(hit.key)) return { key: hit.key, reused: true, map };
  map[slot] = { key: newKey(), fp, path, at: Date.now() };
  writeIntents(store, scopes, map);
  return { key: map[slot].key, reused: false, map };
}

// 响应已明确 → 该意图完成，不再复用 key；未决意图保留。
export function settle(scopes, slot, key, store = storage()) {
  const map = readIntents(store, scopes);
  if (map[slot] && map[slot].key === key) {
    delete map[slot];
    writeIntents(store, scopes, map);
  }
}

export function peek(scopes, slot, store = storage()) {
  return readIntents(store, scopes)[slot] || null;
}

// 可证明“未生效”的 4xx：请求在进入业务前被拒绝（鉴权、请求体/格式、对象不存在、参数不符）。
// 5xx、408/409/429 以及其余 4xx 都可能已在服务端提交，只能视为结果未知并保留原 key。
const NOT_APPLIED = new Set([400, 401, 403, 404, 413, 415, 422]);

// 返回 'settled' | 'unknown'。只有成功与 NOT_APPLIED 才释放 key。
export function classify(result) {
  if (result === 'ok') return 'settled';
  const status = result && result.status;
  if (Number.isInteger(status) && NOT_APPLIED.has(status)) return 'settled';
  return 'unknown';
}

export function settleIfApplied(scopes, slot, key, result, store = storage()) {
  const kind = classify(result);
  if (kind === 'settled') settle(scopes, slot, key, store);
  return kind;
}

// 身份变化时清理旧账户的意图表与输入指纹；只删本应用前缀的键。
export function clearScope(scopes, store = storage()) {
  const key = scopeKey(scopes);
  ['unknown', ...(scopes || []).map(x => 'unknown:' + x)].forEach(k => {
    try { store.removeItem('campus:' + k); } catch (e) { /* 忽略 */ }
  });
  try { store.removeItem(key); } catch (e) { /* 忽略 */ }
  const win = globalThis.window;
  if (win && win.sessionStorage && win.sessionStorage !== store) {
    try { win.sessionStorage.removeItem(key); } catch (e) { /* 跨标签页副本 */ }
  }
}
