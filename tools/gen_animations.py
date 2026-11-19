"""生成居民的 GeckoLib 模型和动画。

用法：在项目根目录运行  python tools/gen_animations.py
输出：
  assets/townfolk/geo/resident.geo.json                模型（玩家比例，手臂和腿各分两节，可以弯肘弯膝）
  assets/townfolk/animations/resident.animation.json   动画
想用 Blockbench 手调的话，直接打开这两个文件改就行，但再运行本脚本会把改动覆盖掉。

角度的约定（和 Blockbench 里看到的一致）：
  手臂、大腿 X 为负 = 往前抬；小臂 X 为负 = 弯肘；小腿 X 为正 = 弯膝；身体 X 为正 = 前倾。
"""
import json
import math
import os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "assets", "townfolk")

# ---------------------------------------------------------------- 模型


def bone(name, parent, pivot, cubes=None):
    b = {"name": name, "pivot": pivot}
    if parent:
        b["parent"] = parent
    if cubes:
        b["cubes"] = cubes
    return b


def cube(origin, size, uv, inflate=None):
    c = {"origin": origin, "size": size, "uv": uv}
    if inflate is not None:
        c["inflate"] = inflate
    return c


def build_geo():
    bones = [
        bone("root", None, [0, 0, 0]),
        # 身体绕胯部转，头和手臂跟着身体走，所以弯腰时整个上半身一起倾
        bone("body", "root", [0, 12, 0], [cube([-4, 12, -2], [8, 12, 4], [16, 16])]),
        bone("head", "body", [0, 24, 0], [
            cube([-4, 24, -4], [8, 8, 8], [0, 0]),
            cube([-4, 24, -4], [8, 8, 8], [32, 0], 0.5),          # 帽子层
        ]),
        bone("rightArm", "body", [-5, 22, 0], [cube([-8, 18, -2], [4, 6, 4], [40, 16])]),
        bone("rightForearm", "rightArm", [-6, 18, 0], [cube([-8, 12, -2], [4, 6, 4], [40, 22])]),
        bone("rightItem", "rightForearm", [-6, 13, -1]),
        bone("leftArm", "body", [5, 22, 0], [cube([4, 18, -2], [4, 6, 4], [32, 48])]),
        bone("leftForearm", "leftArm", [6, 18, 0], [cube([4, 12, -2], [4, 6, 4], [32, 54])]),
        bone("rightLeg", "root", [-1.9, 12, 0], [cube([-3.9, 6, -2], [4, 6, 4], [0, 16])]),
        bone("rightShin", "rightLeg", [-1.9, 6, 0], [cube([-3.9, 0, -2], [4, 6, 4], [0, 22])]),
        bone("leftLeg", "root", [1.9, 12, 0], [cube([-0.1, 6, -2], [4, 6, 4], [16, 48])]),
        bone("leftShin", "leftLeg", [1.9, 6, 0], [cube([-0.1, 0, -2], [4, 6, 4], [16, 54])]),
    ]
    return {
        "format_version": "1.12.0",
        "minecraft:geometry": [{
            "description": {
                "identifier": "geometry.resident",
                "texture_width": 64,
                "texture_height": 64,
                "visible_bounds_width": 3,
                "visible_bounds_height": 3,
                "visible_bounds_offset": [0, 1, 0],
            },
            "bones": bones,
        }],
    }


# ---------------------------------------------------------------- 动画工具

STEP = 0.05          # 每隔多少秒采一个关键帧（正好是一个游戏刻）


def smooth(x):
    return x * x * (3 - 2 * x)


def from_keys(keys, length):
    """keys: [(时间, [x,y,z]), ...]，首尾之间用慢起慢停补间，按 STEP 采样成密集关键帧。"""
    keys = sorted(keys)
    out = {}
    t = 0.0
    while t <= length + 1e-6:
        prev = keys[0]
        nxt = keys[-1]
        for i in range(len(keys) - 1):
            if keys[i][0] <= t + 1e-9 and t <= keys[i + 1][0] + 1e-9:
                prev, nxt = keys[i], keys[i + 1]
                break
        span = nxt[0] - prev[0]
        k = 0.0 if span <= 0 else smooth(min(1.0, max(0.0, (t - prev[0]) / span)))
        out["%.2f" % t] = [round(prev[1][a] + (nxt[1][a] - prev[1][a]) * k, 2) for a in range(3)]
        t += STEP
    return out


