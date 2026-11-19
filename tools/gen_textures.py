"""生成模组的像素贴图（欧式中世纪风格，16x16 方块 + 64x64 居民皮肤）。

用法：在项目根目录运行  python tools/gen_textures.py
只依赖 Python 标准库。想换成手绘贴图时，直接覆盖输出目录里的同名 PNG 即可。
"""
import os
import struct
import zlib

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                    "src", "main", "resources", "assets", "townfolk", "textures")


# ---------------------------------------------------------------- 基础工具

def rgb(hex_color):
    h = hex_color.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))


def shade(color, delta):
    return tuple(max(0, min(255, c + delta)) for c in color)


def noise(x, y, seed=0):
    """固定的伪随机明暗抖动，让纯色块有一点颗粒感。"""
    n = ((x * 73856093) ^ (y * 19349663) ^ (seed * 83492791)) & 0xFFFFFFFF
    return (n % 11) - 5


class Canvas:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.px = [[(0, 0, 0, 0)] * w for _ in range(h)]

    def set(self, x, y, color):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = (color[0], color[1], color[2], 255)

    def rect(self, x, y, w, h, color):
        for yy in range(y, y + h):
            for xx in range(x, x + w):
                self.set(xx, yy, color)

    def grain(self, seed=0):
        for y in range(self.h):
            for x in range(self.w):
                r, g, b, a = self.px[y][x]
                if a:
                    d = noise(x, y, seed)
                    self.px[y][x] = (max(0, min(255, r + d)), max(0, min(255, g + d)),
                                     max(0, min(255, b + d)), a)

    def save(self, path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        raw = b"".join(b"\x00" + bytes(c for p in row for c in p) for row in self.px)

        def chunk(tag, data):
            return (struct.pack(">I", len(data)) + tag + data
                    + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

        png = (b"\x89PNG\r\n\x1a\n"
               + chunk(b"IHDR", struct.pack(">IIBBBBB", self.w, self.h, 8, 6, 0, 0, 0))
               + chunk(b"IDAT", zlib.compress(raw, 9))
               + chunk(b"IEND", b""))
        with open(path, "wb") as f:
            f.write(png)


def from_grid(rows, palette, seed=0):
    assert len(rows) == 16 and all(len(r) == 16 for r in rows), "贴图必须是 16x16"
    c = Canvas(16, 16)
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            c.set(x, y, rgb(palette[ch]))
    c.grain(seed)
    return c


# ---------------------------------------------------------------- 方块贴图

BLOCK_PALETTE = {
    "t": "#5b4028", "T": "#6e4f33",          # 木框
    "s": "#8d8d8d", "S": "#6f6f6f",          # 石砖 / 砖缝
    "b": "#2f4a7a", "w": "#e6e6e6",          # 盾徽
    "o": "#a8845a", "O": "#8f6f49",          # 木板
    "p": "#e9d9b0", "k": "#5a4a3a",          # 羊皮纸 / 墨迹
    "r": "#a62a22",                          # 火漆印
}

TOWN_HALL_SIDE = [
    "tttttttttttttttt",
    "tsssSssssssSssst",
    "tsssSssssssSssst",
    "tSSSSSSSSSSSSSSt",
    "tsSssbbbbbbssSst",
    "tsSssbwwwwbssSst",
    "tSSSSbwbbwbSSSSt",
    "tsssSbwwwwbsSsst",
    "tsssSbbwwbbsSsst",
    "tSSSSSbwwbSSSSSt",
    "tsSsssSbbSsssSst",
    "tsSsssSssSsssSst",
    "tSSSSSSSSSSSSSSt",
    "tsssSssssssSssst",
    "tsssSssssssSssst",
    "tttttttttttttttt",
]

TOWN_HALL_TOP = [
    "tttttttttttttttt",
    "tTTTTTTTTTTTTTTt",
    "tTooOooooOooooTt",
    "tTooOooooOooooTt",
    "tToppppppppppoTt",
    "tTOpkkkkkkkkpOTt",
    "tToppppppppppoTt",
    "tTopkkkkkkpppoTt",
    "tToppppppppppoTt",
    "tTOpkkkkkpprpOTt",
    "tTopppppppprpoTt",
    "tToppppppppppoTt",
    "tTooOooooOooooTt",
    "tTooOooooOooooTt",
    "tTTTTTTTTTTTTTTt",
    "tttttttttttttttt",
]

TOWN_HALL_BOTTOM = [
    "tttttttttttttttt",
    "toooooOooooooOot",
    "tooOoooooOooooot",
    "tOOOOOOOOOOOOOOt",
    "toooOooooooOooot",
    "toOooooOoooooOot",
    "tOOOOOOOOOOOOOOt",
    "tooooOooooOoooot",
    "toOoooooOoooooot",
    "tOOOOOOOOOOOOOOt",
    "toooOoooooooOoot",
    "tooooooOooOoooot",
    "tOOOOOOOOOOOOOOt",
    "toOoooOooooooOot",
    "toooooooOoooooot",
    "tttttttttttttttt",
]


def gen_blocks():
    out = os.path.join(ROOT, "block")
    from_grid(TOWN_HALL_SIDE, BLOCK_PALETTE, 1).save(os.path.join(out, "town_hall_side.png"))
    from_grid(TOWN_HALL_TOP, BLOCK_PALETTE, 2).save(os.path.join(out, "town_hall_top.png"))
    from_grid(TOWN_HALL_BOTTOM, BLOCK_PALETTE, 3).save(os.path.join(out, "town_hall_bottom.png"))


# ---------------------------------------------------------------- 居民皮肤

SKIN_TONES = ["#e0b08a", "#c99672", "#f0c8a0", "#a8754f"]
HAIR_COLORS = ["#3b2a1a", "#6b4423", "#c9a24a", "#1e1a18"]
EYE_COLORS = ["#4a3220", "#3a5a8a", "#4a6b3a", "#2e2218"]

LEATHER = rgb("#4a3320")
BUCKLE = rgb("#c9a24a")
BOOT = rgb("#2e2218")
LINEN = rgb("#e6dcc0")
STRAW = rgb("#d9b95c")


class Box:
    """玩家皮肤里一个身体部件的展开图。(u, v) 是左上角，w/h/d 是宽、高、厚。"""

    def __init__(self, canvas, u, v, w, h, d):
        self.c = canvas
        self.faces = {
            "top": (u + d, v, w, d),
            "bottom": (u + d + w, v, w, d),
            "right": (u, v + d, d, h),
            "front": (u + d, v + d, w, h),
            "left": (u + d + w, v + d, d, h),
            "back": (u + d + w + d, v + d, w, h),
        }

    def fill(self, face, color, x=0, y=0, w=None, h=None):
        fx, fy, fw, fh = self.faces[face]
        w = fw - x if w is None else w
        h = fh - y if h is None else h
        self.c.rect(fx + x, fy + y, w, h, color)

    def fill_all(self, color):
        for face in self.faces:
            self.fill(face, color)

    def sides(self, color, y=0, h=None):
        """在四个侧面的同一高度画一圈。"""
        for face in ("right", "front", "left", "back"):
            self.fill(face, color, 0, y, None, h)


def make_skin(job, male, variant, child=False):
    c = Canvas(64, 64)
    skin = rgb(SKIN_TONES[variant])
    hair = rgb(HAIR_COLORS[(variant + (0 if male else 1)) % 4])
    eye = rgb(EYE_COLORS[variant])

    head = Box(c, 0, 0, 8, 8, 8)
    hat = Box(c, 32, 0, 8, 8, 8)
    body = Box(c, 16, 16, 8, 12, 4)
    arms = [Box(c, 40, 16, 4, 12, 4), Box(c, 32, 48, 4, 12, 4)]
    legs = [Box(c, 0, 16, 4, 12, 4), Box(c, 16, 48, 4, 12, 4)]

    # ---- 衣服配色
    if child:
        tunic = rgb("#5f7fa0") if male else rgb("#b0627a")
        trousers = rgb("#5a4a3c")
    elif job == "farmer":
        tunic, trousers = rgb("#6f7f3a"), rgb("#5a4630")
    elif job == "lumberjack":
        tunic, trousers = rgb("#8a3b2a"), rgb("#3f3a33")
    elif job == "clerk":
        tunic, trousers = rgb("#3f5f8a"), rgb("#4a4238")
    else:
        tunic, trousers = rgb("#8c7b66"), rgb("#5a4a3c")

    # ---- 头
    head.fill_all(skin)
    head.fill("bottom", shade(skin, -18))
    head.fill("top", hair)
    head.fill("back", hair)
    side_rows = 8 if not male else 4
    head.fill("right", hair, 0, 0, None, side_rows)
    head.fill("left", hair, 0, 0, None, side_rows)
    head.fill("front", hair, 0, 0, None, 2)
    if not male:                                   # 女性两鬓的长发
        head.fill("front", hair, 0, 2, 1, 5)
        head.fill("front", hair, 7, 2, 1, 5)
    # 五官
    head.fill("front", rgb("#f4f4f4"), 1, 4, 1, 1)
    head.fill("front", eye, 2, 4, 1, 1)
    head.fill("front", eye, 5, 4, 1, 1)
    head.fill("front", rgb("#f4f4f4"), 6, 4, 1, 1)
    head.fill("front", shade(skin, -22), 3, 5, 2, 1)
    mouth = shade(skin, -45) if male else rgb("#b5605a")
    head.fill("front", mouth, 3, 6, 2, 1)
    if male and not child and variant in (1, 3):    # 一部分男性留胡子
        beard = shade(hair, -8)
        head.fill("front", beard, 0, 7, 8, 1)
        head.fill("front", beard, 0, 6, 3, 1)
        head.fill("front", beard, 5, 6, 3, 1)
        head.fill("right", beard, 0, 5, None, 3)
        head.fill("left", beard, 0, 5, None, 3)

    # ---- 帽子（叠加层）。小孩不戴帽子：幼年模型的帽子层会错位
    if not child:
        if job == "farmer" and male:               # 草帽
            hat.fill("top", STRAW)
            hat.sides(STRAW, 0, 2)
            hat.sides(shade(STRAW, -45), 2, 1)
        elif job == "farmer":                      # 头巾
            hat.fill("top", LINEN)
            hat.fill("back", LINEN, 0, 0, None, 6)
            hat.fill("right", LINEN, 0, 0, None, 4)
            hat.fill("left", LINEN, 0, 0, None, 4)
            hat.fill("front", LINEN, 0, 0, None, 1)
        elif job == "lumberjack" and male:         # 毡帽
            cap = rgb("#4f5a34")
            hat.fill("top", cap)
            hat.sides(cap, 0, 2)
        elif job == "lumberjack":                  # 红头巾
            scarf = rgb("#9a3a2e")
            hat.fill("top", scarf)
            hat.sides(scarf, 0, 2)
            hat.fill("back", scarf, 0, 2, None, 3)

    # ---- 身体
    body.fill_all(tunic)
    body.fill("top", shade(tunic, -20))
    body.fill("bottom", shade(tunic, -25))
    body.fill("front", shade(tunic, -25), 0, 0, None, 1)      # 领口
    body.fill("front", skin, 3, 0, 2, 1)
    body.sides(shade(tunic, -12), 9, 3)                        # 腰带以下的下摆
    body.sides(LEATHER, 8, 1)                                  # 腰带
    body.fill("front", BUCKLE, 3, 8, 2, 1)
    if not child and job == "farmer" and male:                 # 胸前的系带
        body.fill("front", LINEN, 3, 1, 2, 3)
    if not child and job == "lumberjack":                      # 皮背心
        vest = rgb("#5a3d24")
        body.fill("front", vest, 0, 0, 2, 8)
        body.fill("front", vest, 6, 0, 2, 8)
        body.fill("back", vest, 0, 0, None, 8)
        body.fill("right", vest, 0, 0, None, 8)
        body.fill("left", vest, 0, 0, None, 8)
    if not child and ((not male and job == "farmer") or job == "clerk"):   # 围裙上半截
        body.fill("front", LINEN, 1, 6, 6, 2)
        body.fill("front", LINEN, 1, 9, 6, 3)

    # ---- 手臂
    sleeve_rows = 6 if (job == "lumberjack" and not child) else 9
    for arm in arms:
        arm.fill_all(skin)
        arm.fill("top", shade(tunic, -15))
        arm.sides(tunic, 0, sleeve_rows)
        arm.sides(shade(tunic, -25), sleeve_rows - 1, 1)       # 袖口

    # ---- 腿
    for i, leg in enumerate(legs):
        if child:
            leg.fill_all(skin)
            leg.sides(trousers, 0, 6)
            leg.sides(BOOT, 10, 2)
        elif male:
            leg.fill_all(trousers)
            leg.sides(BOOT, 9, 3)
        else:                                                  # 长裙
            dress = shade(tunic, -12)
            leg.fill_all(dress)
            leg.sides(shade(dress, -20), 9, 1)
            leg.sides(BOOT, 10, 2)
            if job == "farmer":                                # 围裙下半截
                inner = 0 if i == 0 else 3                     # 两条腿各画靠内侧的三列
                x = 1 if i == 0 else 0
                leg.fill("front", LINEN, x, 0, 3, 5)
        leg.fill("top", shade(trousers, -10))
        leg.fill("bottom", BOOT)

    c.grain(variant * 7 + (3 if male else 5))
    return c


def gen_skins():
    out = os.path.join(ROOT, "entity", "resident")
    for variant in range(4):
        for male in (True, False):
            g = "m" if male else "f"
            for job in ("unemployed", "farmer", "lumberjack", "clerk"):
                make_skin(job, male, variant).save(os.path.join(out, f"{job}_{g}_{variant}.png"))
            make_skin("unemployed", male, variant, child=True).save(
                os.path.join(out, f"child_{g}_{variant}.png"))


if __name__ == "__main__":
    gen_blocks()
    gen_skins()
    print("贴图已生成到", os.path.normpath(ROOT))
