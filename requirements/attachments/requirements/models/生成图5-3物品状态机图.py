# -*- coding: utf-8 -*-
"""生成图5-3 物品状态机图（PNG + SVG）。

运行：python 生成图5-3物品状态机图.py
依据：正文5.2—5.3及表5-2的 ST-B-01—ST-B-12；BR02—BR05、BR12、BR13、BR19。
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from 绘图工具 import Diagram, NOTE_FILL, MUTED  # noqa: E402


def build():
    g = Diagram(1500, 1400)

    # 迁移线（先画线，后画状态框）
    g.line([(250, 116), (250, 250)], arrow=True)                       # ST-B-01
    g.line([(420, 300), (1050, 300)], arrow=True)                      # ST-B-02
    g.line([(1050, 345), (420, 345)], arrow=True)                      # ST-B-05
    g.line([(250, 380), (250, 520)], arrow=True)                       # ST-B-03
    g.line([(350, 520), (350, 380)], arrow=True)                       # ST-B-04
    g.line([(1220, 380), (1220, 520)], arrow=True)                     # ST-B-06
    g.line([(1050, 560), (1010, 560), (1010, 170), (360, 170), (360, 250)], arrow=True)  # ST-B-07
    g.line([(1390, 350), (1440, 350), (1440, 965), (1390, 965)], arrow=True)  # ST-B-08
    g.line([(1050, 950), (990, 950), (990, 410), (1100, 410), (1100, 380)], arrow=True)  # ST-B-09
    g.line([(1220, 1030), (1220, 1180)], arrow=True)                   # ST-B-10
    g.line([(1050, 1000), (420, 1000)], arrow=True)                    # ST-B-11
    g.line([(80, 930), (40, 930), (40, 220), (200, 220), (200, 250)], arrow=True)  # ST-B-12

    # 状态框
    g.start(250, 100)
    g.state((80, 250, 420, 380), "待审")
    g.state((80, 520, 420, 650), "审核退回")
    g.state((80, 900, 420, 1030), "交易异常终止\n（待核实重发）")
    g.state((1050, 250, 1390, 380), "在售")
    g.state((1050, 520, 1390, 650), "已撤下")
    g.state((1050, 900, 1390, 1030), "交易中")
    g.state((1050, 1180, 1390, 1310), "已成交")

    # 迁移标签
    g.text((272, 140), "ST-B-01 卖方提交新物品（BR02）", 23, anchor="lm")
    g.text((735, 272), "ST-B-02 审核通过（BR03）", 23)
    g.text((735, 372), "ST-B-05 关键修改后重审（BR04）", 23)
    g.text((236, 450), "ST-B-03\n退回并记录原因", 23, anchor="rm")
    g.text((372, 450), "ST-B-04\n修改后显式重提", 23, anchor="lm")
    g.text((1430, 420), "ST-B-06 撤下（BR05）", 23, anchor="rm")
    g.text((690, 178), "ST-B-07 显式重新发布并审核（BR05）", 23)
    g.text((1420, 700), "ST-B-08\n下单成立（BR06）", 23, anchor="rm")
    g.text((1040, 762), "ST-B-09 取消或拒单后\n重检（BR07、09、13）", 23, anchor="lm")
    g.text((1240, 1105), "ST-B-10 买方本人确认\n（BR08）", 23, anchor="lm")
    g.text((735, 1032), "ST-B-11 争议核查后异常终止（BR19）", 23)
    g.text((62, 780), "ST-B-12\n核实归属后重发", 23, anchor="lm")
    g.text((1220, 1345), "终态：无出边", 23, color=MUTED)

    # 图内说明（左对齐逐行绘制）
    from 绘图工具 import wrap  # noqa: E402

    g.box((470, 690, 980, 930), "", fill=NOTE_FILL, pad=16, color="#B45309")
    g.text((725, 722), "限制标记（不是状态）", 24)
    note = ("隐藏标记：物品不公开交易，解除后按BR03重判。\n"
            "卖方账户受限：阻止新下单与新发布（BR12）。\n"
            "两者不改变本图状态，也不抹除成交事实（BR13）。\n"
            "撤下、终止与成交均保留历史（BR16）。")
    y = 762
    for para in note.split("\n"):
        for line in wrap(para, 20, 470):
            g.text((488, y), line, 20, anchor="lm")
            y += 28
        y += 6

    return g.save("图5-3_物品状态机图")


if __name__ == "__main__":
    png, svg = build()
    print("已生成：", png.name, "、", svg.name)