def from_func(func, length):
    """func(相位 0..2π) -> [x,y,z]，采样一个完整循环。"""
    out = {}
    steps = int(round(length / STEP))
    for i in range(steps + 1):
        t = i * STEP
        out["%.2f" % t] = [round(v, 2) for v in func(2 * math.pi * i / steps)]
    return out


def anim(length, bones, loop=True):
    a = {"animation_length": length, "bones": bones}
    if loop:
        a["loop"] = True
    return a


def rot(value):
    return {"rotation": value}


sin = math.sin
cos = math.cos

# ---------------------------------------------------------------- 各个动作


def idle():
    """站着：轻轻呼吸，手臂自然垂着微微晃。"""
    L = 3.0
    return anim(L, {
        "body": rot(from_func(lambda p: [0.8 * sin(p), 0, 0], L)),
        "rightArm": rot(from_func(lambda p: [2.0 * sin(p + 0.6), 0, 2.5 + 1.0 * sin(p)], L)),
        "leftArm": rot(from_func(lambda p: [-2.0 * sin(p + 0.6), 0, -2.5 - 1.0 * sin(p)], L)),
        "rightForearm": rot([-6, 0, 0]),
        "leftForearm": rot([-6, 0, 0]),
    })


def walk():
    """走路：摆臂、迈腿，抬腿时弯膝，上身微微前倾并随步子拧动。"""
    L = 0.8
    return anim(L, {
        "root": {"position": from_func(lambda p: [0, -0.35 + 0.35 * cos(2 * p), 0], L)},
        "body": rot(from_func(lambda p: [4, 5 * sin(p), 0], L)),
        "rightLeg": rot(from_func(lambda p: [-32 * sin(p), 0, 0], L)),
        "rightShin": rot(from_func(lambda p: [22 * (1 + cos(p + 0.9)), 0, 0], L)),
        "leftLeg": rot(from_func(lambda p: [32 * sin(p), 0, 0], L)),
        "leftShin": rot(from_func(lambda p: [22 * (1 - cos(p + 0.9)), 0, 0], L)),
        "rightArm": rot(from_func(lambda p: [30 * sin(p), 0, 3], L)),
        "rightForearm": rot(from_func(lambda p: [-20 + 12 * sin(p), 0, 0], L)),
        "leftArm": rot(from_func(lambda p: [-30 * sin(p), 0, -3], L)),
        "leftForearm": rot(from_func(lambda p: [-20 - 12 * sin(p), 0, 0], L)),
    })


def hoe():
    """锄地：屈膝，双手把锄头举过头顶，再连腰带臂刨下去。0.7 秒一下，和开垦一格地的时间一致。"""
    L = 0.7
    down, up = 0.0, 0.38
    hit = 0.52
    return anim(L, {
        "root": {"position": from_keys([(down, [0, -1.0, 0]), (up, [0, -0.3, 0]), (hit, [0, -1.2, 0]), (L, [0, -1.0, 0])], L)},
        "body": rot(from_keys([(down, [26, 0, 0]), (up, [-8, 0, 0]), (hit, [30, 0, 0]), (L, [26, 0, 0])], L)),
        "rightArm": rot(from_keys([(down, [-62, -18, 0]), (up, [-165, -12, 0]), (hit, [-58, -18, 0]), (L, [-62, -18, 0])], L)),
        "leftArm": rot(from_keys([(down, [-62, 22, 0]), (up, [-160, 14, 0]), (hit, [-58, 22, 0]), (L, [-62, 22, 0])], L)),
        "rightForearm": rot(from_keys([(down, [-12, 0, 0]), (up, [-40, 0, 0]), (hit, [-8, 0, 0]), (L, [-12, 0, 0])], L)),
        "leftForearm": rot(from_keys([(down, [-18, 0, 0]), (up, [-45, 0, 0]), (hit, [-12, 0, 0]), (L, [-18, 0, 0])], L)),
        "rightLeg": rot(from_keys([(down, [-14, 0, 0]), (up, [-6, 0, 0]), (hit, [-16, 0, 0]), (L, [-14, 0, 0])], L)),
        "rightShin": rot(from_keys([(down, [26, 0, 0]), (up, [12, 0, 0]), (hit, [30, 0, 0]), (L, [26, 0, 0])], L)),
        "leftLeg": rot(from_keys([(down, [-14, 0, 0]), (up, [-6, 0, 0]), (hit, [-16, 0, 0]), (L, [-14, 0, 0])], L)),
        "leftShin": rot(from_keys([(down, [26, 0, 0]), (up, [12, 0, 0]), (hit, [30, 0, 0]), (L, [26, 0, 0])], L)),
    })


