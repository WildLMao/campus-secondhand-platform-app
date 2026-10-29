let csrf='';
export function setCsrf(value){csrf=value;}
export async function api(path,method='GET',body,requestKey){
 const headers={};if(method!=='GET')headers['X-CSRF-Token']=csrf;
 const multipart=body instanceof FormData;if(body!==undefined&&!multipart)headers['Content-Type']='application/json';
 if(requestKey)headers['Idempotency-Key']=requestKey;
 let res;try{res=await fetch('/api'+path,{method,headers,credentials:'same-origin',body:body===undefined?undefined:multipart?body:JSON.stringify(body)});}catch(e){throw new Error('网络连接中断，请保留本次操作并重试或查询订单。');}
 const data=await res.json().catch(()=>({message:'服务器返回异常，请稍后重试。'}));if(!res.ok){const error=new Error(data.message||'请求失败');error.status=res.status;throw error;}return data;
}
