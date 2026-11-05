let csrf='';
export function setCsrf(value){csrf=value;}
export async function api(path,method='GET',body,requestKey){
 const headers={};if(method!=='GET')headers['X-CSRF-Token']=csrf;
 const multipart=body instanceof FormData;if(body!==undefined&&!multipart)headers['Content-Type']='application/json';
 if(requestKey)headers['Idempotency-Key']=requestKey;
 let res;try{res=await fetch('/api'+path,{method,headers,credentials:'same-origin',body:body===undefined?undefined:multipart?body:JSON.stringify(body)});}catch(e){const error=new Error('网络连接中断，请保留本次操作并重试或查询订单。');error.transport=true;throw error;}
 const data=await res.json().catch(()=>({message:'服务器返回异常，请稍后重试。'}));if(!res.ok){const error=new Error(data.message||'请求失败');error.status=res.status;throw error;}return data;
}
// 收藏接口：body 明确目标状态 {favorite:true|false}，不是 toggle。
export function setFavorite(id,favorite){return api('/favorites/'+id,'PUT',{favorite:!!favorite});}
// 订单详情：{order,delivery,reviews}；失败抛出原错误，由调用方决定是否退避到 /reviews。
export function orderDetails(id){return api('/orders/'+id+'/details');}
// 管理台治理订单详情：只读查询，不含任何代收货/代评价动作。
export function adminOrderDetails(id){return api('/admin/orders/'+id+'/details');}
