'use strict';
const app=new CampusPrototype.Platform();let currentTab='market',intentCounter=1,pendingAction=null,lastRequests={};
const $=id=>document.getElementById(id), esc=x=>String(x??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const actor=()=>$('actor').value;
function feedback(r){$('feedback').textContent=(r.replay?'原操作回执：':'')+r.message+(r.orderId?` 订单 ${r.orderId}`:'')+(r.currentState?` · 当前${r.currentState}`:'');$('feedback').className=r.ok?'':'error';}
function nextIntent(){$('intent').value='C-'+String(++intentCounter).padStart(3,'0');}
function render(){
 document.querySelectorAll('[data-tab]').forEach(b=>b.classList.toggle('active',b.dataset.tab===currentTab));
 const who=app.users[actor()];let html='';
 if(currentTab==='market'){
   const items=app.publicItems();html='<div class="intro"><div><span class="tag">校园二手 · 单件交易</span><h1>让好物继续被使用</h1><p class="muted">查看描述与价格，确认后发起一笔交易。</p></div><span class="muted">'+items.length+' 件可交易物品</span></div>';
   html+=items.length?'<div class="grid">'+items.map(i=>`<article class="card"><div class="book"><b>软件工程<br>学习笔记</b><span>知识流转<br>校园同行</span></div><span class="tag">${esc(i.category)}</span><h2>${esc(i.title)}</h2><p>${esc(i.description)}</p><p class="muted">${esc(i.sellerNickname)} · ${i.verified?'校园认证':''}</p><div class="row"><strong class="price">¥ ${i.price.toFixed(2)}</strong><button class="primary" data-op="place" data-target="${i.id}">确认下单</button></div></article>`).join('')+'</div>':'<div class="empty"><strong>暂无可交易物品</strong><p>物品可能已被预占、隐藏或完成交易。已有交易请到“我的订单”查看。</p><button data-go="orders">查看我的订单</button></div>';
 }else if(currentTab==='orders'){
   const orders=app.viewOrders(actor());html=`<div class="intro"><div><h1>我的订单</h1><p class="muted">${esc(who.name)}${who.restricted?' · 账户受限，旧单必要处理权保留':''}</p></div><span>${orders.length} 笔记录</span></div>`;
   html+=orders.length?orders.map(o=>{
     const seller=o.seller===actor(),terminal=!app.active(o);let actions=[];
     if(seller&&o.state==='待卖方确认')actions.push(['accept','接受订单'],['reject','拒绝订单']);
     if(seller&&o.state==='待交付')actions.push(['register','登记交付']);
     if(!seller&&o.state==='待买方确认')actions.push(['confirm','确认收到']);
     if(['待卖方确认','待交付'].includes(o.state))actions.push(['cancel','申请取消']);
     if(!terminal)actions.push(['message','订单沟通'],['dispute','申请争议处理']);
     if(seller&&o.state==='待买方确认')actions.push(['remind','提醒买方']);
     if(terminal)actions.push(['report','举报反馈']);
     return `<article class="card"><div class="row"><h2>${esc(o.snapshot.title)}</h2><span class="tag">${esc(o.state)}</span></div><p class="muted">订单 ${o.id} · ${seller?'我是卖方':'我是买方'}${o.disputed?' · 争议已受理，保留原阶段':''}</p><div class="steps">${['待卖方确认','待交付','待买方确认',terminal?o.state:'已完成'].map(s=>`<span class="${s===o.state?'current':''}">${s}</span>`).join('')}</div><dl class="meta"><dt>约定价格</dt><dd>¥ ${o.snapshot.price.toFixed(2)}</dd><dt>成立时描述</dt><dd>${esc(o.snapshot.description)}</dd><dt>交付登记</dt><dd>${o.state==='已完成'?'已登记且买方已确认收到':o.state==='已异常终止'?(o.registered?'原交付登记保留；订单已异常终止':'未登记交付；订单已异常终止'):o.registered?'已登记，等待买方确认':'未登记'}</dd><dt>沟通状态</dt><dd>${o.chatReadOnly?'只读，历史保留':'仅本订单双方可沟通'}</dd></dl>${o.messages.length?'<div class="messages">'+o.messages.map(m=>`<p>${esc(app.users[m.actor].name)}：${esc(m.text)}</p>`).join('')+'</div>':''}<div class="actions">${actions.map(([op,label])=>`<button data-op="${op}" data-target="${o.id}">${label}</button>`).join('')}</div></article>`;
   }).join(''):'<div class="empty"><strong>还没有本人的订单</strong><p>其他人的私有交易不会显示在这里。</p><button data-go="market">去发现物品</button></div>';
 }else if(!who.admin){html='<div class="empty"><strong>当前身份无管理权限</strong><p>治理操作仅供授权管理员执行。</p></div>';}
 else{
   html='<div class="intro"><div><h1>治理与订单处置</h1><p class="muted">每次处置需要说明依据。管理员不能代买方确认收到。</p></div></div>';
   html+=`<article class="card"><h2>物品与账户</h2><p>教材：${app.item('I01').hidden?'隐藏':'未隐藏'} · 卖方：${app.users.seller.restricted?'受限':'正常'} · 买方甲：${app.users.buyer.restricted?'受限':'正常'}</p><div class="actions"><button data-op="hide" data-target="I01">${app.item('I01').hidden?'解除隐藏':'隐藏物品'}</button><button data-op="restrict" data-target="seller">${app.users.seller.restricted?'恢复卖方':'限制卖方'}</button><button data-op="restrict" data-target="buyer">${app.users.buyer.restricted?'恢复买方甲':'限制买方甲'}</button></div></article>`;
   html+=app.orders.filter(o=>o.disputed).map(o=>`<article class="card"><h2>履约争议 · ${o.id}</h2><p>受理阶段：${esc(o.disputeStage)}；当前占用保留。</p><p>${esc(o.history.filter(x=>x.type==='争议受理').at(-1)?.reason)}</p><button data-op="resolve" data-target="${o.id}" class="primary">核查并处置</button></article>`).join('');
   html+='<p class="muted">'+app.reports.length+' 条终态反馈已保留独立回执；本原型未实现完整举报审核工作台。</p>';
 }
 $('content').innerHTML=html;
 $('stateSummary').textContent=`模拟状态观察：物品 ${app.item('I01').state}；有效订单 ${app.summary().active}；完成 ${app.summary().completed} 笔；成交金额 ¥ ${app.summary().amount}。`;
 $('trace').textContent=JSON.stringify(app.logs.slice(-6),null,2);
}
function openAction(op,target){
 pendingAction={op,target};const labels={place:'确认购买意图',accept:'接受订单',reject:'拒绝订单',register:'登记线下交付',confirm:'确认已经收到',cancel:'申请普通取消',dispute:'申请履约争议处理',resolve:'核查争议并结案',hide:'变更物品展示',restrict:'变更账户限制',message:'订单专用沟通',remind:'提醒买方',report:'终态订单反馈'};
 $('dialogTitle').textContent=labels[op];$('dialogHelp').textContent=op==='place'?'下单将建立订单并预占单件物品；不发生在线支付。':op==='cancel'?'仅未实际交付、无登记、无受理争议且阶段允许时可取消。':op==='confirm'?'请由本订单买方确认真实收到。管理员不能代确认。':'请核对当前对象、阶段和操作含义后提交。';
 const noReason=['place','accept','confirm','remind'].includes(op);$('reasonLabel').hidden=noReason;$('reason').value='';$('reason').required=!noReason;
 $('declarationLabel').hidden=!['cancel','register','confirm'].includes(op);$('declaration').checked=false;
 $('declarationText').textContent=op==='cancel'?'我声明尚未实际交付物品':op==='register'?'我声明已经实际交付物品':'我本人确认已收到物品';
 $('outcomeLabel').hidden=op!=='resolve';$('evidenceLabel').hidden=op!=='resolve';$('outcome').value='continue';$('evidence').checked=false;
 $('deliveredLabel').hidden=op!=='dispute';$('delivered').checked=false;
 let hint=$('dialogFeedback');if(!hint){hint=document.createElement('p');hint.id='dialogFeedback';hint.setAttribute('role','alert');$('dialogHelp').after(hint);}hint.textContent='';
 $('actionDialog').showModal();
}
document.addEventListener('click',e=>{const b=e.target.closest('button');if(!b)return;if(b.dataset.tab){currentTab=b.dataset.tab;render();}if(b.dataset.go){currentTab=b.dataset.go;render();}if(b.dataset.op)openAction(b.dataset.op,b.dataset.target);});
$('back').onclick=()=>$('actionDialog').close();
$('actionForm').onsubmit=e=>{
 e.preventDefault();const {op,target}=pendingAction;let data={};
 if(!['place','accept','confirm','remind'].includes(op))data.reason=$('reason').value;
 if(op==='cancel')data.undelivered=$('declaration').checked;
 if(op==='register')data.delivered=$('declaration').checked;
 if(op==='confirm')data.received=$('declaration').checked;
 if(op==='dispute')data.delivered=$('delivered').checked;
 if(op==='resolve'){data.outcome=$('outcome').value;data.evidence=$('evidence').checked?'verified':'pending';}
 if(op==='hide')data.hidden=!app.item(target).hidden;
 if(op==='restrict')data.restricted=!app.users[target].restricted;
 if(op==='message')data={text:$('reason').value};
 app.notificationFailure=$('notifyFail').checked;
 const mode=$('mode').value;lastRequests[actor()]={op,target,data,intent:$('intent').value};
 const r=app.request(actor(),op,target,data,$('intent').value,mode);feedback(r);$('dialogFeedback').textContent=r.ok?'':r.message;
 if(r.ok&&r.code!=='PENDING'){$('actionDialog').close();if(op==='place')currentTab='orders';nextIntent();}
 else if(['PENDING','RESPONSE_LOST'].includes(r.code))$('actionDialog').close();
 else nextIntent();
 render();
};
$('actor').onchange=()=>{nextIntent();render();};$('newIntent').onclick=nextIntent;
$('finish').onclick=()=>{const keys=Object.keys(app.receipts).filter(k=>app.receipts[k].status==='pending');for(const k of keys)feedback(app.finish(k));if(!keys.length)feedback({ok:true,message:'没有挂起请求。'});render();};
$('reset').onclick=()=>{app.reset();lastRequests={};intentCounter=0;nextIntent();$('actor').value='buyer';$('mode').value='normal';$('notifyFail').checked=false;currentTab='market';feedback({ok:true,message:'已重置为初始模拟数据。'});render();};
const retry=document.createElement('button');retry.id='retry';retry.textContent='查询/重试本人上一操作';$('finish').before(retry);retry.onclick=()=>{const req=lastRequests[actor()];if(!req){feedback({ok:false,message:'当前模拟身份没有上一操作。'});return;}const r=app.request(actor(),req.op,req.target,req.data,req.intent);feedback(r);if(r.orderId)currentTab='orders';render();};
$('export').onclick=()=>{const blob=new Blob([JSON.stringify({version:'C-prototype-v1.1',exportedAt:new Date().toISOString(),scope:'仅本地模拟；导出不自动构成人工走查或独立复核',...app.snapshot()},null,2)],{type:'application/json'});const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='C-原型走查记录.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);};
render();

