// 管理台统计时间：只提交合法的可解析时间，统一为固定毫秒 UTC（左闭右开）。
// 合法时间的解析一致性由后端 #11 修复；前端不以此替代后端对 Z / +08:00 写法的处理。

const ISO_MS = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,3})?)?(Z|[+-]\d{2}:\d{2})?$/;

export function parseTime(value) {
  if (value == null || value === '') return null;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d;
}

// 无时区写法的 datetime-local 表示用户本地时间；统一补齐不带小数秒的秒级 ISO。
export function toIso(value) {
  const d = parseTime(value);
  return d ? d.toISOString() : '';
}

// 返回 { from, to, errors, ok }；errors 非空时调用方不得发请求。
export function statsQuery(from, to) {
  const errors = [];
  const f = parseTime(from), t = parseTime(to);
  if (from !== '' && from != null && !f) errors.push('成交开始时间格式不正确。');
  if (to !== '' && to != null && !t) errors.push('成交截止时间格式不正确。');
  if (f && t && f.getTime() >= t.getTime()) errors.push('成交截止时间必须晚于开始时间。');
  const p = new URLSearchParams();
  if (!errors.length) {
    if (f) p.set('from', toIso(from));
    if (t) p.set('to', toIso(to));
  }
  return { from: p.get('from') || '', to: p.get('to') || '', errors, ok: !errors.length };
}

export function isIso(value) {
  return !!value && ISO_MS.test(value);
}

// 争议结案/处理成功后需要刷新的用户数据；通知与争议结果在其中。
export function refreshAfterManage(kind) {
  if (kind === 'dispute') return ['orders', 'disputes', 'notifications', 'inbox', 'admin'];
  return ['notifications', 'inbox', 'admin'];
}
