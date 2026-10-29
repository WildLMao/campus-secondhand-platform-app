# -*- coding: utf-8 -*-
"""B 角色模型图公共绘图工具：同一份坐标同时输出 PNG 与 SVG。

说明：
1. 只用于需求阶段的分析图，除 Pillow 外无其他依赖。
2. 图内文字统一使用黑体（SimHei），保证中文在 Word/PDF 中可读。
3. 脚本运行后覆盖同目录下同名 PNG/SVG，保证源与导出同版。
"""
from pathlib import Path
import html
import math
import re
from PIL import Image, ImageDraw, ImageFont

ASSET = Path(__file__).resolve().parent
_CANDIDATES = [
    Path(r"C:\Windows\Fonts\simhei.ttf"),
    Path(r"C:\Windows\Fonts\msyh.ttc"),
    Path(r"C:\Windows\Fonts\simsun.ttc"),
]
FONT_PATH = next((p for p in _CANDIDATES if p.exists()), None)
if FONT_PATH is None:  # 环境缺字体时明确报错，不静默降级
    raise SystemExit("未找到可用的中文字体，请修改 绘图工具.py 中的 FONT_PATH。")

STROKE = "#334155"
INK = "#111827"
MUTED = "#475569"
STATE_FILL = "#EEF2F7"
ACTION_FILL = "#E8F0FE"
NOTE_FILL = "#FFF8DC"
HEAD_FILL = "#DBEAFE"
WHITE = "#FFFFFF"

_FONTS = {}
_MEASURE = ImageDraw.Draw(Image.new("RGB", (4, 4)))


def _font(size):
    size = max(int(size), 20)
    if size not in _FONTS:
        _FONTS[size] = ImageFont.truetype(str(FONT_PATH), size)
    return _FONTS[size]


def text_width(text, size):
    return _MEASURE.textlength(str(text), font=_font(size))


def wrap(text, size, max_w):
    """按像素宽度折行；ASCII 连续串（编号、数字）不拆开，保留显式换行。"""
    out = []
    for raw in str(text).split("\n"):
        cur = ""
        for token in re.findall(r"[A-Za-z0-9.\-+/]+|.", raw):
            if cur and text_width(cur + token, size) > max_w:
                out.append(cur)
                cur = token
            else:
                cur += token
        out.append(cur)
    return out


