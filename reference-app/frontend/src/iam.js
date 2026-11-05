// 中文输入法状态：composition 期间回车属于选词确认，不得当作发送。
export function composing(e) {
  return !!(e && (e.isComposing || e.keyCode === 229));
}

// 返回 'send' | 'newline' | null。Enter 发送；Shift/Ctrl/Alt/Meta+Enter 或输入法组合中换行/忽略。
export function enterAction(e) {
  if (!e || e.key !== 'Enter') return null;
  if (composing(e)) return null;
  if (e.shiftKey || e.ctrlKey || e.altKey || e.metaKey) return 'newline';
  return 'send';
}

// 订单是否为只看不写的终态（后端以阶段判定读写，前端与之对齐）。
export function isTerminal(stage) {
  return ['已完成', '已取消', '已拒绝', '已异常终止'].includes(stage);
}

// 聊天只读以服务端 readOnly 为准；数据未返回前也不允许发送。
export function chatEditable(data) {
  return !!(data && data.readOnly === false);
}

// 多行消息框的 Enter 键处理：返回 { action:'send'|'newline'|'ignore', preventDefault }。
// - Enter（无修饰、非 IME 组合）→ 发送，阻止默认换行；
// - Shift+Enter / Ctrl+Enter / Alt+Enter / Meta+Enter → 换行，不阻止默认（让 textarea 真正插入 \n）；
// - IME 组合中（isComposing 或 keyCode 229）→ 忽略，交给输入法，不误发也不拦截。
export function composeAction(e) {
  if (!e || e.key !== 'Enter' || e.type !== 'keydown') return { action: 'ignore', preventDefault: false };
  if (composing(e)) return { action: 'ignore', preventDefault: false };
  if (e.shiftKey || e.ctrlKey || e.altKey || e.metaKey) return { action: 'newline', preventDefault: false };
  return { action: 'send', preventDefault: true };
}

export function messageList(data) {
  return data && Array.isArray(data.messages) ? data.messages : [];
}
