"""生成三种货架方块和货物立体模型的全部资源文件。

用法：在项目根目录运行  python tools/gen_blocks.py
输出：方块状态、方块模型、物品模型、贴图、掉落表、合成配方、挖掘工具标签，以及摆在货架上的货物模型。
模型都用长方体拼成，单位是 1/16 格。货架的正面朝南（+z），方块状态里按朝向旋转。
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_textures as tex  # noqa: E402

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources")
ASSETS = os.path.join(RES, "assets", "townfolk")
DATA = os.path.join(RES, "data")
FACES = ("north", "south", "east", "west", "up", "down")


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")


def element(box, texture):
    x1, y1, z1, x2, y2, z2 = box
    faces = {face: {"texture": "#" + texture} for face in FACES}
    # 超出一格范围的长方体（摊位的柱子和布棚）要自己写贴图坐标，不然游戏按位置推算出来的坐标会越界，显示成紫黑格
    if min(box) < 0 or max(box) > 16:
        sizes = {"north": (x2 - x1, y2 - y1), "south": (x2 - x1, y2 - y1), "east": (z2 - z1, y2 - y1),
                 "west": (z2 - z1, y2 - y1), "up": (x2 - x1, z2 - z1), "down": (x2 - x1, z2 - z1)}
        for face, (w, h) in sizes.items():
            faces[face]["uv"] = [0, 0, round(min(16, w), 2), round(min(16, h), 2)]
    return {"from": [x1, y1, z1], "to": [x2, y2, z2], "faces": faces}


# ---------------------------------------------------------------- 贴图

def wood(path, base, seed, plank_rows=4):
    """木头：横向的木纹，每隔几行一道板缝。"""
    c = tex.Canvas(16, 16)
    color = tex.rgb(base)
    for y in range(16):
        row = tex.shade(color, -22 if y % plank_rows == plank_rows - 1 else (tex.noise(0, y, seed) * 2))
        for x in range(16):
            knot = -14 if tex.noise(x // 3, y // 2, seed + 5) > 3 else 0
            c.set(x, y, tex.shade(row, knot))
    c.grain(seed)
    c.save(path)


def plain(path, base, seed, speckle=None):
    """纯色加颗粒，给货物用。speckle 是零星的深色斑点。"""
    c = tex.Canvas(16, 16)
    c.rect(0, 0, 16, 16, tex.rgb(base))
    if speckle:
        for y in range(16):
            for x in range(16):
                if tex.noise(x, y, seed + 9) == 5:
                    c.set(x, y, tex.rgb(speckle))
    c.grain(seed)
    c.save(path)


def gen_coin():
    """金币：一枚带暗边的金色圆片，中间压一个方孔印。"""
    c = tex.Canvas(16, 16)
    gold, rim, shine, mark = tex.rgb("#e2b83e"), tex.rgb("#a8802a"), tex.rgb("#f6dc7a"), tex.rgb("#b8902e")
    for y in range(16):
        for x in range(16):
            d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
            if d <= 5.6:
                c.set(x, y, rim if d > 4.5 else (shine if (x + y) < 13 and d > 2.6 else gold))
    c.rect(7, 6, 2, 4, mark)
    c.rect(6, 7, 4, 2, mark)
    c.save(os.path.join(ASSETS, "textures", "item", "coin.png"))
    write(os.path.join(ASSETS, "models", "item", "coin.json"),
          {"parent": "minecraft:item/generated", "textures": {"layer0": "townfolk:item/coin"}})
    nugget = {"item": "minecraft:gold_nugget"}
    write(os.path.join(DATA, "townfolk", "recipe", "coin.json"),
          {"type": "minecraft:crafting_shapeless", "category": "misc", "ingredients": [nugget], "result": {"id": "townfolk:coin", "count": 1}})
    write(os.path.join(DATA, "townfolk", "recipe", "gold_nugget_from_coin.json"),
          {"type": "minecraft:crafting_shapeless", "category": "misc", "ingredients": [{"item": "townfolk:coin"}],
           "result": {"id": "minecraft:gold_nugget", "count": 1}})


# 收银台：下半截和柜台一样，台面上有天平、账本、钱箱和放钱币的托盘。正面朝南（+z）。
REGISTER = [
    ((0, 0, 2, 2, 10, 4), "frame"), ((14, 0, 2, 16, 10, 4), "frame"),
    ((0, 0, 12, 2, 10, 14), "frame"), ((14, 0, 12, 16, 10, 14), "frame"),
    ((2, 0, 3, 14, 10, 13), "plank"),                                       # 台身
    ((0, 10, 0, 16, 12, 16), "top"),                                        # 台面
    ((9, 12, 3, 15, 16, 10), "frame"), ((8.6, 16, 2.6, 15.4, 17, 10.4), "plank"), ((11, 17, 6, 13, 17.3, 7), "iron"),   # 钱箱
    ((9, 12, 11, 15, 12.6, 15), "frame"),                                   # 托盘
    ((4.6, 12, 9.5, 8.2, 12.5, 14.5), "leather"), ((4.9, 12.5, 9.8, 7.9, 12.9, 14.2), "parch"),                         # 账本
    ((3.2, 12, 4.2, 5.8, 12.6, 6.8), "iron"), ((4.1, 12.6, 5.1, 4.9, 18, 5.9), "iron"), ((0.8, 17.4, 5.2, 8.2, 18, 5.8), "iron"),   # 天平
    ((0.4, 14.4, 4.3, 2.8, 14.9, 6.7), "iron"), ((6.2, 15.4, 4.3, 8.6, 15.9, 6.7), "iron"),
]
# 托盘里的钱币，收的钱越多摆得越多
COIN_PILES = [
    ((9.6, 12.6, 11.6, 11, 13.4, 13), "gold"),
    ((11.4, 12.6, 11.8, 12.8, 13.6, 13.2), "gold"), ((10.4, 12.6, 13.4, 11.8, 13.2, 14.6), "gold"),
    ((13.1, 12.6, 11.6, 14.5, 15, 13), "gold"), ((12.4, 12.6, 13.5, 13.8, 13.8, 14.7), "gold"), ((9.6, 13.4, 11.6, 11, 14.4, 13), "gold"),
]
COINS_SHOWN = (0, 1, 3, 6)          # 四档：空、少量、一些、很多


def gen_register():
    block = os.path.join(ASSETS, "textures", "block")
    plain(os.path.join(block, "register_gold.png"), "#e2b83e", 51, "#b8902e")
    plain(os.path.join(block, "register_iron.png"), "#808088", 52)
    plain(os.path.join(block, "register_parch.png"), "#e9d9b0", 53)
    plain(os.path.join(block, "register_leather.png"), "#7a3a2c", 54)
    textures = dict(BLOCK_TEXTURES)
    textures.update({k: "townfolk:block/register_" + k for k in ("gold", "iron", "parch", "leather")})
    variants = {}
    for level, shown in enumerate(COINS_SHOWN):
        boxes = REGISTER + COIN_PILES[:shown]
        used = {t for _, t in boxes} | {"particle"}
        name = "cash_register" if level == 0 else "cash_register_coins%d" % level
        write(os.path.join(ASSETS, "models", "block", name + ".json"), {
            "parent": "minecraft:block/block",
            "ambientocclusion": False,
            "textures": {k: v for k, v in textures.items() if k in used},
            "elements": [element(box, t) for box, t in boxes],
        })
        for facing, y in (("south", 0), ("west", 90), ("north", 180), ("east", 270)):
            variant = {"model": "townfolk:block/" + name}
            if y:
                variant["y"] = y
            variants["coins=%d,facing=%s" % (level, facing)] = variant
    write(os.path.join(ASSETS, "blockstates", "cash_register.json"), {"variants": variants})
    write(os.path.join(ASSETS, "models", "item", "cash_register.json"), {"parent": "townfolk:block/cash_register_coins2"})
    write(os.path.join(DATA, "townfolk", "loot_table", "blocks", "cash_register.json"), {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "townfolk:cash_register"}],
                   "conditions": [{"condition": "minecraft:survives_explosion"}]}],
    })
    write(os.path.join(DATA, "townfolk", "recipe", "cash_register.json"), {
        "type": "minecraft:crafting_shaped", "category": "misc", "pattern": ["PGP", "S S", "PPP"],
        "key": {"P": {"tag": "minecraft:planks"}, "S": {"item": "minecraft:stick"}, "G": {"item": "minecraft:gold_nugget"}},
        "result": {"id": "townfolk:cash_register", "count": 1},
    })


def gen_textures():
    block = os.path.join(ASSETS, "textures", "block")
    wood(os.path.join(block, "shelf_frame.png"), "#5b4028", 11)
    wood(os.path.join(block, "shelf_plank.png"), "#a8845a", 12)
    wood(os.path.join(block, "shelf_top.png"), "#be9a6e", 13)
    plain(os.path.join(block, "stall_cloth_red.png"), "#b0342c", 14)
    plain(os.path.join(block, "stall_cloth_white.png"), "#ece4d0", 15)
    goods = {
        "potato": ("#c9a25a", "#8a6a34"), "carrot": ("#e27e24", "#b85e14"), "leaf": ("#4f8a34", "#3a6a26"),
        "beet": ("#8a2a4a", "#5e1a32"), "straw": ("#dcbc54", "#b8963a"), "tie": ("#8a6a34", None),
        "bread": ("#c9964e", "#a8743a"), "crumb": ("#e6c88a", None), "apple": ("#ba342c", "#8e241e"),
        "stem": ("#5b4028", None), "sack": ("#b8a47a", "#8e7c58"), "seed": ("#6a8a3a", "#4a6a2a"),
        "clay": ("#96604a", None), "twig": ("#8a6a44", "#6a4e30"),
    }
    for i, (name, (base, speckle)) in enumerate(goods.items()):
        plain(os.path.join(block, "goods_%s.png" % name), base, 30 + i, speckle)


# ---------------------------------------------------------------- 货架方块

# 货架有两格高：下半格和上半格各两层搁板，上半格多一块顶板
SHELF_LOWER = [
    ((0, 0, 0, 16, 16, 1), "frame"),            # 背板
    ((0, 0, 1, 1, 16, 12), "frame"),            # 左侧板
    ((15, 0, 1, 16, 16, 12), "frame"),          # 右侧板
    ((1, 0, 1, 15, 1, 13), "plank"),            # 这半格的底层搁板
    ((1, 7, 1, 15, 8, 12), "plank"),            # 这半格的中层搁板
]
SHELF_UPPER = SHELF_LOWER[:3] + [
    ((1, 0, 1, 15, 1, 12), "plank"),
    ((1, 7, 1, 15, 8, 12), "plank"),
    ((0, 15, 0, 16, 16, 13), "top"),            # 顶板
]

COUNTER = [
    ((1, 0, 1, 3, 10, 3), "frame"), ((13, 0, 1, 15, 10, 3), "frame"),       # 后面两条腿
    ((1, 0, 13, 3, 10, 15), "frame"), ((13, 0, 13, 15, 10, 15), "frame"),   # 前面两条腿
    ((3, 0, 1, 13, 10, 2), "plank"),                                        # 背板
    ((2, 3, 2, 14, 4, 14), "plank"),                                        # 下层搁板
    ((0, 10, 0, 16, 12, 16), "top"),                                        # 台面
]

STALL = [
    ((0, 0, 0, 2, 28, 2), "frame"), ((14, 0, 0, 16, 28, 2), "frame"),       # 后柱
    ((0, 0, 14, 2, 26, 16), "frame"), ((14, 0, 14, 16, 26, 16), "frame"),   # 前柱（矮一点，棚子往前倾）
    ((2, 0, 1, 14, 9, 2), "plank"), ((1, 0, 2, 2, 9, 14), "plank"),         # 柜台围板
    ((14, 0, 2, 15, 9, 14), "plank"), ((2, 0, 14, 14, 9, 15), "plank"),
    ((0, 9, 0, 16, 11, 16), "top"),                                         # 台面
] + [
    # 布棚：八条红白相间的布条，从后往前一级一级低下来
    ((-1, 28 - i * 0.3, -1 + i * 2.25, 17, 29 - i * 0.3, 1.25 + i * 2.25), "cloth_red" if i % 2 == 0 else "cloth_white")
    for i in range(8)
]

BLOCK_TEXTURES = {
    "particle": "townfolk:block/shelf_plank",
    "frame": "townfolk:block/shelf_frame",
    "plank": "townfolk:block/shelf_plank",
    "top": "townfolk:block/shelf_top",
    "cloth_red": "townfolk:block/stall_cloth_red",
    "cloth_white": "townfolk:block/stall_cloth_white",
}

# 物品栏里显示的样子。摊位有两格高，缩小一点才放得进格子
GUI_SMALL = {"gui": {"rotation": [30, 225, 0], "translation": [0, -3, 0], "scale": [0.4, 0.4, 0.4]}}


def gen_tall_shelf():
    """两格高的货架：上下两半各一个模型，物品栏里显示的是两半拼起来的样子，只有下半格掉落。"""
    textures = {k: BLOCK_TEXTURES[k] for k in ("particle", "frame", "plank", "top")}
    for half, boxes in (("lower", SHELF_LOWER), ("upper", SHELF_UPPER)):
        write(os.path.join(ASSETS, "models", "block", "shelf_%s.json" % half), {
            "parent": "minecraft:block/block",
            "ambientocclusion": False,
            "textures": {k: v for k, v in textures.items() if k in {t for _, t in boxes} | {"particle"}},
            "elements": [element(box, t) for box, t in boxes],
        })
    stacked = SHELF_LOWER + [((x1, y1 + 16, z1, x2, y2 + 16, z2), t) for (x1, y1, z1, x2, y2, z2), t in SHELF_UPPER]
    write(os.path.join(ASSETS, "models", "item", "shelf.json"), {
        "parent": "minecraft:block/block",
        "textures": textures,
        "elements": [element(box, t) for box, t in stacked],
        "display": GUI_SMALL,
    })
    old = os.path.join(ASSETS, "models", "block", "shelf.json")
    if os.path.exists(old):
        os.remove(old)
    variants = {}
    for facing, y in (("south", 0), ("west", 90), ("north", 180), ("east", 270)):
        for half in ("lower", "upper"):
            variant = {"model": "townfolk:block/shelf_" + half}
            if y:
                variant["y"] = y
            variants["facing=%s,half=%s" % (facing, half)] = variant
    write(os.path.join(ASSETS, "blockstates", "shelf.json"), {"variants": variants})
    write(os.path.join(DATA, "townfolk", "loot_table", "blocks", "shelf.json"), {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [{
            "type": "minecraft:item", "name": "townfolk:shelf",
            "conditions": [{"condition": "minecraft:block_state_property", "block": "townfolk:shelf", "properties": {"half": "lower"}}],
        }], "conditions": [{"condition": "minecraft:survives_explosion"}]}],
    })


def gen_shelf_blocks():
    gen_tall_shelf()
    for name, boxes, display in (("counter", COUNTER, None), ("market_stall", STALL, GUI_SMALL)):
        used = {t for _, t in boxes} | {"particle"}
        model = {
            "parent": "minecraft:block/block",
            # 这些模型里有很多凹进去和伸出格子外的部分，开着环境光遮蔽会把搁板里面和布棚底下算成全黑
            "ambientocclusion": False,
            "textures": {k: v for k, v in BLOCK_TEXTURES.items() if k in used},
            "elements": [element(box, t) for box, t in boxes],
        }
        if display:
            model["display"] = display
        write(os.path.join(ASSETS, "models", "block", name + ".json"), model)
        write(os.path.join(ASSETS, "models", "item", name + ".json"), {"parent": "townfolk:block/" + name})
        ref = "townfolk:block/" + name
        write(os.path.join(ASSETS, "blockstates", name + ".json"), {"variants": {
            "facing=south": {"model": ref},
            "facing=west": {"model": ref, "y": 90},
            "facing=north": {"model": ref, "y": 180},
            "facing=east": {"model": ref, "y": 270},
        }})
        write(os.path.join(DATA, "townfolk", "loot_table", "blocks", name + ".json"), {
            "type": "minecraft:block",
            "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "townfolk:" + name}],
                       "conditions": [{"condition": "minecraft:survives_explosion"}]}],
        })

    planks = {"tag": "minecraft:planks"}
    stick = {"item": "minecraft:stick"}
    recipes = {
        "shelf": (["PPP", "S S", "PPP"], {"P": planks, "S": stick}, 2),
        "counter": (["PPP", "S S", "S S"], {"P": planks, "S": stick}, 2),
        "market_stall": (["WWW", "S S", "PPP"], {"W": {"tag": "minecraft:wool"}, "P": planks, "S": stick}, 1),
    }
    for name, (pattern, key, count) in recipes.items():
        write(os.path.join(DATA, "townfolk", "recipe", name + ".json"), {
            "type": "minecraft:crafting_shaped", "category": "misc", "pattern": pattern, "key": key,
            "result": {"id": "townfolk:" + name, "count": count},
        })
    write(os.path.join(DATA, "minecraft", "tags", "block", "mineable", "axe.json"),
          {"replace": False, "values": ["townfolk:shelf", "townfolk:counter", "townfolk:market_stall", "townfolk:cash_register"]})


# ---------------------------------------------------------------- 货物的立体模型
# 每个模型底面贴着 y=0，水平方向以 (8, 8) 为中心。

GOODS = {
    "potato": [((5, 0, 6, 11, 3, 10), "potato"), ((6, 3, 6.8, 10, 4, 9.2), "potato"), ((4, 0.5, 7, 5, 2.5, 9), "potato"),
               ((11, 0.4, 6.8, 11.8, 2.2, 9.2), "potato")],
    "carrot": [((4.5, 0, 6.8, 10, 2.2, 9.2), "carrot"), ((10, 0.3, 7.1, 12, 1.9, 8.9), "carrot"), ((12, 0.6, 7.5, 13.5, 1.5, 8.5), "carrot"),
               ((2.5, 0.2, 7.2, 4.5, 1.4, 8.8), "leaf"), ((1.5, 1.0, 7.5, 3.5, 2.6, 8.5), "leaf")],
    "beetroot": [((5.5, 0, 5.5, 10.5, 4, 10.5), "beet"), ((6.5, 4, 6.5, 9.5, 5, 9.5), "beet"), ((7.5, 5, 7.5, 8.5, 8, 8.5), "leaf"),
                 ((6, 7, 7, 10, 9, 9), "leaf"), ((7.4, -0.0, 7.4, 8.6, 0.0, 8.6), "beet")],
    "wheat": [((6, 0, 6, 10, 7, 10), "straw"), ((5.5, 3, 5.5, 10.5, 4, 10.5), "tie"), ((5, 7, 5, 11, 10, 11), "straw"),
              ((5.5, 10, 5.5, 10.5, 11, 10.5), "straw")],
    "bread": [((4, 0, 5.5, 12, 3, 10.5), "bread"), ((5, 3, 6.3, 11, 4, 9.7), "bread"), ((6, 4, 7, 6.8, 4.2, 9), "crumb"),
              ((7.8, 4, 7, 8.6, 4.2, 9), "crumb"), ((9.6, 4, 7, 10.4, 4.2, 9), "crumb")],
    "apple": [((5.5, 0, 5.5, 10.5, 5, 10.5), "apple"), ((6.5, 5, 6.5, 9.5, 5.6, 9.5), "apple"), ((7.7, 5.6, 7.7, 8.3, 7.2, 8.3), "stem"),
              ((8.3, 6.4, 7.5, 10, 6.9, 8.5), "leaf")],
    "seeds": [((5, 0, 5, 11, 5, 11), "sack"), ((6, 5, 6, 10, 6, 10), "sack"), ((5.6, 4, 5.6, 10.4, 4.6, 10.4), "tie"),
              ((6.5, 6, 6.5, 9.5, 6.5, 9.5), "seed")],
    "sapling": [((6, 0, 6, 10, 3, 10), "clay"), ((5.5, 3, 5.5, 10.5, 3.8, 10.5), "clay"), ((7.6, 3.8, 7.6, 8.4, 7.5, 8.4), "stem"),
                ((5.5, 7.5, 5.5, 10.5, 10.5, 10.5), "leaf"), ((6.5, 10.5, 6.5, 9.5, 11.5, 9.5), "leaf")],
    "sticks": [((4, 0, 6.2, 12, 1, 7.2), "twig"), ((4, 0, 7.6, 12, 1, 8.6), "twig"), ((4, 0, 9, 12, 1, 10), "twig"),
               ((4.5, 1, 6.9, 12.5, 2, 7.9), "twig"), ((4.5, 1, 8.3, 12.5, 2, 9.3), "twig"), ((7.5, 0, 6, 8.5, 2.2, 10.2), "tie")],
}


def gen_goods():
    for name, boxes in GOODS.items():
        boxes = [(b, t) for b, t in boxes if b[1] != b[4]]         # 去掉高度为 0 的占位
        textures = {t: "townfolk:block/goods_" + t for _, t in boxes}
        textures["particle"] = "townfolk:block/goods_" + boxes[0][1]
        write(os.path.join(ASSETS, "models", "goods", name + ".json"),
              {"textures": textures, "elements": [element(box, t) for box, t in boxes]})


if __name__ == "__main__":
    gen_textures()
    gen_shelf_blocks()
    gen_goods()
    gen_coin()
    gen_register()
    print("货架和货物的资源已生成到", os.path.normpath(RES))
