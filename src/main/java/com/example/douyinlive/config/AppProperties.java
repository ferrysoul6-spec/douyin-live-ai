package com.example.douyinlive.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 统一配置项，映射 application.yml 的 live-ai.* 前缀。
 */
@ConfigurationProperties(prefix = "live-ai")
public class AppProperties {

    private final Llm llm = new Llm();
    private final Persona persona = new Persona();
    private final Throttle throttle = new Throttle();
    private final Tts tts = new Tts();
    private final Collector collector = new Collector();
    private final Music music = new Music();
    private final Stage stage = new Stage();
    private final Memory memory = new Memory();
    private final Warmup warmup = new Warmup();

    public Llm getLlm() {
        return llm;
    }

    public Memory getMemory() {
        return memory;
    }

    public Tts getTts() {
        return tts;
    }

    public Collector getCollector() {
        return collector;
    }

    public Music getMusic() {
        return music;
    }

    public Stage getStage() {
        return stage;
    }

    public Warmup getWarmup() {
        return warmup;
    }

    public Persona getPersona() {
        return persona;
    }

    public Throttle getThrottle() {
        return throttle;
    }

    /**
     * 大模型调用（火山方舟，OpenAI 兼容协议）。
     *
     * <p>base-url 默认指向方舟；换其他 OpenAI 兼容服务只需改 base-url + model。
     * Key 走环境变量 ARK_API_KEY（兼容旧的 DEEPSEEK_API_KEY），不要写进代码库。
     */
    public static class Llm {
        /** 方舟 API Key，从 ARK_API_KEY 注入（兼容 DEEPSEEK_API_KEY）。 */
        private String apiKey;
        private String baseUrl = "https://ark.cn-beijing.volces.com/api/v3";
        private String model = "doubao-seed-2-1-lite-260915";
        private double temperature = 0.7;
        private int maxTokens = 120;
        private int timeoutSeconds = 10;
        /**
         * 火山方舟 doubao-seed 等模型默认开启深度思考（reasoning_tokens 会到几百，
         * 延迟 10 秒+，不适合直播实时回复）。开启此项后请求体附加
         * {"thinking":{"type":"disabled"}}，延迟降到 1 秒级。DeepSeek 官方 API 会忽略未知字段。
         */
        private boolean disableThinking = true;
        /**
         * 主模型失败（限流 / 超时 / 模型不可用 / 返回空）时自动回落的备用模型。
         * 与主模型共用同一 Key 与 base-url，只换 model 字段，因此只能兜「模型级」故障；
         * Key 失效、账号欠费、方舟整体故障时兜底同样失败，最终仍走话术池。空 = 不启用。
         */
        private String fallbackModel = "";
        private boolean fallbackEnabled = true;
        /** 兜底模型的超时秒数，比主模型短，避免单条弹幕连等两次完整超时。 */
        private int fallbackTimeoutSeconds = 5;
        /**
         * 兜底链：主模型失败后按顺序逐个尝试，任一成功即返回。
         *
         * <p>与主模型共用同一 Key 与 base-url，只换 model 字段，因此只能兜「模型级」故障；
         * Key 失效 / 账号欠费（401、403）属于账号级故障，链上会立刻放弃，不用把十几个模型挨个撞一遍。
         */
        private java.util.List<String> fallbackModels = new java.util.ArrayList<>();
        /** 整条兜底链的总预算（秒）：超预算就停手，别让一条弹幕排队等十几秒。 */
        private int fallbackBudgetSeconds = 15;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public boolean isDisableThinking() { return disableThinking; }
        public void setDisableThinking(boolean b) { this.disableThinking = b; }
        public String getFallbackModel() { return fallbackModel; }
        public void setFallbackModel(String m) { this.fallbackModel = m; }
        public boolean isFallbackEnabled() { return fallbackEnabled; }
        public void setFallbackEnabled(boolean b) { this.fallbackEnabled = b; }
        public int getFallbackTimeoutSeconds() { return fallbackTimeoutSeconds; }
        public void setFallbackTimeoutSeconds(int s) { this.fallbackTimeoutSeconds = s; }
        public java.util.List<String> getFallbackModels() { return fallbackModels; }
        public void setFallbackModels(java.util.List<String> list) { this.fallbackModels = list; }
        public int getFallbackBudgetSeconds() { return fallbackBudgetSeconds; }
        public void setFallbackBudgetSeconds(int s) { this.fallbackBudgetSeconds = s; }
    }

