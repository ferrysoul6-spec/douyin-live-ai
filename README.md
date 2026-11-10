# douyin-live-ai

抖音直播间 AI 助播后端：进场自动欢迎 + 评论自动回复（FAQ 短路 + LLM 兜底 + 语音播报）。

弹幕来自开源 SDK 直连的真实直播间，回复经火山方舟 LLM 生成、SeedTTS 合成，
音频入队后由本机播放器送进直播音轨，全场可听。

## 架构

```
DanmakuCollector（DouyinLiveJava SDK 连真实直播间，in-process 直调）
  ▼
Spring Boot（本仓库）→ 频控 → FAQ 短路 → 方舟 LLM → TTS 合成
  ▼
OutboundMessage 下发 → 播放队列 → 本机 afplay → OBS 混音（全场可听）
```

## 配置

**全部业务配置只有一个入口：[application.yml](src/main/resources/application.yml) 的 `live-ai.*`**，
每项都带默认值和注释。临时覆盖用同名环境变量（yml 里已写成 `${VAR:default}` 形式）。

`.env` 只放密钥，不放业务配置：

| 键 | 用途 |
|---|---|
| `RAPIDAPI_KEY` | 抖音签名服务（连直播间必需） |
| `ARK_API_KEY` | 火山方舟 LLM Key（旧键名 `DEEPSEEK_API_KEY` 仍兼容） |
| `SEEDTTS_API_KEY` | SeedTTS 语音专属 Key（UUID 形态，不是 ark- Key） |

`scripts/start-live.sh` 只做两件事：读 `.env` 密钥、按命令行动作设置采集模式；
不写任何配置默认值，改配置只改 `application.yml`。

## 目录

| 路径 | 职责 |
|---|---|
| `collector/DanmakuCollector` | 采集端：连真实直播间，映射 SDK 事件 → LiveEvent，断线指数退避重连 |
| `collector/DanmakuSimulator` | 模拟事件源，无需签名服务/真实直播间即可跑通全链路 |
| `llm/ArkLlmClient` | 方舟 LLM 调用（Chat Completions，OpenAI 兼容协议） |
| `llm/FallbackLlmClient` | LLM 路由：主模型失败自动换备用模型重试一次 |
| `llm/SeedTtsClient` | 火山 SeedTTS 2.0 主引擎 |
| `llm/EdgeTtsClient` | edge-tts 兜底引擎，调本机 `uvx edge-tts`，按文本哈希缓存 |
| `llm/FallbackTtsClient` | TTS 路由：主引擎失败自动回落兜底引擎 |
| `llm/TtsAsyncService` | 语音合成移出回复链路（队列 + 同句去重） |
| `service/FaqService` | faq.json 关键词命中，命中直接返回答案省 token |
| `service/RateLimiter` | 欢迎去重 / 用户冷却 / 全局每分钟 LLM 上限（多实例部署换 Redis） |
| `service/EventWorker` | 事件处理线程：采集回调只投递，LLM/TTS 编排在这里串行执行 |
| `service/MusicService` | 点歌：指令解析、上限 3 的队列、网易云解析 |
| `service/StageService` | 舞台状态：当前动作、最近一句字幕、空闲轮换 |
| `service/ReplyService` | 事件编排 + TTS 挂载，异常全部降级不抛出 |
| `service/ViewerMemoryService` | 观众记忆：跨天记住昵称/来访次数/历史提问，注入欢迎语 prompt |
| `service/AudioQueueService` | 待播报音频队列（有上限，丢最旧） |
| `controller/HealthController` | `/health` 探活，启动脚本用它判断后端就绪 |
| `controller/AudioController` | 音频下发 `/audio/{name}` + 队列 `/audio/queue/*` + 手动播报 `/audio/speak` |
| `controller/StageController` | `/stage` 透明舞台页 + `/stage/state` |
| `controller/MusicController` | 播放器接口：`/music/next`、`/music/skip`、`/music/finish`、`/music/state` |
| `controller/MemoryController` | 观测：`/memory/stats`、`/memory/viewer/{openid}`、`/memory/flush`、`/memory/tts`、`/memory/pipeline` |
| `live-assets/persona.md` | 基础人设记忆：AI 的自我认知、口癖、直播间背景、禁忌 |
| `scripts/netease-api.sh` | 网易云解析服务启停（点歌依赖它把歌名换成直链） |
| `scripts/start-live.sh` | 唯一脚本：启动 / 模拟 / 状态 / 停止 / 播放器（播放器已内嵌，独立跑用 `--player`） |