def chop():
    """砍树：两脚站稳，上身向后拧、双手把斧头抡到身侧，再横着砍过去。0.4 秒一下。"""
    L = 0.4
    hit0, wind, hit1 = 0.0, 0.24, 0.33
    return anim(L, {
        "body": rot(from_keys([(hit0, [8, -24, 0]), (wind, [4, 30, 0]), (hit1, [10, -28, 0]), (L, [8, -24, 0])], L)),
        "rightArm": rot(from_keys([(hit0, [-78, -20, 0]), (wind, [-95, 28, 0]), (hit1, [-74, -24, 0]), (L, [-78, -20, 0])], L)),
        "leftArm": rot(from_keys([(hit0, [-72, 30, 0]), (wind, [-88, 48, 0]), (hit1, [-70, 28, 0]), (L, [-72, 30, 0])], L)),
        "rightForearm": rot(from_keys([(hit0, [-12, 0, 0]), (wind, [-55, 0, 0]), (hit1, [-8, 0, 0]), (L, [-12, 0, 0])], L)),
        "leftForearm": rot(from_keys([(hit0, [-22, 0, 0]), (wind, [-60, 0, 0]), (hit1, [-18, 0, 0]), (L, [-22, 0, 0])], L)),
        "rightLeg": rot([-10, 0, 4]),
        "rightShin": rot([14, 0, 0]),
        "leftLeg": rot([8, 0, -4]),
        "leftShin": rot([6, 0, 0]),
    })


def bend():
    """蹲下干活（播种、收割、捡东西、种树苗）：屈膝下蹲、上身前倾，一只手在地上一下一下地忙。"""
    L = 1.0
    return anim(L, {
        "root": {"position": [0, -3.9, 0]},
        "body": rot(from_func(lambda p: [40 + 3 * sin(p), 0, 0], L)),
        "rightLeg": rot([-52, 0, 0]),
        "rightShin": rot([96, 0, 0]),
        "leftLeg": rot([-52, 0, 0]),
        "leftShin": rot([96, 0, 0]),
        "rightArm": rot(from_func(lambda p: [-58 + 14 * sin(2 * p), -8, 0], L)),
        "rightForearm": rot(from_func(lambda p: [-18 - 12 * sin(2 * p), 0, 0], L)),
        "leftArm": rot([-30, 10, 0]),
        "leftForearm": rot([-55, 0, 0]),
    })


def dig():
    """挖坑：屈膝弯腰，双手握铲往下铲，再把土扬起来。"""
    L = 0.8
    lift, push = 0.0, 0.45
    return anim(L, {
        "root": {"position": from_keys([(lift, [0, -0.7, 0]), (push, [0, -1.4, 0]), (L, [0, -0.7, 0])], L)},
        "body": rot(from_keys([(lift, [22, 12, 0]), (push, [42, 0, 0]), (L, [22, 12, 0])], L)),
        "rightArm": rot(from_keys([(lift, [-70, -14, 0]), (push, [-48, -16, 0]), (L, [-70, -14, 0])], L)),
        "leftArm": rot(from_keys([(lift, [-64, 24, 0]), (push, [-44, 22, 0]), (L, [-64, 24, 0])], L)),
        "rightForearm": rot(from_keys([(lift, [-45, 0, 0]), (push, [-10, 0, 0]), (L, [-45, 0, 0])], L)),
        "leftForearm": rot(from_keys([(lift, [-50, 0, 0]), (push, [-14, 0, 0]), (L, [-50, 0, 0])], L)),
        "rightLeg": rot(from_keys([(lift, [-16, 0, 0]), (push, [-26, 0, 0]), (L, [-16, 0, 0])], L)),
        "rightShin": rot(from_keys([(lift, [30, 0, 0]), (push, [48, 0, 0]), (L, [30, 0, 0])], L)),
        "leftLeg": rot(from_keys([(lift, [-16, 0, 0]), (push, [-26, 0, 0]), (L, [-16, 0, 0])], L)),
        "leftShin": rot(from_keys([(lift, [30, 0, 0]), (push, [48, 0, 0]), (L, [30, 0, 0])], L)),
    })


