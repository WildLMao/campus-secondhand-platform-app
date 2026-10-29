from pathlib import Path
import math
from PIL import Image,ImageDraw,ImageFont
ASSET=Path(__file__).resolve().parent
FONT=r'C:\Windows\Fonts\simhei.ttf'
class Diagram:
 def __init__(self,w,h):
  self.w=w; self.h=h; self.im=Image.new('RGB',(w,h),'white');self.d=ImageDraw.Draw(self.im);self.svg=[]
 def line(self,pts,arrow=False,dash=False):
  self.d.line(pts,fill='#334155',width=3)
  self.svg.append('<polyline points="'+ ' '.join(f'{x},{y}' for x,y in pts)+'" fill="none" stroke="#334155" stroke-width="3"'+(' stroke-dasharray="9 6"' if dash else '')+'/>')
  if arrow:
   (x0,y0),(x,y)=pts[-2:]; a=math.atan2(y-y0,x-x0);p=[(x,y),(x-15*math.cos(a-.45),y-15*math.sin(a-.45)),(x-15*math.cos(a+.45),y-15*math.sin(a+.45))]
   self.d.polygon(p,fill='#334155');self.svg.append('<polygon points="'+' '.join(f'{x},{y}' for x,y in p)+'" fill="#334155"/>')
 def box(self,xy,label='',size=28,fill='#F1F5F9',ellipse=False):
  x1,y1,x2,y2=xy
  if ellipse:
   self.d.ellipse(xy,fill=fill,outline='#334155',width=3);self.svg.append(f'<ellipse cx="{(x1+x2)/2}" cy="{(y1+y2)/2}" rx="{(x2-x1)/2}" ry="{(y2-y1)/2}" fill="{fill}" stroke="#334155" stroke-width="3"/>')
  else:
   self.d.rectangle(xy,fill=fill,outline='#334155',width=3);self.svg.append(f'<rect x="{x1}" y="{y1}" width="{x2-x1}" height="{y2-y1}" fill="{fill}" stroke="#334155" stroke-width="3"/>')
  if label:self.text(((x1+x2)/2,(y1+y2)/2),label,size)
 def text(self,pt,t,size=28):
  import html
  size=max(size,30)
  font=ImageFont.truetype(FONT,size);ls=t.split('\n');x,y=pt
  for i,l in enumerate(ls):
   yy=y+(i-(len(ls)-1)/2)*(size+10);self.d.text((x,yy),l,font=font,fill='#111827',anchor='mm')
   self.svg.append(f'<text x="{x}" y="{yy+size*.34}" text-anchor="middle" font-family="SimHei,Microsoft YaHei,sans-serif" font-size="{size}" fill="#111827">{html.escape(l)}</text>')
 def actor(self,x,y,label):
  self.d.ellipse((x-20,y-65,x+20,y-25),outline='#334155',width=3);self.svg.append(f'<circle cx="{x}" cy="{y-45}" r="20" fill="white" stroke="#334155" stroke-width="3"/>')
  for pts in [[(x,y-25),(x,y+45)],[(x-40,y),(x+40,y)],[(x,y+45),(x-35,y+85)],[(x,y+45),(x+35,y+85)]]:self.line(pts)
  self.text((x,y+118),label,27)
 def save(self,name):
  self.im.save(ASSET/(name+'.png'));(ASSET/(name+'.svg')).write_text(f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.w}" height="{self.h}" viewBox="0 0 {self.w} {self.h}"><rect width="100%" height="100%" fill="white"/>'+''.join(self.svg)+'</svg>',encoding='utf-8')
g=Diagram(1600,1560)
g.box((260,45,1370,1500),'',fill='white')
g.text((815,90),'校园二手交易与物品流转平台',34)
g.actor(110,420,'访问者')
g.actor(110,1030,'校园用户')
g.actor(1490,620,'管理员')
# 参与关联使用无箭头实线，先画线后画用例。
g.line([(150,420),(205,420),(205,210),(550,210)])
g.line([(150,420),(350,510)])
g.line([(150,1030),(225,1030),(225,210),(550,210)])
g.line([(150,1030),(205,1030),(205,355),(550,355)])
g.line([(1450,620),(1405,620),(1405,210),(1050,210)])
g.line([(1450,620),(1418,620),(1418,355),(1050,355)])
g.text((1205,180),'仅校园认证审核',30)
g.text((405,320),'举报／争议交互',30)
g.box((550,160,1050,260),'UC01\n注册、校园认证与会话',30,ellipse=True)
g.box((550,305,1050,405),'UC10\n审核、举报与争议处置',30,ellipse=True)
items=[('UC02','检索与浏览物品'),('UC03','收藏与交易咨询'),('UC04','发布与维护物品'),('UC05','下单并预占物品'),('UC06','确认交易与登记交付'),('UC07','取消交易'),('UC08','评价交易'),('UC09','举报违规')]
for i,(uc,label) in enumerate(items):
    y=510+119*i
    g.line([(150,1030),(350,y)])
    g.box((350,y-43,850,y+43),uc+'\n'+label,30,ellipse=True)
for y,uc,label in [(665,'UC11','限制与恢复账户'),(900,'UC12','查看业务统计')]:
    g.line([(1450,620),(1320,y)])
    g.box((930,y-50,1320,y+50),uc+'\n'+label,30,ellipse=True)
g.text((1110,1190),'校园用户在具体交易中\n承担买方或卖方身份；\nUC10的普通用户仅参与\n本人举报、争议与补证，\n不取得管理员处置权限。',30)
g.save('图2-1_UML用例图')