## AI 记忆（两层）

### 第 1 层 · 基础人设记忆
`live-assets/persona.md` 写 AI 是谁、什么口癖、这个直播间干什么、什么不能说。
每次拼进 system prompt，**改完不用重启**（每轮读文件）。配置项：

```yaml
live-ai.persona.persona-memory-file: live-assets/persona.md   # 也可直接写 persona-memory 纯文本
```

### 第 2 层 · 观众记忆
按 openid 持久化观众档案到 `memory-viewers.json`（昵称/首访/来访次数/历史提问），
模拟模式自动改用 `memory-viewers-sim.json`，避免测试 openid 污染真实档案，
老观众进场时欢迎语会体现出「记得你」。实测对比：

| 观众 | 欢迎语 |
|---|---|
| 第 2 次来的「爱吃糖的小美」 | 「小美又来啦，咕噜咕噜，今天想聊点什么呀」 |
| 全新观众 | 「欢迎新来的宝子呀，我是大肥鱼～」 |

配置项：`live-ai.memory.*`（enabled / file / max-viewers / flush-interval-seconds）。
观测接口：

```bash
curl -s localhost:8080/memory/stats                       # 档案数、累计访问、落盘路径
curl -s localhost:8080/memory/viewer/<openid>             # 单个观众档案
curl -s -X POST localhost:8080/memory/flush               # 立即落盘
```

写盘限流（默认 30s 一次）+ 临时文件原子替换，进程被 kill 也不会写坏 JSON；
超出 max-viewers 时按最久未访问淘汰。

## 弹幕采集

采集回调**只做投递**：SDK 的读线程不能等 LLM（一次 1s 左右），否则弹幕会在 SDK 内部堆积、
严重时心跳超时断连。`DanmakuCollector.dispatch` 把事件塞进有界队列后立刻返回，
由 `EventWorker` 的单线程串行处理——顺序和频控语义都不变。队列满时丢最旧一条
（`live-ai.collector.event-queue-size`，默认 200）。