    public static class Persona {
        /** 直播间助播人设，写进 system prompt。 */
        private String name = "小助手";
        private String style = "活泼、亲切、简短";
        /** 单条回复最大字数，避免刷屏。 */
        private int maxReplyChars = 30;
        /** 欢迎语字数上限。 */
        private int maxWelcomeChars = 26;
        /**
         * LLM 不可用（无 Key / 余额不足 / 超时）时，是否用预设话术兜底回复。
         * 关掉的话，这类弹幕就静默不回复。
         */
        private boolean fallbackReplyEnabled = true;
        /** 兜底话术池，随机取一条，避免所有回复千篇一律。 */
        private java.util.List<String> fallbackReplies = java.util.List.of(
                "感谢关注，具体细节我帮您转达主播~",
                "这条我记下来了，主播稍后统一解答哦",
                "宝子稍等一下，主播马上回你"
        );

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getStyle() { return style; }
        public void setStyle(String style) { this.style = style; }
        public int getMaxReplyChars() { return maxReplyChars; }
        public void setMaxReplyChars(int maxReplyChars) { this.maxReplyChars = maxReplyChars; }
        public int getMaxWelcomeChars() { return maxWelcomeChars; }
        public void setMaxWelcomeChars(int maxWelcomeChars) { this.maxWelcomeChars = maxWelcomeChars; }
        public boolean isFallbackReplyEnabled() { return fallbackReplyEnabled; }
        public void setFallbackReplyEnabled(boolean b) { this.fallbackReplyEnabled = b; }
        public java.util.List<String> getFallbackReplies() { return fallbackReplies; }
        public void setFallbackReplies(java.util.List<String> l) { this.fallbackReplies = l; }
        /**
         * 基础人设记忆：AI 助播的自我认知、直播间背景、口癖与禁忌，
         * 每次拼进 system prompt。留空则只用 name + style 两行。
         */
        private String personaMemory = "";
        /** 人设档案文件（如 live-assets/persona.md）；非空时优先读取文件内容。 */
        private String personaMemoryFile = "";
        public String getPersonaMemory() { return personaMemory; }
        public void setPersonaMemory(String s) { this.personaMemory = s; }
        public String getPersonaMemoryFile() { return personaMemoryFile; }
        public void setPersonaMemoryFile(String s) { this.personaMemoryFile = s; }
    }