def store():
    """往箱子里放东西：微微弯腰，双手伸向前。"""
    L = 1.2
    return anim(L, {
        "body": rot(from_func(lambda p: [14 + 3 * sin(p), 0, 0], L)),
        "rightArm": rot(from_func(lambda p: [-72 + 8 * sin(p), -6, 0], L)),
        "leftArm": rot(from_func(lambda p: [-72 + 8 * sin(p), 6, 0], L)),
        "rightForearm": rot(from_func(lambda p: [-20 - 10 * sin(p), 0, 0], L)),
        "leftForearm": rot(from_func(lambda p: [-20 - 10 * sin(p), 0, 0], L)),
    })


def eat():
    """吃东西：弯起小臂把手里的东西送到嘴边，一口一口地咬。"""
    L = 0.6
    return anim(L, {
        "rightArm": rot(from_func(lambda p: [-52 + 4 * sin(p), -22, 0], L)),
        "rightForearm": rot(from_func(lambda p: [-112 - 8 * sin(p), 0, 0], L)),
        "leftArm": rot([2, 0, -3]),
        "leftForearm": rot([-10, 0, 0]),
        "body": rot(from_func(lambda p: [3 + 1.5 * sin(p), 0, 0], L)),
    })


def sit():
    """坐在地上歇着：两腿往前伸、膝盖微微弓起，上身略往后靠，双手搭在腿上。"""
    L = 3.0
    return anim(L, {
        "root": {"position": [0, -9.6, 0]},
        "body": rot(from_func(lambda p: [-8 + 1.2 * sin(p), 0, 0], L)),
        "rightLeg": rot([-78, 8, 0]),
        "rightShin": rot([28, 0, 0]),
        "leftLeg": rot([-70, -8, 0]),
        "leftShin": rot([14, 0, 0]),
        "rightArm": rot(from_func(lambda p: [-38 + 1.5 * sin(p), -6, 0], L)),
        "rightForearm": rot([-38, 0, 0]),
        "leftArm": rot(from_func(lambda p: [-34 + 1.5 * sin(p), 6, 0], L)),
        "leftForearm": rot([-42, 0, 0]),
    })


def talk():
    """聊天：站着，右手在身前比划，左手偶尔也抬一下，身体跟着轻轻晃。"""
    L = 1.6
    return anim(L, {
        "body": rot(from_func(lambda p: [2 + 1.5 * sin(p), 4 * sin(p), 0], L)),
        "rightArm": rot(from_func(lambda p: [-34 + 12 * sin(2 * p), -14 + 8 * sin(p), 0], L)),
        "rightForearm": rot(from_func(lambda p: [-62 - 22 * sin(2 * p + 0.8), 0, 0], L)),
        "leftArm": rot(from_func(lambda p: [-10 - 10 * max(0.0, sin(p)), 6, -3], L)),
        "leftForearm": rot(from_func(lambda p: [-22 - 40 * max(0.0, sin(p)), 0, 0], L)),
    })


def sleep():
    """睡觉：平躺，手臂放在身体两侧。躺下这件事由渲染器来做，这里只管四肢放平。"""
    return anim(2.0, {
        "rightArm": rot([0, 0, 4]),
        "leftArm": rot([0, 0, -4]),
    })


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
        f.write("\n")


if __name__ == "__main__":
    write(os.path.join(OUT, "geo", "resident.geo.json"), build_geo())
    write(os.path.join(OUT, "animations", "resident.animation.json"), {
        "format_version": "1.8.0",
        "animations": {
            "idle": idle(), "walk": walk(), "hoe": hoe(), "chop": chop(), "bend": bend(),
            "dig": dig(), "store": store(), "eat": eat(), "sit": sit(), "talk": talk(), "sleep": sleep(),
        },
    })
    print("模型和动画已生成到", os.path.normpath(OUT))
