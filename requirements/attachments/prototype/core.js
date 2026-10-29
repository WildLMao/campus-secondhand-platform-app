/* C-prototype-v1 — local in-memory teaching simulation, no server authorization. */
(function (root) {
  'use strict';
  const TERMINAL = ['已完成','已取消','已拒绝','已异常终止'];
  const clone = x => JSON.parse(JSON.stringify(x));
  const stable = x => JSON.stringify(x, Object.keys(x).sort());
  class Platform {
    constructor() { this.reset(); }
    reset() {
      this.users = {
        buyer:{name:'买方甲',verified:true,restricted:false},
        buyer2:{name:'买方乙',verified:true,restricted:false},
        seller:{name:'卖方',verified:true,restricted:false},
        admin:{name:'管理员',admin:true,verified:true,restricted:false},
        guest:{name:'访问者',verified:false,restricted:false},
        unverified:{name:'未认证用户',verified:false,restricted:false}
      };
      this.items = [{id:'I01',title:'软件工程教材',category:'教材',description:'模拟物品：教材一本，附课程笔记。',price:35,seller:'seller',state:'在售',approved:true,hidden:false,occupation:null}];
      this.orders=[];this.receipts={};this.logs=[];this.notifications=[];this.reports=[];this.sequence=0;
      this.notificationFailure=false;
    }
    item(id){return this.items.find(x=>x.id===id);}
    order(id){return this.orders.find(x=>x.id===id);}
    active(o){return o&&!TERMINAL.includes(o.state);}
    sellable(i){return !!(i&&i.state==='在售'&&i.approved&&!i.hidden&&!i.occupation&&!this.users[i.seller].restricted&&this.users[i.seller].verified);}
    publicItems(){return clone(this.items.filter(i=>this.sellable(i)).map(i=>({id:i.id,title:i.title,category:i.category,description:i.description,price:i.price,sellerNickname:this.users[i.seller].name,verified:this.users[i.seller].verified})));}
    viewOrders(actor){return clone(this.orders.filter(o=>o.buyer===actor||o.seller===actor));}
    summary(){return {completed:this.orders.filter(o=>o.state==='已完成').length,amount:this.orders.filter(o=>o.state==='已完成').reduce((n,o)=>n+o.snapshot.price,0),active:this.orders.filter(o=>this.active(o)).length};}
    log(actor,op,target,result){this.logs.push({no:this.logs.length+1,time:new Date().toISOString(),actor,op,target,result:clone(result)});}
    result(ok,code,message,extra={}){return {ok,code,message,...extra};}
    deny(code,message){return this.result(false,code,message);}
    allowed(actor,op,target){
      const u=this.users[actor];if(!u||actor==='guest')return this.deny('AUTH','请先登录本人账户；角色切换仅为模拟。');
      if(['hide','restrict','resolve','review'].includes(op)&&!u.admin)return this.deny('ROLE','该操作仅限授权管理员。');
      if(['accept','reject','register','confirm','cancel','dispute','resolve','message','remind','report'].includes(op)){
        const o=this.order(target);if(!o)return this.deny('NOT_FOUND','订单不存在。');
        if(op==='resolve')return null;
        if(![o.buyer,o.seller].includes(actor))return this.deny('OWNER','仅本订单当事人可执行此操作。');
        if(['accept','reject','register','remind'].includes(op)&&actor!==o.seller)return this.deny('ROLE','仅本订单卖方可执行此操作。');
        if(op==='confirm'&&actor!==o.buyer)return this.deny('ROLE','仅本订单买方本人可确认收到。');
      }
      return null;
    }
    request(actor,op,target,data={},intent='',mode='normal'){
      const refusal=this.allowed(actor,op,target);
      if(refusal){this.log(actor,op,target,refusal);return refusal;}
      if(!intent.trim())return this.deny('INTENT','请提供本次操作标识；重试须沿用原标识。');
      const key=actor+'|'+intent, fingerprint=op+'|'+target+'|'+stable(data), previous=this.receipts[key];
      if(previous){
        if(previous.fingerprint!==fingerprint)return this.deny('INTENT_CONFLICT','同次操作内容发生变化，请核对；新操作使用新标识。');
        const r=previous.status==='pending'?this.result(true,'PENDING','本次操作处理中，请查询原结果。'):{...clone(previous.result),replay:true};
        if(r.orderId)r.currentState=this.order(r.orderId)?.state;
        this.log(actor,op,target,r);return r;
      }
      this.receipts[key]={fingerprint,status:'pending',actor,op,target,data:clone(data)};
      if(mode==='pending'){const r=this.result(true,'PENDING','模拟处理延迟：尚未建立订单，请稍后查询原结果。');this.log(actor,op,target,r);return r;}
      const r=this.finish(key);
      if(mode==='lost'&&r.ok)return this.result(false,'RESPONSE_LOST','模拟响应丢失。业务可能已成立，请沿用原操作标识查询；不要新建交易。');
      return r;
    }
    finish(key){
      const req=this.receipts[key];if(!req)return this.deny('NOT_FOUND','未找到请求。');
      if(req.status==='done')return clone(req.result);
      const refusal=this.allowed(req.actor,req.op,req.target);
      const result=refusal||this.apply(req.actor,req.op,req.target,req.data);
      req.status='done';req.result=clone(result);this.log(req.actor,req.op,req.target,result);
      this.assertInvariant();return result;
    }
    notify(o,kind){this.notifications.push({orderId:o.id,kind,status:this.notificationFailure?'发送失败':'模拟已发送'});}
    end(o,state,itemState){o.state=state;o.disputed=false;o.chatReadOnly=true;const i=this.item(o.itemId);i.occupation=null;i.state=itemState;}
    apply(actor,op,target,d){
      const u=this.users[actor],i=this.item(target),o=this.order(target);
      const success=(msg,extra={})=>this.result(true,'OK',msg,extra);
      const requiredReason=()=>typeof d.reason==='string'&&!!d.reason.trim();
      if(op==='place'){
        if(u.admin||!u.verified||u.restricted)return this.deny('ELIGIBILITY','当前账户不具备新交易资格。');
        if(!i)return this.deny('NOT_FOUND','物品不存在。');
        if(i.seller===actor)return this.deny('SELF','不能购买本人物品。');
        if(!this.sellable(i))return this.deny('UNAVAILABLE','物品当前不可交易，请刷新列表或查看本人已有订单。');
        const order={id:'O'+String(++this.sequence).padStart(3,'0'),buyer:actor,seller:i.seller,itemId:i.id,state:'待卖方确认',snapshot:{title:i.title,description:i.description,price:i.price,buyer:actor,seller:i.seller},registered:false,disputed:false,disputeStage:null,actualDeliveryReported:false,chatReadOnly:false,messages:[],reminded:false,history:[]};
        this.orders.push(order);i.occupation=order.id;i.state='交易中';this.notify(order,'建立订单');
        return success('订单与预占同时建立。',{orderId:order.id,st:['ST-B-08','ST-B-13']});
      }
      if(op==='hide'){
        if(!i)return this.deny('NOT_FOUND','物品不存在。');
        if(!requiredReason())return this.deny('REASON','治理操作需要填写依据或原因。');
        i.hidden=!!d.hidden;return success('已更新隐藏标记；原生命周期状态保持。',{st:['ST-B-S5']});
      }
      if(op==='restrict'){
        const account=this.users[target];if(!account||account.admin||target==='guest')return this.deny('TARGET','请选择业务账户。');
        if(!requiredReason())return this.deny('REASON','账户处置需要填写依据或原因。');
        account.restricted=!!d.restricted;return success('已更新账户限制；旧订单阶段保持。');
      }
      if(!o)return this.deny('OP','本轮未实现此操作。');
      const item=this.item(o.itemId),oid={orderId:o.id};
      if(op==='report'){
        if(!requiredReason())return this.deny('REASON','请填写反馈对象与理由。');
        if(this.active(o))return this.deny('ROUTE','未结束订单的履约分歧请使用履约争议入口。');
        const report={id:'R'+(this.reports.length+1),orderId:o.id,reporter:actor,reason:d.reason,event:d.event||'订单反馈'};
        report.related=this.reports.filter(x=>x.orderId===o.id&&x.event===report.event).map(x=>x.id);this.reports.push(report);
        return success('已记录终态反馈，不重开订单、不改变成交。',{...oid,reportId:report.id,st:['ST-B-S6']});
      }
      if(op==='message'){
        if(o.chatReadOnly)return this.deny('READ_ONLY','订单已结束，会话只读；需补证请使用举报反馈。');
        if(!d.text||!d.text.trim())return this.deny('EMPTY','请输入沟通内容。');
        o.messages.push({actor,text:d.text});return success('已记录旧单沟通；订单阶段保持。',oid);
      }
      if(!this.active(o))return this.deny('TERMINAL','订单已结束；历史保留，不重复改变成交。');
      if(op==='dispute'){
        if(!requiredReason())return this.deny('REASON','请填写履约分歧或处理申请的理由。');
        if(o.disputed)return this.deny('DISPUTE_EXISTS','争议已受理；本原型不重复建立争议，请保留原记录。');
        o.disputed=true;o.disputeStage=o.state;o.actualDeliveryReported=!!d.delivered||o.actualDeliveryReported;
        o.history.push({type:'争议受理',actor,reason:d.reason,stage:o.state});return success('已受理模拟争议，保留原阶段与占用。',oid);
      }
      if(op==='resolve'){
        if(!o.disputed)return this.deny('NO_DISPUTE','没有待处置的履约争议。');
        if(!requiredReason())return this.deny('REASON','结案需要核查依据，不能仅以沉默为由终止。');
        if(!['continue','terminate'].includes(d.outcome))return this.deny('OUTCOME','管理员不能代买方确认；请选择继续履约或异常终止。');
        const stage=o.state;
        if(d.outcome==='terminate'){
          if(d.evidence!=='verified')return this.deny('EVIDENCE','请先记录已核查无法履约的依据；此处仅模拟核查结果。');
          this.end(o,'已异常终止','交易异常终止');
        } else {o.disputed=false;o.state=o.disputeStage;}
        o.history.push({type:'争议结案',actor,reason:d.reason,outcome:d.outcome});this.notify(o,'争议结案');
        return success(d.outcome==='continue'?'争议结案，恢复受理时原阶段。':'异常终止并释放占用，物品仍不可售。',{...oid,st:d.outcome==='continue'?[]:['ST-B-11',stage==='待买方确认'?'ST-B-20':'ST-B-21']});
      }
      if(op==='cancel'){
        if(!['待卖方确认','待交付'].includes(o.state)||o.registered||o.disputed||o.actualDeliveryReported)return this.deny('CANCEL_BLOCKED','当前不得普通取消；已交付请补登记，有分歧请申请处理。');
        if(d.undelivered!==true||!requiredReason())return this.deny('DECLARATION','请填写原因并明确声明尚未实际交付。');
        const st=o.state==='待交付'?'ST-B-18':'ST-B-16';o.history.push({type:'取消',actor,reason:d.reason,undelivered:true});this.end(o,'已取消','在售');this.notify(o,'取消');
        return success('已取消并释放占用；隐藏和账户限制仍有效。',{...oid,st:[st,'ST-B-09']});
      }
      if(op==='accept'||op==='reject'){
        if(o.state!=='待卖方确认')return this.deny('STAGE','当前阶段不能接受或拒绝订单。');
        if(o.disputed)return this.deny('DISPUTED','争议受理中，请先由管理员结案后继续当前阶段。');
        if(op==='reject'){if(!requiredReason())return this.deny('REASON','请填写拒单原因。');this.end(o,'已拒绝','在售');}
        else o.state='待交付';this.notify(o,op);return success(op==='accept'?'卖方已接单，等待线下交付。':'已拒单，独立限制仍有效。',{...oid,st:op==='accept'?['ST-B-14']:['ST-B-15','ST-B-09']});
      }
      if(op==='register'){
        if(o.state!=='待交付')return this.deny('STAGE','只有待交付订单可以登记；请刷新当前进度。');
        if(o.disputed)return this.deny('DISPUTED','争议受理中，请先结案再补登记。');
        if(d.delivered!==true||!requiredReason())return this.deny('DELIVERY','请声明实际已交付并填写交接说明。');
        o.registered=true;o.actualDeliveryReported=true;o.state='待买方确认';o.history.push({type:'登记',actor,reason:d.reason});this.notify(o,'交付登记');return success('已登记交付，等待买方确认；尚未计入成交。',{...oid,st:['ST-B-17']});
      }
      if(op==='confirm'){
        if(o.state!=='待买方确认'||!o.registered)return this.deny('STAGE','尚未登记交付，不能确认收到。');
        if(d.received!==true)return this.deny('RECEIVED','请由买方本人明确确认已收到物品。');
        this.end(o,'已完成','已成交');o.history.push({type:'收货确认',actor});this.notify(o,'完成');return success('买方本人确认，订单完成与物品成交同时成立。',{...oid,st:['ST-B-19','ST-B-10']});
      }
      if(op==='remind'){
        if(o.state!=='待买方确认')return this.deny('STAGE','仅登记后待买方确认阶段可以提醒。');
        if(o.reminded)return success('本阶段已提醒，不新增通知。',oid);
        o.reminded=true;this.notify(o,'提醒');return success('已发出一次模拟站内提醒；阶段及占用保持。',oid);
      }
      return this.deny('OP','本轮未实现此操作。');
    }
    assertInvariant(){
      for(const i of this.items){const active=this.orders.filter(o=>o.itemId===i.id&&this.active(o));if(active.length>1)throw Error('重复有效订单');if((active[0]?.id||null)!==i.occupation)throw Error('占用与订单不一致');if(active.length&&i.state!=='交易中')throw Error('有效订单的物品未处于交易中');}
      for(const o of this.orders){if(TERMINAL.includes(o.state)&&!o.chatReadOnly)throw Error('终态会话未只读');if(o.state==='已完成'&&(!o.registered||this.item(o.itemId).state!=='已成交'))throw Error('未交付成交');}
      return true;
    }
    snapshot(){return clone({items:this.items,orders:this.orders,users:this.users,notifications:this.notifications,reports:this.reports,receipts:this.receipts,logs:this.logs,summary:this.summary()});}
  }
  if(typeof module!=='undefined'&&module.exports)module.exports={Platform,TERMINAL};
  else root.CampusPrototype={Platform,TERMINAL};
})(typeof globalThis!=='undefined'?globalThis:this);
