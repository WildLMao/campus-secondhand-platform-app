package cn.campus;

import static cn.campus.Store.p;
import static cn.campus.MarketService.*;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Independent reviewer checks. Only the explicitly configured disposable MySQL test database. */
@SpringBootTest(properties={"spring.datasource.url=${IT_DB_URL}","spring.datasource.username=${IT_DB_USER}",
 "spring.datasource.password=${IT_DB_PASSWORD}","spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
 "app.seed=false","app.bootstrap-admin.username=${IT_ADMIN_USER:it_admin}",
 "app.bootstrap-admin.password=${IT_ADMIN_PASSWORD}","app.upload-dir=./target/mysql-review-uploads"})
@EnabledIfEnvironmentVariable(named="IT_DB_URL",matches=".+campus_test_20261105.*")
@AutoConfigureMockMvc
class RootMySqlReviewIT {
 @Autowired Store db; @Autowired MarketService service;
 @Autowired MockMvc mvc;
 String admin(){return s(db.one("select id from accounts where role='ADMIN' limit 1",p()),"id");}
 String user(String prefix){var u=service.register(p("username",prefix+id().substring(0,8),"password","TestPass123!","nickname",prefix));String uid=s(u,"id");db.exec("update accounts set verified=1 where id=#{p.u}",p("u",uid));return uid;}
 Map<String,Object> item(String seller){String asset=id();db.exec("insert into assets(id,owner_id,kind,media_type,created_at) values(#{p.a},#{p.u},'ITEM','image/png',#{p.t})",p("a",asset,"u",seller,"t",now()));return service.saveItem(seller,null,p("title","ROOT独立验证","description","合成数据","category","教材书籍","price","32.00","image_id",asset),id());}
 Map<String,Object> sales(String from,String to){return (Map<String,Object>)service.statistics(admin(),from,to).get("sales");}
 void salesIs(String from,String to,int count,String amount){var r=sales(from,to);assertEquals(count,((Number)r.get("count")).intValue(),r.toString());assertEquals(0,new BigDecimal(amount).compareTo(new BigDecimal(r.get("amount").toString())),r.toString());}
 @Test void equivalentInstantsAndNanosecondEdgesOnRealMySql(){
  String seller=user("rootstatS"),buyer=user("rootstatB");var i=item(seller);String[] ts={"13:55:56.892773700Z","13:55:56.892Z","21:55:56.892773700+08:00","13:55:56Z","13:55:55.999999999Z","16:00:00.000000001Z"};String[] amounts={"32.00","1.10","0.90","2.00","0.30","3.00"};List<String> ids=new ArrayList<>();
  try{for(int n=0;n<ts.length;n++){String oid=id();ids.add(oid);db.exec("insert into orders(id,item_id,buyer_id,seller_id,title,description,price,buyer_name,seller_name,item_version,stage,created_at,completed_at) values(#{p.o},#{p.i},#{p.b},#{p.s},'ROOT统计','合成历史',#{p.price},'甲','乙',1,'已完成',#{p.t},#{p.done})",p("o",oid,"i",i.get("id"),"b",buyer,"s",seller,"price",amounts[n],"t","2001-11-04T00:00:00Z","done","2001-11-04T"+ts[n]));}
   for(String from:List.of("2001-11-04T13:55:56Z","2001-11-04T13:55:56.000Z","2001-11-04T21:55:56+08:00"))salesIs(from,"2001-11-04T16:00:00Z",4,"36.00");
   salesIs("2001-11-04T13:55:56.892773700Z","2001-11-04T16:00:00Z",2,"32.90");
   salesIs("2001-11-04T13:55:56Z","2001-11-04T13:55:56.892773700Z",2,"3.10");
   salesIs("2001-11-04T16:00:00Z","2001-11-04T16:00:00.000000002Z",1,"3.00");
  }finally{for(String oid:ids)db.exec("delete from orders where id=#{p.o}",p("o",oid));}
 }
 @Test void actualNotificationSqlFailureDoesNotRollbackAndRecovers() throws Exception {
  String seller=user("rootfaultS");var i=item(seller);String iid=s(i,"id");
  db.exec("RENAME TABLE notifications TO root_review_notifications_backup",p());
  try{assertDoesNotThrow(()->service.manage(admin(),"item",iid,p("pass",true,"version",i.get("version"),"reason","ROOT真实SQL故障")));assertEquals("在售",s(service.item(iid,false),"status"));}
  finally{db.exec("RENAME TABLE root_review_notifications_backup TO notifications",p());}
  var session=new MockHttpSession();session.setAttribute("uid",seller);
  mvc.perform(get("/api/notifications").session(session)).andExpect(status().isOk());
  assertTrue(((Number)db.one("select count(*) as c from notifications where user_id=#{p.u} and content like '%物品审核通过%'",p("u",seller)).get("c")).intValue()>0,"真实投递失败后恢复数据库，应能补投，而不只是测试开关跳过投递");
 }
 @Test void repeatedReportDecisionsAreNewFactsNotDuplicateNotificationKeys(){
  String seller=user("rootreportS"),buyer=user("rootreportB");var i=item(seller);service.manage(admin(),"item",s(i,"id"),p("pass",true,"version",i.get("version"),"reason","ROOT审核"));
  service.report(buyer,p("object_type","ITEM","object_id",i.get("id"),"reason","ROOT举报","evidence","合成证据"));
  String rid=s(db.one("select id from reports where author_id=#{p.u}",p("u",buyer)),"id");
  service.manage(admin(),"report",rid,p("reason","首次核查"));
  assertDoesNotThrow(()->service.manage(admin(),"report",rid,p("reason","新增事实后的复核")),"不同处置事实不能共享固定通知事件键而导致回滚");
  assertEquals("新增事实后的复核",s(db.one("select result from reports where id=#{p.r}",p("r",rid)),"result"));
 }
}