依赖开源 SDK [DouyinLiveJava](https://github.com/lulajax/DouyinLiveJava)（MIT，Java 17+）。
jitpack 当前构建失败，且该 artifact 不在任何公共仓库，**必须先本地编译安装一次**：

```bash
git clone https://github.com/lulajax/DouyinLiveJava.git /tmp/DouyinLiveJava
cd /tmp/DouyinLiveJava && mvn install -DskipTests      # 装出 com.douyinlive:douyin-live-client:1.0.0
```

**关键前置：SDK 不含签名算法**，必须外部提供签名服务，二选一：
- 自建签名服务 → 配 `COLLECTOR_SIGN_URL=http://your-sign:18080`
- 走 RapidAPI 托管网关（`douyin-sign-api` by tikhub-team，订阅后拿 X-RapidAPI-Key）→ 设环境变量 `RAPIDAPI_KEY`，sign-url 留空，SDK 自动识别

启动（推荐一键脚本，自带缺 Key/缺房间号防呆校验）：

```bash
export RAPIDAPI_KEY=<订阅后复制的 X-RapidAPI-Key>
./scripts/start-live.sh https://v.douyin.com/xxxxxx/     # 支持分享短链、live.douyin.com/xxx、roomId、liveId
./scripts/start-live.sh https://v.douyin.com/xxxxxx/ --speakers   # 声音走扬声器，自己听得到不推流
./scripts/start-live.sh --status                          # 查看运行状态
./scripts/start-live.sh --stop                            # 停止并恢复音频
```

手动方式：

```bash
export COLLECTOR_MODE=live
export COLLECTOR_LIVE_ID=640801847218
# 可选：登录态 Cookie 能收到更完整弹幕，务必用环境变量注入，不要写进代码库
export COLLECTOR_COOKIE='sessionid=xxx'
mvn spring-boot:run
```

订阅地址：https://rapidapi.com/tikhub-team-tikhub-team-default/api/douyin-sign-api
（SDK 源码 `SignProvider` 硬编码的官方网关：`douyin-sign-api.p.rapidapi.com`，
签名端点 `/api/v1/douyin/sign`，status 端点 `/api/v1/douyin/sign/status`。）

### 模拟模式（不依赖任何外部服务，先验证全链路）

```bash
./scripts/start-live.sh --simulate          # 7 个模拟事件，含欢迎/评论/礼物，播放器自动拉起
```

## 频控

三道闸全部是内存实现（多实例部署需换 Redis）：

| 闸 | 默认 | 作用 |
|---|---|---|
| 欢迎去重 | 600s / 人 | 同一观众反复进出不刷「欢迎 XX」 |
| 回复冷却 | 15s / 人 | 一个人连续提问不占满额度 |
| 欢迎 LLM | 10 次/分 | 开场进场高峰用模板补足 |
| 欢迎总量 | 15 条/分 | LLM + 模板合计，防「欢迎 A/B/C」刷屏 |
| 回复 LLM | 30 次/分 | 真实提问优先走 LLM |
| 总闸 | 40 次/分 | 两类合计兜底 |

欢迎与回复分开配额是刻意的：共用 30 次/分时，开场 30 秒来几十个观众就会把额度吃光，
之后所有提问只能得到话术池回复。

## 回复链路的三级降级

```text
主模型（llm.model）失败 ──→ 备用模型（llm.fallback-model）重试一次 ──→ 预设话术池
```

`FallbackLlmClient` 负责前两级，`ReplyService` 负责最后一级。触发条件：HTTP 非 200、
超时、返回空内容。备用模型与主模型共用同一个 Key 和 base-url，**只换 model 字段**，
所以只能兜「模型级」故障（限流 / 该模型不可用 / 单次超时）：

| 故障 | 备用模型能否兜住 |
|---|---|
| 主模型限流、临时不可用、单次超时 | 能 |
| Key 失效、账号欠费、方舟整体故障 | 不能，落到话术池 |

配置：

```yaml
live-ai.llm:
  model: doubao-seed-2-1-lite-260915              # 主模型
  fallback-model: doubao-seed-2-1-turbo-260628    # 备用模型，留空 = 关闭兜底
  fallback-enabled: true
  fallback-timeout-seconds: 5                     # 比主模型短，避免连等两次完整超时
```

环境变量覆盖：`ARK_MODEL` / `ARK_FALLBACK_MODEL` / `ARK_FALLBACK_ENABLED` / `ARK_FALLBACK_TIMEOUT_SECONDS`。

实测（把主模型名改成不存在的值）：

```text
INFO  LLM 路由：主 = doubao-seed-2-1-nonexist-999999，兜底 = doubao-seed-2-1-turbo-260628（fallback-enabled=true）
WARN  主模型 doubao-seed-2-1-nonexist-999999 失败，回落到 doubao-seed-2-1-turbo-260628: 方舟 HTTP 404
INFO  [模拟] COMMENT 路过的宝子 -> [LLM]: 直播间有专属券，先领券再下单，叠加下来更划算。
```

## 语音引擎：SeedTTS 主用 + edge-tts 免费兜底

`FallbackTtsClient` 负责路由，`live-ai.tts.provider` 决定主引擎：

| 配置 | 主引擎 | 兜底 | 适用 |
|---|---|---|---|
| `provider=seedtts` | 火山 SeedTTS 2.0 | **edge-tts（微软免费）** | 推荐，延迟低 |
| `provider=edge` | edge-tts | 无 | 纯本地零成本联调 |

兜底触发条件：主引擎返回空（Key 无效 / 配额用尽 / 网络异常 / 超时）。实测：

```text
WARN  SeedTTS HTTP 401: Invalid X-Api-Key
INFO  主引擎合成失败，回落到兜底引擎（edge-tts 免费）
INFO  TTS 合成完成 50eac8d7...mp3 (26208 bytes)
```

SeedTTS 接入要点（踩过的坑都写在配置注释里）：

| 项 | 正确值 | 错误示例 |
|---|---|---|
| 端点 | `openspeech.bytedance.com/api/v3/tts/unidirectional` | `/api/v3/audio/speech` → 404 |
| Key | 语音专属 Key（`SEEDTTS_API_KEY`） | 方舟 `ark-` Key → 45000010 |
| 资源 ID | `seed-tts-2.0` | `seedtts2.0` → 45000030 not granted |
| 音色 | `zh_female_vv_uranus_bigtts`（2.0 系） | `*_moon_bigtts`（1.0 系） → resource mismatch |

关掉兜底：`TTS_FALLBACK_TO_EDGE=false`。

## 语音异步化（回复不再被合成阻塞）

`TtsAsyncService` 负责把合成移出回复链路：

```text
缓存命中 → 同步返回文件名，本次回复直接带 audioUrl（实测 7-10ms）
未命中   → 提交后台合成（tts-async-N 线程），本次只发文本
           合成完成后自动入播放队列，播放器照常播
```

实测对比（同一条弹幕）：

| 场景 | 回复耗时 | audioUrl |
|---|---|---|
| 同步模式（旧） | ~2.1s（LLM 1.08s + TTS 0.99s） | 有 |
| **异步模式（现在）** | **1.08s** | 首次无，缓存命中时有 |

防护：同一句正在合成时不重复提交（`deduped` 计数）；队列上限 50，满则丢弃该条语音；
2 个线程，关闭时 3s 内优雅退出。

观测：`curl localhost:8080/memory/tts`、`curl localhost:8080/memory/pipeline`

```json
{"asyncEnabled":true,"threads":2,"submitted":1,"completed":1,"failed":0,"deduped":0,"inFlight":0}
```

配置：`TTS_ASYNC_ENABLED` / `TTS_ASYNC_THREADS` / `TTS_ASYNC_QUEUE_SIZE`（设 `TTS_ASYNC_ENABLED=false` 可回退同步）。

## 语音怎么进直播间

后端只负责把音频文件入队；内嵌播放器（`start-live.sh --player`）用 `afplay` 播到指定音频设备，
OBS / 直播伴侣以「桌面音频」或虚拟声卡采集进推流，全场观众都能听到。

## 点歌

```text
点歌 晴天 / 我想听 晴天 / 来一首 晴天 / 放一首 晴天 / 唱一首 晴天
切歌 / 下一首 / 跳过这首 / 换一首 / 跳过
歌单 / 点歌列表 / 队列
```

- 指令在**冷却之后、FAQ 之前**拦截，走固定话术，不消耗 LLM 额度
- 队列上限 3 首（不含正在播的），满了明确告诉观众「等前面放完再点」
- 解析链路：`/search` 取歌曲 id → `/song/url` 取直链，依赖自建 NeteaseCloudMusicApi
- `live-ai.music.resolve-base` 留空时只回话术、不入队——宁可说「没有播放源」，也不假装排上
- 音乐与 AI 语音是**两个独立队列、两个 afplay 进程**：音乐循环只播歌，语音队列只播回复，
  系统混音后一起进推流；音量分别 0.35 / 1.0
- 切歌：播放器每秒轮询 `/music/skip`，命中就 kill 当前 afplay 并取下一条

**解析服务怎么起**（否则点歌只会回「暂无播放源」）：

```bash
# 首次：克隆并装依赖（npm 内网源没有这个包，要指定官方源）
git clone --depth 1 https://github.com/TianhaoC/NeteaseCloudMusicApi-enhanced.git ../netease-api
cd ../netease-api && pnpm install --registry=https://registry.npmjs.org

# 之后都用脚本
./scripts/netease-api.sh start     # 后台启动，默认 3000
./scripts/netease-api.sh status    # 进程 + 搜索自检
./scripts/netease-api.sh stop
```

手工验证 / 联调接口：

```bash
curl -X POST "localhost:8080/music/request?q=起风了"   # 等价于观众「点歌 起风了」
curl -X POST localhost:8080/music/skip                  # 切歌
curl -s localhost:8080/music/state                      # 当前曲 + 队列
```

会员/版权受限的歌（比如《晴天》专辑版）解析会返回 `code 404`、拿不到直链，
后端会如实回「暂时没有播放源」，不会假装排上。

配置：`live-ai.music.*`（enabled / resolve-base / queue-max / volume / dir）

> 合规提醒：直播间公开播放网易云曲目涉及版权与平台协议，稳妥做法是只用自有或已授权音频。

## 直播画面（OBS 浏览器源）

`GET /stage` 是一个透明底页面：角色动作 + 字幕气泡 + 点歌队列，叠在推流画面上。

| 项 | 说明 |
|---|---|
| 素材 | `live-assets/pet-alpha/*.webm`（25 个动作，**带真 alpha 通道**；缺失时退回黑底源素材 `pet`） |
| 抠像 | 素材出厂是**纯黑底、没有 alpha**，靠 `scripts/key-black.py` 烘焙成真 alpha 视频，页面不需要任何滤镜 |
| 动作映射 | 进场 `wave`、礼物 `happy`、关注 `shy`、LLM 生成中 `think`、说完 `bubble`、点歌 `sing` |
| 空闲 | 超过 18s 无事件后在 `idle,doze,tail,tea,bubble` 之间轮换，小动作播 3s 自动回 `idle` |
| 前端 | 每 0.8s 轮询 `GET /stage/state`，只在 `seq` 变化时换视频 |

**为什么必须烘焙，而不是用 CSS 滤镜抠黑**

源素材是「角色叠在纯黑上」，边缘像素的颜色本身就被黑色冲淡了（`C = α·F`）。
按亮度抠像只能解出 α，颜色还是那个被染黑的 C，合成时又乘一次 α，边缘就暗两次——
整圈轮廓变成一道黑边（浅色背景上尤其明显）。

`scripts/key-black.py` 把 α 显式写进视频，并做五件事：

1. **连通域判背景**：只有和画布四边连通的近黑区域才算背景，角色内部的黑线稿
   （眉毛、睫毛、描边）不会被当成背景抠掉；
2. **封闭黑袋也判背景**：上一条进不去「被角色围死的镂空」——呆毛弧线的内圈、
   发梢透镜的孔洞，它们在源帧里是纯黑（背景透出来），却和画布四边不连通，
   会被保留成不透明黑块，放到舞台上就是头顶一块黑斑。判据用绝对亮度：
   ≥20px 且均值 luma <0.015 的封闭袋算背景（实测角色最暗墨色 ≥0.035，差一个量级，
   眼睛和描边不会被误伤）；
3. **去污染**：半透明像素按 `F = C / α` 反解真实颜色，消掉边缘被黑染暗的那层
   （增益封顶 2.2×，否则压缩噪声会被放大成白点）；
4. **裁到人物再放大**：原画面 640×360 里人物只占约 350×335，四周全是空黑。
   裁掉空白再放大，等于把像素全花在人身上（默认 3 倍，输出 1164×1056）。
5. **剥掉原画自带的白色外描边**：只删与背景相连的那一圈中灰低饱和像素，
   白蕾丝和蓝色高光都落在判据窗之外，不会误伤。

```bash
pip install numpy scipy pillow          # 一次性
python3 scripts/key-black.py --force    # live-assets/pet -> live-assets/pet-alpha（约 8 分钟）
python3 scripts/key-black.py --only idle,wave --force    # 只重做部分
```

体积参考：默认 3 倍约 100MB（每动作约 4MB）；`--scale 1` 只剩一个零头。
`pet-alpha/` 整个删掉也没事，页面会自动退回带亮度滤镜的源素材 `pet/`。

**换素材的三步**（顺序别反，否则 OBS 会继续播旧文件）：

1. 重烘：`python3 scripts/key-black.py --force`；
2. 改 `live-assets/stage/index.html` 里的 `ASSET_VER`（如 `20261110`），页面会用
   `?v=<版本>` 拉视频，绕开 OBS/CEF 的 webm 缓存；
3. 在 OBS 里右键浏览器源 →「刷新页面」（或等它重新加载）。

`live-assets/` 是 `file:./live-assets/` 直接对外暴露的静态根目录——旧的素材备份要挪到
`backups/`（顶层，不对外），别留在 `live-assets/` 里，否则 `/pet-alpha-v1/*.webm`
会被公网直接访问到。

> 注：`dewhite` 的判据必须带**连通性约束**——头顶发饰内部那圈黑色被很薄的蓝边包着，
> 按「到背景的欧氏距离 ≤N 像素」会在里面打出一片白孔。实测 `dewhite_px=6` 时
> 孔洞从 175 涨到 3762，去掉连通性检查就会复现这个问题。
>
> 另外，拿 `ffmpeg` 验产物时必须显式 `-c:v libvpx-vp9`；原生 `vp9` 解码器
> 不认 WebM 的 alpha 附加块，会静默返回全不透明，`ffprobe` 也把 `pix_fmt`
> 报成 `yuv420p`（alpha 在独立平面里）。

> 注：原画自带**白色外描边**（Q 版立绘常见做法，让角色在任意背景上都清晰），
> 深色背景下它表现为一圈柔和光晕。烘焙时已默认清掉（第 5 步），实测对
> 深色舞台的观感改善明显。判据只认「中灰 + 低饱和 + 与背景连通」，
> 白蕾丝、蓝发高光和角色内部的黑线稿都不会被误伤。

OBS 里加一个**浏览器源**，URL 填 `http://127.0.0.1:8080/stage`，宽高与画布一致，
勾选「透明背景」（页面本身没有背景色）。

> 老环境（OBS 内置 CEF 较旧）可能不支持 VP9 alpha。页面会实测首帧：
> 检测不到透明像素就自动退回 `pet/` + 亮度滤镜，不会变成黑方块。

配置：`live-ai.stage.*`（enabled / idle-actions / idle-after-seconds）

> 动作素材取自 [newbeejmc/Dsh_douyin](https://github.com/newbeejmc/Dsh_douyin)，
> 公开使用前请确认素材授权。

## 本地运行

```bash
export ARK_API_KEY=<火山方舟 API Key>          # 或直接用 ./scripts/start-live.sh，它自动读 .env
mvn spring-boot:run

# 冒烟测试
curl -s localhost:8080/health

# 手动播报一句话（合成 + 入队）
curl -s -X POST localhost:8080/audio/speak --data-urlencode "text=欢迎宝子来到直播间"

# 取音频文件
curl -s -o /tmp/a.mp3 -w '%{http_code} %{size_download}\n' \
  localhost:8080/audio/<返回的文件名>.mp3

# 本机播放器（另一终端；OBS 开桌面音频后全场可听）
./scripts/start-live.sh --player
```

弹幕事件由采集端在进程内直接投递，没有对外 HTTP 入口；要注入事件用
`./scripts/start-live.sh --simulate`。

TTS 相关配置见 `application.yml` 的 `live-ai.tts`：`enabled`、`provider`、`voice`、`dir`、
`public-url-base`、`enqueue-for-player`、`queue-max-size`。关掉 `enabled` 即退回纯文本回复。

## 测试

```bash
mvn test        # 54 个用例
```

覆盖编排层（FAQ 短路不调 LLM、冷却/去重/总量丢弃、LLM 失败落话术池、礼物连击文案）、
模型兜底（主备切换、两者失败、自抑制边界）、频控（欢迎去重 / 回复冷却 / 分项配额）、
FAQ 命中与答非所问回归、音频队列上限与丢弃顺序、TTS 缓存键（含与重构前取值一致）、
文本截断（含 emoji 不切半）、事件线程投递/消费/队列满、观众记忆并发读写、点歌指令与降级。

## 容器部署（要点）

1. 容器服务用 Java 17 镜像，端口用 `SERVER_PORT` 环境变量覆盖。
2. 环境变量注入 `ARK_API_KEY`、`RAPIDAPI_KEY`、`SEEDTTS_API_KEY`（`TTS_PROVIDER` 已默认 seedtts）。
3. `public-url-base` 改成外部可访问地址，否则下发的音频 URL 指向 127.0.0.1 播不出来。
4. 容器里没有 `uvx`，**edge-tts 兜底不可用**，生产必须走 SeedTTS。
5. `com.douyinlive:douyin-live-client` 不在任何公共仓库，镜像内需 vendor 源码或预置本地 `libs/`，否则 `mvn` 依赖解析必失败。
6. 事件处理目前是每事件同步处理，高并发场景建议改成 HTTP 立即 ACK + 队列异步生成。

## 上线前必做

- [ ] 准备签名服务（自建或 RapidAPI Key），SDK 不含签名算法，没有它连不上直播间
- [ ] 把 SDK artifact 落到可复现位置（`libs/` 或私有仓库），否则换机/构建镜像装不上依赖
- [ ] FAQ 跟随直播间定位维护：当前是「技术分享 + 小程序接单」，不含报价和工期数字
- [ ] 生产用 SeedTTS（容器里没有 uvx，edge-tts 不可用）
- [ ] Cookie / API Key 一律走环境变量，确认没有落进 Git
- [ ] 压测：全局 LLM 上限 + TTS 合成耗时 + 扩容
- [ ] 合规：采集他人直播间弹幕属灰色地带；数字人/全自动 AI 直播需报备，建议真人主播 + AI 助播形态