    /**
     * 观众记忆：跨天记住来过的观众（昵称、来访次数、问过什么），
     * 下次进场欢迎语会提到历史，让 AI 显得「记得你」。
     */
    public static class Memory {
        private boolean enabled = true;
        /** 持久化文件（JSON）；多实例部署时换数据库。 */
        private String file = "./memory-viewers.json";
        /** 最多保留多少位观众档案，超出按最久未访问淘汰。 */
        private int maxViewers = 5000;
        /** 最短写盘间隔（秒），避免高频弹幕反复 IO。 */
        private int flushIntervalSeconds = 30;
        /** 老观众进场时是否生成个性化欢迎语。 */
        private boolean personalizedWelcome = true;
        /** 每位观众最多记录多少条历史提问。 */
        private int maxQuestionsPerViewer = 5;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean b) { this.enabled = b; }
        public String getFile() { return file; }
        public void setFile(String f) { this.file = f; }
        public int getMaxViewers() { return maxViewers; }
        public void setMaxViewers(int n) { this.maxViewers = n; }
        public int getFlushIntervalSeconds() { return flushIntervalSeconds; }
        public void setFlushIntervalSeconds(int s) { this.flushIntervalSeconds = s; }
        public boolean isPersonalizedWelcome() { return personalizedWelcome; }
        public void setPersonalizedWelcome(boolean b) { this.personalizedWelcome = b; }
        public int getMaxQuestionsPerViewer() { return maxQuestionsPerViewer; }
        public void setMaxQuestionsPerViewer(int n) { this.maxQuestionsPerViewer = n; }
    }

    /**
     * 频控与去重：直播间弹幕量大，必须限流，否则会被刷屏且浪费 token。
     */
    public static class Throttle {
        /** 同一用户多久内不再重复欢迎（秒）。 */
        private int welcomeDedupeSeconds = 600;
        /** 同一用户两次 AI 回复的最小间隔（秒）。 */
        private int replyCooldownSeconds = 15;
        /**
         * 进场欢迎每分钟最多调用几次 LLM。
         * 与评论回复分开配额：开场进场高峰不能把评论额度吃光，否则真实提问只能得到话术池回复。
         */
        private int welcomeLlmPerMinute = 10;
        /** 评论回复每分钟最多调用几次 LLM。 */
        private int replyLlmPerMinute = 30;
        /**
         * 每分钟最多发几条欢迎语（LLM 生成 + 模板合计）。
         * 开场几十人涌进来时，光靠欢迎去重挡不住「欢迎 A/B/C…」刷满屏。
         */
        private int welcomePerMinute = 15;
        /** 两类合计的每分钟上限，兜底防止单类配额被绕过。 */
        private int globalLlmPerMinute = 40;
        /** 命中 FAQ 时是否跳过 LLM（省 token）。 */
        private boolean faqShortcut = true;

        public int getWelcomeDedupeSeconds() { return welcomeDedupeSeconds; }
        public void setWelcomeDedupeSeconds(int s) { this.welcomeDedupeSeconds = s; }
        public int getReplyCooldownSeconds() { return replyCooldownSeconds; }
        public void setReplyCooldownSeconds(int s) { this.replyCooldownSeconds = s; }
        public int getWelcomeLlmPerMinute() { return welcomeLlmPerMinute; }
        public void setWelcomeLlmPerMinute(int n) { this.welcomeLlmPerMinute = n; }
        public int getReplyLlmPerMinute() { return replyLlmPerMinute; }
        public void setReplyLlmPerMinute(int n) { this.replyLlmPerMinute = n; }
        public int getWelcomePerMinute() { return welcomePerMinute; }
        public void setWelcomePerMinute(int n) { this.welcomePerMinute = n; }
        public int getGlobalLlmPerMinute() { return globalLlmPerMinute; }
        public void setGlobalLlmPerMinute(int n) { this.globalLlmPerMinute = n; }
        public boolean isFaqShortcut() { return faqShortcut; }
        public void setFaqShortcut(boolean b) { this.faqShortcut = b; }
    }

    /**
     * 语音合成（TTS）。
     *
     * <p>provider=edge：调用本机 uvx edge-tts（免费，中文音色好，适合本地联调和
     * “音频混进直播间推流”的方案）；provider=volc：火山引擎 TTS（生产推荐，
     * 需在抖音云环境能访问外网并配 AK/SK，实现类留待接入）。
     */
    public static class Tts {
        /** 总开关：关闭时只下发文本，不合成音频。 */
        private boolean enabled = true;
        private String provider = "edge";
        /** edge-tts 音色，中文推荐 zh-CN-XiaoxiaoNeural（女）/ zh-CN-YunxiNeural（男）。 */
        private String voice = "zh-CN-XiaoxiaoNeural";
        /** 音频文件落盘目录；按文本哈希缓存，相同文案不重复合成。 */
        private String dir = "./audio-cache";
        /** 下发给前端的音频 URL 前缀，默认本机服务地址。 */
        private String publicUrlBase = "http://127.0.0.1:8080";
        /** edge-tts 可执行入口（uvx 或 pip 安装的 edge-tts）。 */
        private String command = "uvx";
        /** 单次合成超时秒数，超时降级为纯文本。 */
        private int timeoutSeconds = 20;
        /** 音频入队给本地播放器（混流方案）的开关。 */
        private boolean enqueueForPlayer = true;
        /**
         * 播放队列上限，超过则丢弃最旧的。
         * 按每条 30 字约 6s 音频、播放器 60s 过期窗口算，超过 10 条的尾部必被丢弃。
         */
        private int queueMaxSize = 10;
        /** 主引擎是 SeedTTS 时，失败自动回落到本机 edge-tts（微软免费）。 */
        private boolean fallbackToEdge = true;
        /** 语音合成异步化：命中缓存同步返回，未命中后台合成（关掉则退回同步阻塞）。 */
        private boolean asyncEnabled = true;
        /** 异步合成线程数。 */
        private int asyncThreads = 2;
        /** 异步任务队列上限，满则丢弃（宁可少一条语音也不阻塞弹幕）。 */
        private int asyncQueueSize = 50;
        /** SeedTTS 2.0（火山豆包语音合成）配置，provider=seedtts 时生效。 */
        private final Seedtts seedtts = new Seedtts();

        public Seedtts getSeedtts() { return seedtts; }
        public boolean isFallbackToEdge() { return fallbackToEdge; }
        public void setFallbackToEdge(boolean b) { this.fallbackToEdge = b; }
        public boolean isAsyncEnabled() { return asyncEnabled; }
        public void setAsyncEnabled(boolean b) { this.asyncEnabled = b; }
        public int getAsyncThreads() { return asyncThreads; }
        public void setAsyncThreads(int n) { this.asyncThreads = n; }
        public int getAsyncQueueSize() { return asyncQueueSize; }
        public void setAsyncQueueSize(int n) { this.asyncQueueSize = n; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean b) { this.enabled = b; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getVoice() { return voice; }
        public void setVoice(String voice) { this.voice = voice; }
        public String getDir() { return dir; }
        public void setDir(String dir) { this.dir = dir; }
        public String getPublicUrlBase() { return publicUrlBase; }
        public void setPublicUrlBase(String s) { this.publicUrlBase = s; }
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int t) { this.timeoutSeconds = t; }
        public boolean isEnqueueForPlayer() { return enqueueForPlayer; }
        public void setEnqueueForPlayer(boolean b) { this.enqueueForPlayer = b; }
        public int getQueueMaxSize() { return queueMaxSize; }
        public void setQueueMaxSize(int n) { this.queueMaxSize = n; }
    }

    /**
     * 火山 SeedTTS 2.0（豆包语音合成）配置。
     *
     * <p>走语音服务专属鉴权：X-Api-Key 是「语音」类 API Key（UUID 形态），
     * 不是方舟 LLM 的 ark- Key；X-Api-Resource-Id 固定 seed-tts-2.0。
     * 音色必须是 2.0 系列（*_uranus_bigtts），1.0 音色会报 resource mismatch。
     */
    public static class Seedtts {
        /** 语音专属 API Key（控制台「API Key 管理」里语音用途的那把）。 */
        private String apiKey = "";
        private String endpoint = "https://openspeech.bytedance.com/api/v3/tts/unidirectional";
        private String resourceId = "seed-tts-2.0";
        /**
         * 音色 ID。2.0 可用（实测通过）：
         * zh_female_vv_uranus_bigtts / zh_female_xiaohe_uranus_bigtts / zh_male_m191_uranus_bigtts
         */
        private String speaker = "zh_female_vv_uranus_bigtts";
        private int sampleRate = 24000;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String s) { this.apiKey = s; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String s) { this.endpoint = s; }
        public String getResourceId() { return resourceId; }
        public void setResourceId(String s) { this.resourceId = s; }
        public String getSpeaker() { return speaker; }
        public void setSpeaker(String s) { this.speaker = s; }
        public int getSampleRate() { return sampleRate; }
        public void setSampleRate(int n) { this.sampleRate = n; }
    }

    /**
     * 点歌。观众发「点歌 X / 我想听 X / 切歌」即可进队列；
     * 音频由本机播放器循环取走播放，与 AI 语音是两个独立队列，混音后一起进推流。
     */
    public static class Music {
        private boolean enabled = true;
        /**
         * 网易云解析服务地址（自建 NeteaseCloudMusicApi），如 http://127.0.0.1:3000。
         * 留空时点歌只做指令回复，不下发音频（避免播不出还想当然）。
         */
        private String resolveBase = "";
        /** 待播队列上限（不含正在播的那首）。 */
        private int queueMax = 3;
        /** 背景音乐音量（0-1），AI 语音是 1.0，避免盖住说话。 */
        private double volume = 0.35;
        /** 下载缓存目录。 */
        private String dir = "./music-cache";
        /**
         * 本地曲库目录：点歌先在这里模糊匹配，命中就播本地文件，不再走在线解析。
         * 目录内容由静态资源映射对外暴露为 /music-library/**，播放器直接下载。
         * 留空关闭本地曲库。
         */
        private String libraryDir = "./live-assets/music-library";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean b) { this.enabled = b; }
        public String getResolveBase() { return resolveBase; }
        public void setResolveBase(String s) { this.resolveBase = s; }
        public int getQueueMax() { return queueMax; }
        public void setQueueMax(int n) { this.queueMax = n; }
        public double getVolume() { return volume; }
        public void setVolume(double v) { this.volume = v; }
        public String getDir() { return dir; }
        public void setDir(String s) { this.dir = s; }
        public String getLibraryDir() { return libraryDir; }
        public void setLibraryDir(String s) { this.libraryDir = s; }
    }

    /**
     * 直播画面（OBS 浏览器源）：角色动作 + 字幕气泡 + 点歌队列。
     */
    public static class Stage {
        private boolean enabled = true;
        /**
         * 空闲时轮换的动作，逗号分隔。
         *
         * <p>写 {@code auto} 表示从素材清单里自动装填全部动作（推荐）：素材加了多少个，
         * 轮换池就有多少个，不用手工维护这一行。
         */
        private String idleActions = "auto";
        /** 素材清单，前端与本服务共用；auto 模式下从它读动作名和帧数。 */
        private String idleActionsFile = "live-assets/stage/manifest-shenshen.json";
        /** 空闲多少秒后开始轮换动作。 */
        private int idleAfterSeconds = 18;
        /** 两次轮换之间的间隔秒数（上一次开播算起），动作播完留一段待机再换下一个。 */
        private int idleRotateSeconds = 22;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean b) { this.enabled = b; }
        public String getIdleActions() { return idleActions; }
        public void setIdleActions(String s) { this.idleActions = s; }
        public String getIdleActionsFile() { return idleActionsFile; }
        public void setIdleActionsFile(String s) { this.idleActionsFile = s; }
        public int getIdleAfterSeconds() { return idleAfterSeconds; }
        public void setIdleAfterSeconds(int n) { this.idleAfterSeconds = n; }
        public int getIdleRotateSeconds() { return idleRotateSeconds; }
        public void setIdleRotateSeconds(int n) { this.idleRotateSeconds = n; }
    }

    /**
     * 冷场暖场：直播间接连一段时间没有互动时，主动播一条预置话术，
     * 避免画面和声音都静下来（也降低被判定为无人直播的概率）。
     */
    public static class Warmup {
        private boolean enabled = true;
        /** 静默多少秒后开始暖场（从最近一次舞台活动算起）。 */
        private int idleSeconds = 120;
        /** 两次暖场之间的最小间隔秒数，防止一直自言自语。 */
        private int minIntervalSeconds = 180;
        /** 暖场话术池，随机取一条；留空则不暖场。 */
        private List<String> lines = new ArrayList<>(List.of(
                "直播间现在人不多，我先自己聊两句：最近在折腾 AI 直播这套东西，有想一起做的可以直接打在公屏上。",
                "空场时间补一条——想做小程序的朋友，把需求打在公屏上，我帮你看能不能做、大概什么路子。",
                "最近在整理 AI 直播踩过的坑，弹幕想问哪块，我优先讲。",
                "有人在看的话打个 1，我看看要不要聊聊技术选型。"
        ));

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean b) { this.enabled = b; }
        public int getIdleSeconds() { return idleSeconds; }
        public void setIdleSeconds(int n) { this.idleSeconds = n; }
        public int getMinIntervalSeconds() { return minIntervalSeconds; }
        public void setMinIntervalSeconds(int n) { this.minIntervalSeconds = n; }
        public List<String> getLines() { return lines; }
        public void setLines(List<String> l) { this.lines = l; }
    }

    /**
     * 弹幕采集端（DouyinLiveJava SDK）。
     *
     * <p>mode=live：连接真实直播间（需要签名服务，见 signUrl 说明）。
     * mode=simulate：本地造事件走通全链路（频控/FAQ/LLM/TTS/播放队列），无需任何外部依赖。
     * mode=off：不启动采集（默认），只提供 HTTP 观测接口。
     */
    public static class Collector {
        /** off / simulate / live */
        private String mode = "off";
        /** 直播间 web_rid（浏览器地址栏 live.douyin.com/ 后面那串数字），与 roomId 二选一。 */
        private String liveId;
        /** 直播间 room_id（签名服务 status 接口返回的），与 liveId 二选一。 */
        private String roomId;
        /**
         * 签名服务地址（SDK 不含签名算法，必须外部提供）：
         * - 自建签名服务：http://your-sign-server:18080
         * - 或走 RapidAPI 托管网关（用环境变量 RAPIDAPI_KEY，此处留空即可）
         * 不配且无环境变量时，SDK 默认尝试 http://localhost:18080。
         */
        private String signUrl;
        /** 可选登录态 Cookie（sessionid=...; ...），传入能收到更完整的弹幕事件；严禁提交到代码库。 */
        private String cookie;
        /** 断线重连的起始间隔（秒），每次失败翻倍，上限 reconnectMaxSeconds。 */
        private int reconnectDelaySeconds = 5;
        private int reconnectMaxSeconds = 120;
        /**
         * 采集回调与处理之间的队列容量（条）。
         * 回调只投递、不等待 LLM，避免 1s 级的回复耗时把 SDK 读线程堵住；
         * 队列满时丢最旧一条。
         */
        private int eventQueueSize = 200;

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getLiveId() { return liveId; }
        public void setLiveId(String liveId) { this.liveId = liveId; }
        public String getRoomId() { return roomId; }
        public void setRoomId(String roomId) { this.roomId = roomId; }
        public String getSignUrl() { return signUrl; }
        public void setSignUrl(String signUrl) { this.signUrl = signUrl; }
        public String getCookie() { return cookie; }
        public void setCookie(String cookie) { this.cookie = cookie; }
        public int getReconnectDelaySeconds() { return reconnectDelaySeconds; }
        public void setReconnectDelaySeconds(int s) { this.reconnectDelaySeconds = s; }
        public int getReconnectMaxSeconds() { return reconnectMaxSeconds; }
        public void setReconnectMaxSeconds(int s) { this.reconnectMaxSeconds = s; }
        public int getEventQueueSize() { return eventQueueSize; }
        public void setEventQueueSize(int n) { this.eventQueueSize = n; }
    }
}
