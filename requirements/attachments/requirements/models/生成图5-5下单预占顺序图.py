# -*- coding: utf-8 -*-
"""生成图5-5 下单与预占顺序图（PNG + SVG）。

运行：python 生成图5-5下单预占顺序图.py
依据：正文5.5、UC05、BR06、BR10、BR14、BR20；ST-B-08、ST-B-13。
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from 绘图工具 import Diagram, NOTE_FILL, wrap  # noqa: E402

BUYER, UI, ACCOUNT, ITEM, ORDER = 170, 470, 750, 1020, 1300


def build():
    g = Diagram(1500, 1500)

    # 生命线
    for x, label in [(BUYER, "买方"), (UI, "下单界面"), (ACCOUNT, "账户"),
                     (ITEM, "物品"), (ORDER, "订单")]:
        g.lifeline(x, 60, 1270, label, width=200, size=26)

    # 激活期
    g.activation(BUYER, 250, 1255)
    g.activation(UI, 250, 1255)
    g.activation(ACCOUNT, 330, 735)
    g.activation(ITEM, 800, 1115)
    g.activation(ORDER, 490, 1185)

    # 失败与重试的可选片段（框内即结束，不进入后续建单）
    g.frame((80, 345, 1430, 442), "[无会话或越权：M3 返回拒绝，本次请求结束]")
    g.frame((80, 505, 1430, 600), "[已有回执或处理中：M5 返回原结果，本次请求结束]")
    g.frame((80, 665, 1430, 760), "[新交易资格不满足：M7 返回拒绝，本次请求结束]")
    g.frame((80, 805, 1430, 910), "[不可交易或竞争失败：M9 返回拒绝，本次请求结束]")

    # 消息
    g.message(BUYER, UI, 250, "提交下单意图（物品、价格）")
    g.message(UI, ACCOUNT, 330, "核验身份与归属（BR14）")
    g.message(ACCOUNT, UI, 400, "有效／无权（拒绝）", dash=True, above=False)
    g.message(UI, ORDER, 490, "识别同次操作（BR10）")
    g.message(ORDER, UI, 560, "原回执／处理中／无记录", dash=True, above=False)
    g.message(UI, ACCOUNT, 650, "检查双方新交易资格（BR06、BR12）")
    g.message(ACCOUNT, UI, 715, "允许／拒绝", dash=True, above=False)
    g.message(UI, ITEM, 800, "校验公开可交易（BR03）")
    g.message(ITEM, UI, 870, "可交易／竞争失败", dash=True, above=False)
    g.message(UI, ORDER, 950, "建立订单并保存快照（BR20）")
    g.message(ORDER, ITEM, 1020, "按订单成立将物品置为交易中（BR06）")
    g.message(ITEM, ORDER, 1090, "状态已置为交易中", dash=True)
    g.message(ORDER, UI, 1160, "订单标识（待卖方确认）", dash=True)
    g.message(UI, BUYER, 1230, "回执／进度／拒绝原因", dash=True)

    # 失败与约束说明
    g.box((90, 1290, 1420, 1470), "", fill=NOTE_FILL, pad=16, color="#B45309")
    g.text((755, 1322), "失败、重试与并发约束", 24)
    note = ("四个opt框内的返回即本次请求终点：不进入M10，不建立订单、不产生占用，物品状态保持（ST-B-S1、ST-B-S2）。\n"
            "重试：同次操作已有结果或处理中时返回原回执/进度；成功后未收到响应再试，仍返回原订单（BR10）。\n"
            "同时成立：M10—M12 中订单建立与物品转交易中为同一业务结果，中途失败不留下半个占用；并发至多一笔成立（BR06）。")
    y = 1360
    for para in note.split("\n"):
        for line in wrap(para, 21, 1270):
            g.text((112, y), line, 21, anchor="lm")
            y += 30
        y += 4

    return g.save("图5-5_下单预占顺序图")


if __name__ == "__main__":
    png, svg = build()
    print("已生成：", png.name, "、", svg.name)
