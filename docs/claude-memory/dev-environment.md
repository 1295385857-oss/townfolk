---
name: dev-environment
description: 这台机器上构建/运行 townfolk 模组的环境坑：JAVA_HOME 无效、如何验证客户端画面
metadata:
  node_type: memory
  type: reference
  originSessionId: 70df9e47-af4b-4a93-ac02-b797089d639a
  modified: 2026-11-19T05:26:19.984Z
---

- 系统环境变量 `JAVA_HOME=C:\work\jdk` 指向不存在的目录，直接跑 `gradlew.bat` 会报错。跑 Gradle 前先 `export JAVA_HOME="C:/Program Files/Java/jdk-21"`（Java 21 实际装在这里）。给用户的 `run-client.bat` 会自动忽略无效的 JAVA_HOME。
- 验证方式：`gradlew runGameTestServer` 跑服务端逻辑测试（`com.townfolk.test.TownGameTests`）；`gradlew runSmoke` 会真的启动游戏窗口，自动建超平坦世界、建镇、打开管理界面并截图到 `run-smoke/screenshots/`，然后自己退出（约 40 秒），用 Read 看截图确认画面。新增界面/渲染后应扩展 `client/dev/SmokeTest.java` 再跑一遍。
- 游戏内测试的场地默认会被屏障方块封顶，居民靠高度图认地面会认错；涉及地表的测试要加 `skyAccess = true`。每个测试各建一个镇会因为“两镇至少隔 128 格”互相冲突，用不同的 `batch` 让它们先后跑。
- 截图测试里用 `server.tickRateManager().requestGameToSprint(n)` 快进，让居民干一阵活再截图；截图 PNG 有 500KB 以上，贴进聊天前用 `tools/ToJpeg.java` 转成 JPEG（`java tools/ToJpeg.java in.png out.jpg 0.72`）。
- 调动画用 `gradlew runSmokePoses`（约 30 秒）：只生成一排各摆一个动作的居民、一个睡觉的、一个从侧面走过的，截图 `11_poses_*` 到 `16_walk_*`。刚 `addFreshEntity` 的生物还没落地，立刻 `navigation.moveTo` 会失败，要等几 tick。GeckoLib 源码可从它的 maven 下 `-sources.jar` 查接口。
- 做不到一格高的方块（货架、柜台、收银台这类）必须重写 `isPathfindable` 返回 false，否则原版寻路把它当成能走的格子，生物会顶在上面不动。`navigation.moveTo(x,y,z,speed)` 的精度是 1 格，只保证走到目标旁边，判定“到了”的距离不能小于约 1.4。
- 截图测试的世界关了昼夜循环（`doDaylightCycle=false`），所以里面不会发工资、不会调价；这两样要在 gametest 里测。一项测试失败且没清理小镇时，后面的测试会连带报“放下镇公所后应该建立小镇”，只看第一条失败即可。
- 自动测试和截图测试里要先设 `Demographics.autoEvents = false`，否则随机发生的婚事和出生会打乱断言；`TownTicker.tickTown(level, town)` 可以立刻触发一次住户识别、分房和招工。
- 居民死亡后实体还会 tick 约 1 秒（倒地动画），任何每 tick 写名册的逻辑都要先判 `isAlive()`。
- Minecraft/NeoForge 反编译源码在 `build/moddev/artifacts/neoforge-21.1.267-sources.jar`，核对 API 时解压后 grep（解压要不带通配符，这里的 unzip 通配符不跨目录）。
- 在 Bash 工具的 heredoc 里写含 `\\` 的内容会被吃成单个反斜杠，含中文引号的大段 heredoc 还会报引号不匹配；写文件优先用 Write 工具。