class Diagram:
    def __init__(self, w, h):
        self.w, self.h = int(w), int(h)
        self.im = Image.new("RGB", (self.w, self.h), "white")
        self.d = ImageDraw.Draw(self.im)
        self.svg = []

    # ------------------------------------------------------------------ 线条
    def _polyline(self, pts, color, width, dash=False):
        self.d.line(pts, fill=color, width=width, joint="curve")
        dash_attr = ' stroke-dasharray="10 7"' if dash else ""
        points = " ".join("%d,%d" % (x, y) for x, y in pts)
        self.svg.append(
            '<polyline points="%s" fill="none" stroke="%s" stroke-width="%d"%s/>'
            % (points, color, width, dash_attr)
        )

    def arrow_head(self, p0, p1, color=STROKE, size=16):
        ang = math.atan2(p1[1] - p0[1], p1[0] - p0[0])
        tri = [
            p1,
            (p1[0] - size * math.cos(ang - 0.42), p1[1] - size * math.sin(ang - 0.42)),
            (p1[0] - size * math.cos(ang + 0.42), p1[1] - size * math.sin(ang + 0.42)),
        ]
        self.d.polygon(tri, fill=color)
        self.svg.append(
            '<polygon points="%s" fill="%s"/>'
            % (" ".join("%d,%d" % (x, y) for x, y in tri), color)
        )

    def line(self, pts, arrow=True, dash=False, color=STROKE, width=3, head=16):
        pts = [(int(x), int(y)) for x, y in pts]
        self._polyline(pts, color, width, dash)
        if arrow:
            self.arrow_head(pts[-2], pts[-1], color, head)

    def diamond_end(self, pts, filled=True, color=STROKE):
        """组合关系菱形，画在折线起点。"""
        (x0, y0), (x1, y1) = pts[0], pts[1]
        ang = math.atan2(y1 - y0, x1 - x0)
        length, half = 26, 14
        cx, cy = x0 + length / 2 * math.cos(ang), y0 + length / 2 * math.sin(ang)
        pts_d = [
            (x0, y0),
            (cx - half * math.sin(ang), cy + half * math.cos(ang)),
            (x0 + length * math.cos(ang), y0 + length * math.sin(ang)),
            (cx + half * math.sin(ang), cy - half * math.cos(ang)),
        ]
        self.d.polygon(pts_d, fill=color if filled else WHITE, outline=color)
        self.svg.append(
            '<polygon points="%s" fill="%s" stroke="%s" stroke-width="2"/>'
            % (
                " ".join("%d,%d" % (x, y) for x, y in pts_d),
                color if filled else WHITE,
                color,
            )
        )

    # ------------------------------------------------------------------ 文字
    def text(self, xy, t, size=26, anchor="mm", color=INK, gap=8, bg=False):
        x, y = xy
        lines = str(t).split("\n")
        lh = size + gap
        f = _font(size)
        for i, line in enumerate(lines):
            yy = y + (i - (len(lines) - 1) / 2) * lh
            if bg:
                wd = text_width(line, size)
                if anchor == "mm":
                    x0 = x - wd / 2
                elif anchor == "lm":
                    x0 = x
                else:
                    x0 = x - wd
                self.d.rectangle(
                    (x0 - 7, yy - size * 0.66, x0 + wd + 7, yy + size * 0.66), fill="white"
                )
            self.d.text((x, yy), line, font=f, fill=color, anchor=anchor)
            if anchor == "mm":
                sx, ta = x, "middle"
            elif anchor == "lm":
                sx, ta = x, "start"
            else:
                sx, ta = x, "end"
            self.svg.append(
                '<text x="%d" y="%d" text-anchor="%s" font-family="SimHei,Microsoft YaHei,sans-serif" '
                'font-size="%d" fill="%s">%s</text>'
                % (sx, yy + size * 0.34, ta, size, color, html.escape(line))
            )

    # ------------------------------------------------------------------ 形状
    def box(self, xy, label="", size=26, fill=ACTION_FILL, dashed=False, radius=12,
            pad=18, color=STROKE, width=3):
        x1, y1, x2, y2 = xy
        if dashed:
            self.d.rectangle(xy, fill=fill, outline=color, width=width)
            self.svg.append(
                '<rect x="%d" y="%d" width="%d" height="%d" fill="%s" stroke="%s" '
                'stroke-width="%d" stroke-dasharray="10 7"/>'
                % (x1, y1, x2 - x1, y2 - y1, fill, color, width)
            )
        else:
            self.d.rounded_rectangle(xy, radius=radius, fill=fill, outline=color, width=width)
            self.svg.append(
                '<rect x="%d" y="%d" width="%d" height="%d" rx="%d" fill="%s" stroke="%s" stroke-width="%d"/>'
                % (x1, y1, x2 - x1, y2 - y1, radius, fill, color, width)
            )
        if label:
            lines = wrap(label, size, (x2 - x1) - 2 * pad)
            self.text(((x1 + x2) / 2, (y1 + y2) / 2), "\n".join(lines), size, gap=6)
        return label

    def diamond(self, cx, cy, w, h, label, size=24, fill=STATE_FILL, color=STROKE):
        pts = [(cx, cy - h / 2), (cx + w / 2, cy), (cx, cy + h / 2), (cx - w / 2, cy)]
        self.d.polygon(pts, fill=fill, outline=color)
        self.d.line(pts + [pts[0]], fill=color, width=3)
        self.svg.append(
            '<polygon points="%s" fill="%s" stroke="%s" stroke-width="3"/>'
            % (" ".join("%d,%d" % (x, y) for x, y in pts), fill, color)
        )
        lines = wrap(label, size, w * 0.58)
        self.text((cx, cy), "\n".join(lines), size, gap=4)

    def state(self, xy, label, size=28, fill=STATE_FILL):
        """UML 状态：圆角框。"""
        self.box(xy, label, size=size, fill=fill, radius=26, pad=16)

    def start(self, cx, cy, r=14, color=STROKE):
        self.d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=color)
        self.svg.append('<circle cx="%d" cy="%d" r="%d" fill="%s"/>' % (cx, cy, r, color))

    def end(self, cx, cy, r=18, color=STROKE):
        self.d.ellipse((cx - r, cy - r, cx + r, cy + r), fill="white", outline=color, width=3)
        self.d.ellipse((cx - r + 5, cy - r + 5, cx + r - 5, cy + r - 5), fill=color)
        self.svg.append(
            '<circle cx="%d" cy="%d" r="%d" fill="white" stroke="%s" stroke-width="3"/>'
            % (cx, cy, r, color)
        )
        self.svg.append('<circle cx="%d" cy="%d" r="%d" fill="%s"/>' % (cx, cy, r - 5, color))

    def class_box(self, xy, name, attrs, name_size=30, attr_size=25):
        """领域类：名称栏 + 属性栏（业务操作在正文表3-3说明）。"""
        x1, y1, x2, y2 = xy
        head = name_size + 24
        self.d.rectangle(xy, fill=WHITE, outline=STROKE, width=3)
        self.d.rectangle((x1, y1, x2, y1 + head), fill=HEAD_FILL, outline=STROKE, width=3)
        self.svg.append(
            '<rect x="%d" y="%d" width="%d" height="%d" fill="%s" stroke="%s" stroke-width="3"/>'
            % (x1, y1, x2 - x1, y2 - y1, WHITE, STROKE)
        )
        self.svg.append(
            '<rect x="%d" y="%d" width="%d" height="%d" fill="%s" stroke="%s" stroke-width="3"/>'
            % (x1, y1, x2 - x1, head, HEAD_FILL, STROKE)
        )
        self.text(((x1 + x2) / 2, y1 + head / 2), name, name_size)
        lh = attr_size + 16
        y = y1 + head + 16
        for attr in attrs:
            for line in wrap(attr, attr_size, (x2 - x1) - 34):
                self.text((x1 + 16, y + attr_size / 2), line, attr_size, anchor="lm", gap=4)
                y += lh
        return y

    # ------------------------------------------------------------------ 顺序图
    def lifeline(self, x, top, bottom, label, width=210, size=26):
        self.box((x - width / 2, top, x + width / 2, top + 74), label, size=size, fill=WHITE, pad=8)
        self.line([(x, top + 74), (x, bottom)], arrow=False, dash=True, width=2)
        return top + 74

    def activation(self, x, y1, y2, w=12):
        self.d.rectangle((x - w / 2, y1, x + w / 2, y2), fill="#CBD5E1", outline=STROKE, width=2)
        self.svg.append(
            '<rect x="%d" y="%d" width="%d" height="%d" fill="#CBD5E1" stroke="%s" stroke-width="2"/>'
            % (x - w / 2, y1, w, y2 - y1, STROKE)
        )

    def message(self, x1, x2, y, label, size=24, dash=False, above=True):
        """同步消息（实线实心箭头）或返回消息（虚线）。"""
        self.line([(x1, y), (x2, y)], arrow=True, dash=dash, width=3, head=15)
        cx = (x1 + x2) / 2
        self.text((cx, y - 26 if above else y + 26), label, size, gap=4, bg=True)

    def self_message(self, x, y, label, size=24, w=64, h=54):
        pts = [(x, y), (x + w, y), (x + w, y + h), (x + 18, y + h)]
        self.line(pts, arrow=True, width=3, head=15)
        self.text((x + w / 2 + 10, y + h + 26), label, size, gap=4)

    def note(self, xy, label, size=24, fill=NOTE_FILL, pad=16, color="#B45309"):
        self.box(xy, label, size=size, fill=fill, pad=pad, color=color)
        self.svg.append(
            '<rect x="%d" y="%d" width="%d" height="%d" fill="none" stroke="%s" stroke-width="2"/>'
            % (xy[0] + 8, xy[1] + 8, xy[2] - xy[0] - 16, xy[3] - xy[1] - 16, color)
        )

    def frame(self, xy, text, tag="opt", size=21, color="#64748B"):
        """UML 组合片段框：无填充，左上角带标签，用于标出可选/失败出口。"""
        x1, y1, x2, y2 = xy
        self.d.rectangle(xy, outline=color, width=2)
        self.svg.append(
            '<rect x="%d" y="%d" width="%d" height="%d" fill="none" stroke="%s" stroke-width="2"/>'
            % (x1, y1, x2 - x1, y2 - y1, color)
        )
        w = text_width(tag + "  " + text, size) + 26
        h = size + 16
        pts = [(x1, y1), (x1 + w, y1), (x1 + w, y1 + h), (x1 + w - 16, y1 + h), (x1, y1 + h)]
        self.d.polygon(pts, fill="white", outline=color)
        self.d.line(pts + [pts[0]], fill=color, width=2)
        self.svg.append(
            '<polygon points="%s" fill="white" stroke="%s" stroke-width="2"/>'
            % (" ".join("%d,%d" % (x, y) for x, y in pts), color)
        )
        self.text((x1 + 12, y1 + h / 2), tag + "  " + text, size, anchor="lm", gap=4)

    # ------------------------------------------------------------------ 输出
    def save(self, name):
        png = ASSET / (name + ".png")
        self.im.save(png)
        svg = ASSET / (name + ".svg")
        svg.write_text(
            '<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d">'
            '<rect width="100%%" height="100%%" fill="white"/>%s</svg>'
            % (self.w, self.h, self.w, self.h, "".join(self.svg)),
            encoding="utf-8",
        )
        return png, svg
