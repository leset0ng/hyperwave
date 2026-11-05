# 切歌封面翻转

设置页的“切歌封面翻转”默认开启，独立于 iOS / HyperOS / MiOS 波形样式。设置通过设备保护存储保存，由只读 ContentProvider 通知 SystemUI；关闭后立即移除翻转视图，恢复原生 ImageView 的最新封面。

## 宿主接入与图片就绪

`ArtworkFlipHooks` 在插件 `IslandIconViewHolder.setFixIcon(DynamicIslandData)` 原调用返回后，接管 `miui_media_album_icon`。原生代码继续同步解码图片、维护原图、计算渐变颜色和处理点击。翻转视图作为原图的同容器兄弟节点，复制实际布局尺寸与 margin，并读取原图 outline 的圆角半径。右侧波形不会随之翻转。

SystemUI 的 `MiuiIslandMediaControllerImpl.generateRightImage` Hook 在图片 Bundle 中附加会话、媒体 ID、标题、歌手和图片投递代号。图片代号按上游 Icon 实例生成，跨过插件边界后仍能区分“新歌曲信息搭配旧封面”和“新的图片投递”。

切歌时先保留旧封面：

1. 只有歌曲信息变化、图片仍是上一张时，不启动动画，也不提交新歌曲 seed。
2. 新歌曲尚无封面、暂时使用应用图标时，同样等待。
3. 原生同步解码得到新的有效封面后，调用 Bitmap 的 `prepareToDraw()`，再将新图片和歌曲 seed 一起提交给翻转状态机。
4. 旋转到距离侧面 9° 的位置时，才从待显示缓冲切到新封面。

没有固定等待时间。`prepareToDraw()` 是 Android 的绘制预热请求，并不保证 GPU 上传已经同步完成。若两首歌持续共用同一个上游图片实例，则不会仅凭歌曲信息翻转同一张图片。

非空媒体 ID 优先作为曲目身份，避免歌手信息补全触发额外翻转；没有媒体 ID 时退回标题和歌手，并保留应用、用户、会话边界。不同同名曲目没有媒体 ID 时仍可能无法区分。宿主元数据 Hook 不可用时，退回原生 Icon 身份；该降级路径无法可靠区分重新构造的同一封面。

## 状态机与渲染参数

`ArtworkFlipModel` 不依赖 Android，可用单元测试验证事件和数学行为。移植依据为 `ios-re/artwork-flip-findings.md`，该目录不参与编译。

- 竖直 Y 轴、中心锚点；每次有效翻转累计 −180°，显示角使用 ties-away-from-zero 折叠，避免镜像背面。
- 弹簧使用单位质量等效 stiffness=60、阻尼比约 0.645497，对应 mass=1.25、stiffness=300、damping=25 和 speed=0.5。使用解析二阶方程，重定向保留当前角度和速度，允许过冲，不限制为 300ms。
- 换图边界为半圈中点提前 9°；累计翻转继续使用累计角度。
- 换图后去模糊与加亮用 150ms ease-out；白光初始强度 0.3，使用 Android `BlendMode.PLUS`。模糊在圆角裁切内部，白光在模糊外侧，两者都随封面旋转。
- 宽高比变化用 300ms ease-in-out；首张有效封面用 250ms 淡入，不翻转。
- 明确移除图片时先用 250ms 淡出，再用 250ms 恢复方形比例；新图片会取消过期移除完成操作。
- 同一 seed 的新图片投递原地刷新；有 pending 图片时，后续投递覆盖缓冲，不追加半圈。reveal 取走 pending 后，即使弹簧仍在运动，也允许下一次翻转。

Android 使用自定义投影矩阵映射 dimensionless perspective=0.1，模糊采用 `RenderNode` / `RenderEffect`。这些是平台近似，不代表已经逐像素匹配 SwiftUI 的模糊、色彩空间和投影。停止阈值采用本地角度、速度容差 0.01，并非从 Apple 运行时测得。

当前宿主路径统一使用 forward 方向。模型支持 backward，但尚未确认原生上一首/下一首方向标记的完整生产链，因此没有猜测映射。

## 生命周期与验证

隐藏、脱离窗口和宿主隐藏时完成到最新封面并取消帧回调；热重载释放旧视图，在新代从原生 holder 恢复。系统关闭动画时立即显示最新图片，动画速度随系统 animator duration scale 调整。

验证命令：

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
android install --device=emulator-5580 --apks=app/build/outputs/apk/debug/app-debug.apk
android install --device=emulator-5580 --apks=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w -e artwork_flip true \
  io.github.leset0ng.hyperwave.test/io.github.leset0ng.hyperwave.ui.PerformanceInstrumentation
```

本次验证：

- JVM 行为测试覆盖正反向切图边界、连续翻转、pending 合并、中断速度、过冲、两阶段移除、歌曲身份，以及元数据先到/封面晚到的投递规则。
- HyperOS API 37 模拟器的硬件 Canvas 检查通过：PixelCopy 验证中途新图、最终最新图片、原生圆角，以及隐藏/脱离后的收尾；执行了硬件 RenderNode 模糊路径。
- 模拟器设置页确认初始开关开启，分别切换 iOS / HyperOS / MiOS 后开关值保持独立，重启设置进程后保存值仍在。
- debug APK 构建与 Android lint 通过，并安装到连接的 25098PN5AC 手机供实际切歌体验。

模拟器上的模块未激活，以上设备绘制测试使用独立视图测试场景。真实音乐岛中的 Hook 命中、播放器封面交付时序、真实设备帧率与热重载视觉效果仍需实际播放验证，不能由独立渲染测试代替。
