# -*- coding: utf-8 -*-
"""生成图5-4 订单状态机图（PNG + SVG）。

运行：python 生成图5-4订单状态机图.py
依据：正文5.2—5.3及表5-2的 ST-B-13—ST-B-21、ST-B-S2—ST-B-S4；BR06—BR09、BR15、BR19。
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from 绘图工具 import Diagram, NOTE_FILL, MUTED  # noqa: E402


def build():
    g = Diagram(1500, 1250)

    # 迁移线
    g.line([(532, 165), (600, 165)], arrow=True)                        # ST-B-13
    g.line([(750, 230), (750, 370)], arrow=True)                        # ST-B-14
    g.line([(1000, 165), (1150, 165)], arrow=True)                      # ST-B-15
    g.line([(600, 200), (555, 200), (555, 445), (400, 445)], arrow=True)  # ST-B-16
    g.line([(750, 500), (750, 640)], arrow=True)                        # ST-B-17
    g.line([(600, 490), (400, 490)], arrow=True)                        # ST-B-18
    g.line([(750, 770), (750, 910)], arrow=True)                        # ST-B-19
    g.line([(1000, 705), (1150, 705)], arrow=True)                      # ST-B-20
    g.line([(1000, 470), (1080, 470), (1080, 660), (1150, 660)], arrow=True)  # ST-B-21

    # 状态框
    g.start(520, 165)
    g.state((600, 100, 1000, 230), "待卖方确认")
    g.state((600, 370, 1000, 500), "待交付")
    g.state((600, 640, 1000, 770), "待买方确认")
    g.state((600, 910, 1000, 1040), "已完成")
    g.state((60, 380, 400, 510), "已取消")
    g.state((1150, 100, 1490, 230), "已拒绝")
    g.state((1150, 620, 1490, 780), "已异常终止")

    # 迁移标签
    g.text((466, 212), "ST-B-13\n下单成立", 22, anchor="rm")
    g.text((770, 300), "ST-B-14 卖方接受（BR07）", 23, anchor="lm")
    g.text((1075, 120), "ST-B-15\n卖方拒绝", 22)
    g.text((545, 296), "ST-B-16 普通取消\n（声明未交付）", 22, anchor="rm")
    g.text((750, 575), "ST-B-17 登记交付（BR08）", 23, anchor="lm")
    g.text((410, 540), "ST-B-18\n取消（BR09）", 22, anchor="lm")
    g.text((770, 840), "ST-B-19 买方本人确认收货（BR08）", 23, anchor="lm")
    g.text((1075, 740), "ST-B-20\n异常终止", 22)
    g.text((1096, 505), "ST-B-21 争议核查后异常终止（BR19）\n（待卖方确认阶段同适用）", 22, anchor="lm")
    g.text((800, 1070), "终态：无出边", 23, color=MUTED)

    # 状态保持说明
    from 绘图工具 import wrap  # noqa: E402

    g.box((60, 800, 520, 1180), "", fill=NOTE_FILL, pad=16, color="#B45309")
    g.text((290, 834), "状态保持（不产生成功转换）", 23)
    note = ("待卖方确认：同次重试返回原回执，不新增订单（BR10）。\n"
            "待买方确认：有登记或争议时拒绝普通取消，阶段保持（BR09）。\n"
            "登记前确认、非本人确认、重复确认均拒绝，成交不重复计数（BR08、BR18）。\n"
            "四个终态：举报只追加治理记录，不重开、不改成交（BR15、BR16）。")
    y = 876
    for para in note.split("\n"):
        for line in wrap("· " + para, 20, 420):
            g.text((78, y), line, 20, anchor="lm")
            y += 28
        y += 6

    return g.save("图5-4_订单状态机图")


if __name__ == "__main__":
    png, svg = build()
    print("已生成：", png.name, "、", svg.name)
