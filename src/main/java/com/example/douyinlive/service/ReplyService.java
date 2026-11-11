package com.example.douyinlive.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.douyinlive.config.AppProperties;
import com.example.douyinlive.llm.LlmClient;
import com.example.douyinlive.llm.LlmException;
import com.example.douyinlive.llm.TtsAsyncService;
import com.example.douyinlive.model.LiveEvent;
import com.example.douyinlive.model.OutboundMessage;

/**
 * 回复编排：事件进来 → 判断类型 → 频控 → FAQ/LLM → TTS 合成 → 返回下发指令。
 *
 * <p>任何异常都不向上抛，统一降级为 suppressed 或兜底话术，保证直播流不被打断。
 */
@Service
public class ReplyService {

    private static final Logger log = LoggerFactory.getLogger(ReplyService.class);

    /** 句末标点：在这些位置收尾，句子是完整的。 */
    private static final String SENTENCE_ENDS = "。！？!?";
    /** 停顿标点：只能退而求其次，收尾后要把标点本身去掉。 */
    private static final String PAUSE_ENDS = "，、；,;";

    /**
     * 表演口令：单独一个 1，或「打1 / 扣1 / 来1 / 上1 / 刷1 / 要1」。
     *
     * <p>只认这一种短口令，不认「11」「1！」之外的闲聊，避免把正常发言当指令。
     * 匹配前会去掉空白，所以「打 1」「1 」都能命中。
     */
    private static final Pattern SHOW_COMMAND = Pattern.compile("^(打|扣|来|上|刷|要)?1$");

    /** 人设文件缓存：按 mtime 失效，既省掉每轮磁盘 IO，又能保留「改完即生效」。 */
    private volatile String personaCache;
    private volatile long personaCacheMtime = -1L;
    private volatile String personaCachePath;

    private final AppProperties props;
    private final FaqService faqService;
    private final RateLimiter rateLimiter;
    private final LlmClient llmClient;
    private final TtsAsyncService ttsAsyncService;
    private final ViewerMemoryService memory;
    private final MusicService music;
    private final StageService stage;

    /** 尚未感谢的点赞数：点赞是高频事件，攒够一批再播一次。 */
    private final AtomicLong pendingLikes = new AtomicLong();
    /** 上次因点赞致谢的时间，用于冷却。 */
    private final AtomicLong lastLikeThanksAt = new AtomicLong();

    public ReplyService(AppProperties props, FaqService faqService,
                        RateLimiter rateLimiter, LlmClient llmClient,
                        TtsAsyncService ttsAsyncService,
                        ViewerMemoryService memory,
                        MusicService music,
                        StageService stage) {
        this.props = props;
        this.faqService = faqService;
        this.rateLimiter = rateLimiter;
        this.llmClient = llmClient;
        this.ttsAsyncService = ttsAsyncService;
        this.memory = memory;
        this.music = music;
        this.stage = stage;
    }

    public OutboundMessage handle(LiveEvent event) {
        long start = System.currentTimeMillis();
        OutboundMessage msg = switch (event.getType()) {
            case ENTER -> handleEnter(event);
            case COMMENT -> handleComment(event);
            case GIFT -> handleGift(event);
            case FOLLOW -> handleFollow(event);
            case LIKE -> handleLike(event);
            case UNKNOWN -> OutboundMessage.suppressed("不处理的事件类型");
        };
        attachAudio(msg);
        if (!msg.isSuppressed()) {
            // 字幕与动作都从这里统一上报，前端只需要轮询一个接口
            stage.say(msg.getToNickname(), msg.getText());
        }
        msg.setCostMs(System.currentTimeMillis() - start);
        return msg;
    }

    /**
     * 冷场暖场：直接播一条预置话术。
     *
     * <p>不走 LLM（不烧 token），也不占欢迎语的去重/频控；语音仍复用同一套
     * TTS 与播放队列，所以 TTS 挂了也只是没声音，字幕照常。
     */
    public OutboundMessage warmup(String text) {
        OutboundMessage msg = OutboundMessage.of(text, OutboundMessage.Display.TTS, OutboundMessage.Source.WARMUP);
        msg.setToNickname("大肥鱼");
        attachAudio(msg);
        stage.play("bubble");
        stage.say("大肥鱼", text);
        return msg;
    }

    /**
     * 给已生成的文本回复附上语音。
     *
     * <p>合成失败或 TTS 未启用时静默跳过，文本回复照常下发——语音是增强项，
     * 绝不能因为它失败而让观众什么都收不到。
     */
    private void attachAudio(OutboundMessage msg) {
        if (msg.isSuppressed() || msg.getText() == null || msg.getText().isBlank()) {
            return;
        }
        if (!props.getTts().isEnabled()) {
            return;
        }
        try {
            // 缓存命中 → 同步挂 audioUrl 秒回；未命中 → 后台合成并直接进播放队列
            ttsAsyncService.cachedOrDispatch(msg.getText(), msg.getToNickname())
                    .ifPresent(fileName -> {
                        String base = props.getTts().getPublicUrlBase();
                        msg.setAudioUrl((base == null || base.isBlank() ? "" : base.replaceAll("/+$", ""))
                                + "/audio/" + fileName);
                    });
        } catch (RuntimeException e) {
            log.warn("TTS 挂载异常，降级纯文本: {}", e.getMessage());
        }
    }

    /** 进场欢迎：走去重 → 查观众记忆 → LLM 生成个性化欢迎语，失败降级为模板。 */
    private OutboundMessage handleEnter(LiveEvent event) {
        if (!rateLimiter.tryWelcome(event.getRoomId(), event.getOpenid())) {
            return OutboundMessage.suppressed("欢迎语去重");
        }
        if (!rateLimiter.tryBroadcastWelcome()) {
            return OutboundMessage.suppressed("欢迎语每分钟上限");
        }
        String nickname = safeNickname(event);
        stage.play("wave");

        // 记录本次访问并取历史档案（老观众 welcome 会提到「又来啦」）
        ViewerMemoryService.ViewerProfile profile = null;
        try {
            profile = memory.recordVisit(event.getOpenid(), nickname);
        } catch (RuntimeException e) {
            log.warn("写观众记忆失败（不影响回复）: {}", e.getMessage());
        }
        String memoryHint = memory.describeForPrompt(profile);

        String text;
        if (rateLimiter.tryAcquireLlm(true)) {
            try {
                String userPrompt = memoryHint.isBlank()
                        ? "有观众进入直播间，昵称是「" + nickname + "」，生成一句欢迎语。"
                        : "有观众进入直播间，昵称是「" + nickname + "」。观众记忆：" + memoryHint + "\n生成一句欢迎语。";
                text = truncate(
                        llmClient.chat(buildSystemPrompt(props.getPersona().getMaxWelcomeChars()), userPrompt),
                        props.getPersona().getMaxWelcomeChars());
            } catch (LlmException e) {
                log.warn("欢迎语 LLM 失败，降级模板: {}", e.getMessage());
                text = fallbackWelcome(nickname);
            }
        } else {
            text = fallbackWelcome(nickname);
        }
        OutboundMessage msg = OutboundMessage.of(text, OutboundMessage.Display.DANMU,
                OutboundMessage.Source.WELCOME);
        msg.setToNickname(nickname);
        return msg;
    }

    /** 评论回复：先 FAQ 短路，未命中再走 LLM，额度不足时静默丢弃。 */
    private OutboundMessage handleComment(LiveEvent event) {
        String content = event.getContent();
        if (content == null || content.isBlank()) {
            return OutboundMessage.suppressed("空评论");
        }
        if (!rateLimiter.tryReply(event.getRoomId(), event.getOpenid())) {
            return OutboundMessage.suppressed("用户回复冷却中");
        }

        // 「打1」这类口令：表演舞狮头。放在点歌和 FAQ 之前——它不需要 LLM，
        // 也不能掉进闲聊分支（否则每个跟着打 1 的人都烧一次 token、还要等 1s）
        if (isShowCommand(content)) {
            if (!rateLimiter.tryShow()) {
                return OutboundMessage.suppressed("表演指令冷却中");
            }
            stage.playFull("lion");
            OutboundMessage msg = OutboundMessage.of("好嘞，舞狮头给你安排上！",
                    OutboundMessage.Display.DANMU, OutboundMessage.Source.INTERACT);
            msg.setToNickname(safeNickname(event));
            return msg;
        }

        Optional<MusicService.Command> musicCommand = music.parse(content);
        if (musicCommand.isPresent()) {
            return handleMusicCommand(musicCommand.get(), event);
        }

        FaqService.FaqEntry hit = faqService.match(content);
        if (hit != null && props.getThrottle().isFaqShortcut()) {
            // 走一遍截断：FAQ 文案以后可能被人加长，超出上限会污染下发内容
            OutboundMessage msg = OutboundMessage.of(
                    truncate(hit.answer(), props.getPersona().getMaxReplyChars()),
                    OutboundMessage.Display.DANMU, OutboundMessage.Source.FAQ);
            msg.setToNickname(safeNickname(event));
            return msg;
        }

        if (!rateLimiter.tryAcquireLlm(false)) {
            return fallbackReply(event, "LLM 额度已满");
        }

        // 记住这位观众问了什么，下次欢迎时能提起来
        try {
            memory.recordQuestion(event.getOpenid(), content);
        } catch (RuntimeException e) {
            log.warn("记录观众提问失败（不影响回复）: {}", e.getMessage());
        }

        String context = faqService.buildContext(5);
        String memoryHint = "";
        try {
            memoryHint = memory.describeForPrompt(memory.peek(event.getOpenid()));
        } catch (RuntimeException ignored) {
            // 记忆读取失败不影响主流程
        }
        String userPrompt = (memoryHint.isBlank() ? "" : "观众背景：" + memoryHint + "\n")
                + (context.isBlank() ? "" : "商品与政策参考（只能基于这些内容回答，不要编造）：\n" + context + "\n")
                + "观众「" + safeNickname(event) + "」说：" + content + "\n请回复。";

        String text;
        stage.play("think", 0);
        try {
            text = truncate(llmClient.chat(buildSystemPrompt(props.getPersona().getMaxReplyChars()), userPrompt),
                    props.getPersona().getMaxReplyChars());
        } catch (LlmException e) {
            log.warn("评论回复 LLM 失败: {}", e.getMessage());
            return fallbackReply(event, "LLM 调用失败");
        }
        if (text.isBlank()) {
            return fallbackReply(event, "LLM 返回空");
        }
        OutboundMessage msg = OutboundMessage.of(text, OutboundMessage.Display.DANMU,
                OutboundMessage.Source.LLM);
        msg.setToNickname(safeNickname(event));
        return msg;
    }

    /**
     * 点歌 / 切歌 / 看队列：固定文案，不经过 FAQ 也不消耗 LLM 额度。
     * 解析不到播放源时明确告知，不假装已经排上。
     */
    private OutboundMessage handleMusicCommand(MusicService.Command cmd, LiveEvent event) {
        String nickname = safeNickname(event);
        String text = switch (cmd.type()) {
            case PLAY -> {
                MusicService.EnqueueResult r = music.enqueue(cmd.query(), nickname);
                if (r.ok()) {
                    stage.play("sing");
                    Object current = music.state().getOrDefault("current", "");
                    yield String.valueOf(current).isBlank()
                            ? "收到点歌：《" + r.title() + "》，这就放给你听～"
                            : "收到点歌：《" + r.title() + "》，前面那首放完就到。";
                }
                yield r.full()
                        ? "队列已经排满啦，等前面放完再点吧～"
                        : "《" + r.title() + "》暂时没有播放源，本鲸先记下歌名。";
            }
            case NEXT -> music.requestSkip()
                    ? "切歌！下一首马上来～"
                    : "现在还没开始放歌呀，想听什么直接点～";
            case LIST -> describeQueue();
        };
        OutboundMessage msg = OutboundMessage.of(
                truncate(text, props.getPersona().getMaxReplyChars()),
                OutboundMessage.Display.DANMU, OutboundMessage.Source.MUSIC);
        msg.setToNickname(nickname);
        return msg;
    }

    /** 「歌单」指令的回复：当前曲 + 排队中的几首。 */
    private String describeQueue() {
        Map<String, Object> state = music.state();
        Object current = state.getOrDefault("current", "");
        Object queue = state.get("queue");
        int size = queue instanceof List<?> list ? list.size() : 0;
        if (String.valueOf(current).isBlank() && size == 0) {
            return "队列空空的，想听什么直接点～";
        }
        StringBuilder sb = new StringBuilder();
        if (!String.valueOf(current).isBlank()) {
            sb.append("在放《").append(current).append("》");
        }
        if (size > 0) {
            sb.append(sb.length() > 0 ? "，" : "").append("队列还有 ").append(size).append(" 首");
        }
        return sb.toString();
    }

    /** 礼物感谢。连击已在采集端合并，这里只按最终数量说一次。 */
    private OutboundMessage handleGift(LiveEvent event) {
        stage.play("happy");
        String nickname = safeNickname(event);
        Integer count = event.getCount();
        String gift = event.getContent();
        String text = (count != null && count > 1)
                ? "感谢" + nickname + "的 " + count + " 个"
                    + (gift == null || gift.isBlank() ? "礼物" : gift) + "，太给力了！"
                : "感谢" + nickname + "的礼物，太给力了！";
        OutboundMessage msg = OutboundMessage.of(truncate(text, props.getPersona().getMaxReplyChars()),
                OutboundMessage.Display.DANMU, OutboundMessage.Source.WELCOME);
        msg.setToNickname(nickname);
        return msg;
    }

    /**
     * 点赞感谢。
     *
     * <p>点赞是高频事件：观众连点几十上百下，平台会连续回调，每一条都回就刷屏了。
     * 所以先累计、攒够 min-count 才值得说，再叠加冷却（默认 45s 只说一次），
     * 冷却期内继续累计、下次一起说。
     */
    private OutboundMessage handleLike(LiveEvent event) {
        int count = event.getCount() == null ? 1 : Math.max(1, event.getCount());
        long total = pendingLikes.addAndGet(count);

        AppProperties.Throttle th = props.getThrottle();
        if (total < Math.max(1, th.getLikeThankMinCount())) {
            return OutboundMessage.suppressed("点赞累计不足");
        }
        long now = System.currentTimeMillis();
        long cooldownMs = Math.max(5, th.getLikeThankCooldownSeconds()) * 1000L;
        if (now - lastLikeThanksAt.get() < cooldownMs) {
            return OutboundMessage.suppressed("点赞感谢冷却中");
        }
        lastLikeThanksAt.set(now);
        long n = pendingLikes.getAndSet(0);

        stage.play("happy");
        String nickname = event.getNickname();
        String who = (nickname == null || nickname.isBlank()) ? "宝子们" : nickname;
        String text = "谢谢" + who + "点的 " + n + " 个赞，比心~";
        OutboundMessage msg = OutboundMessage.of(truncate(text, props.getPersona().getMaxReplyChars()),
                OutboundMessage.Display.DANMU, OutboundMessage.Source.WELCOME);
        if (nickname != null && !nickname.isBlank()) {
            msg.setToNickname(nickname);
        }
        return msg;
    }

    private OutboundMessage handleFollow(LiveEvent event) {
        stage.play("shy");
        String nickname = safeNickname(event);
        OutboundMessage msg = OutboundMessage.of(
                truncate("欢迎" + nickname + "关注，点个关注不迷路~", props.getPersona().getMaxReplyChars()),
                OutboundMessage.Display.DANMU, OutboundMessage.Source.WELCOME);
        msg.setToNickname(nickname);
        return msg;
    }

    private String buildSystemPrompt(int maxChars) {
        AppProperties.Persona p = props.getPersona();
        String memory = loadPersonaMemory(p);
        return "你是抖音直播间助播「" + p.getName() + "」，风格" + p.getStyle() + "。\n"
                + (memory.isBlank() ? "" : "\n【你的自我认知与直播间背景】\n" + memory + "\n")
                + "规则：\n"
                + "1. 只输出一句给观众看的回复，不要任何解释、前缀、引号或表情符号堆砌；\n"
                + "2. 严格控制在 " + maxChars + " 个字以内；\n"
                + "3. 不编造价格、库存、发货时间等事实，参考资料里没有就说「这个我帮您问一下主播」；\n"
                + "4. 不涉及医疗、金融收益承诺、绝对化用语（最、第一、100%）；\n"
                + "5. 遇到辱骂或敏感内容，只回一句礼貌引导，不接话。";
    }

    /**
     * 读取基础人设记忆：优先文件（便于热改、可版本管理），其次配置里的纯文本。
     * 每次都读文件，改完不用重启（文件通常很小，开销可忽略）。
     */
    private String loadPersonaMemory(AppProperties.Persona p) {
        String file = p.getPersonaMemoryFile();
        if (file == null || file.isBlank()) {
            return p.getPersonaMemory() == null ? "" : p.getPersonaMemory().trim();
        }
        try {
            java.nio.file.Path path = java.nio.file.Path.of(file).toAbsolutePath().normalize();
            if (!java.nio.file.Files.exists(path)) {
                log.warn("人设记忆文件不存在: {}", path);
                return p.getPersonaMemory() == null ? "" : p.getPersonaMemory().trim();
            }
            long mtime = java.nio.file.Files.getLastModifiedTime(path).toMillis();
            String key = path.toString();
            String cached = personaCache;
            // 命中条件：同一路径 + 文件没被改过
            if (cached != null && mtime == personaCacheMtime && key.equals(personaCachePath)) {
                return cached;
            }
            String content = java.nio.file.Files
                    .readString(path, java.nio.charset.StandardCharsets.UTF_8).trim();
            personaCache = content;
            personaCacheMtime = mtime;
            personaCachePath = key;
            log.debug("人设记忆已重新加载（{} 字）: {}", content.length(), path);
            return content;
        } catch (Exception e) {
            log.warn("读取人设记忆文件失败: {}", e.getMessage());
            return p.getPersonaMemory() == null ? "" : p.getPersonaMemory().trim();
        }
    }

    private String fallbackWelcome(String nickname) {
        return "欢迎" + nickname + "来到直播间~";
    }

    /**
     * LLM 不可用时的评论兜底回复：从预设话术池随机取一条，保证真实弹幕仍有响应。
     * 关闭 live-ai.persona.fallback-reply-enabled 则退回静默丢弃。
     */
    private OutboundMessage fallbackReply(LiveEvent event, String reason) {
        List<String> pool = props.getPersona().getFallbackReplies();
        if (!props.getPersona().isFallbackReplyEnabled() || pool == null || pool.isEmpty()) {
            return OutboundMessage.suppressed(reason);
        }
        String text = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        OutboundMessage msg = OutboundMessage.of(text, OutboundMessage.Display.DANMU,
                OutboundMessage.Source.FALLBACK);
        msg.setToNickname(safeNickname(event));
        return msg;
    }

    /** 是否表演口令（打1看舞狮头这类）。匹配前去掉空白，「打 1」「1 」都算。 */
    static boolean isShowCommand(String content) {
        if (content == null) {
            return false;
        }
        return SHOW_COMMAND.matcher(content.replaceAll("\\s+", "")).matches();
    }

    private String safeNickname(LiveEvent event) {
        String n = event.getNickname();
        return (n == null || n.isBlank()) ? "宝子" : n;
    }

    /**
     * 压成单行并截断。按 code point 而不是 char 计数，
     * 否则会把 emoji 的代理对切成半个字符，下发出去是乱码。
     */
    static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replaceAll("\\s+", " ").trim();
        if (cleaned.codePointCount(0, cleaned.length()) <= max) {
            return cleaned;
        }
        String cut = cleaned.substring(0, cleaned.offsetByCodePoints(0, max));
        // 先找句末标点：在这里断开是完整句子
        int sentenceEnd = lastStop(cut, SENTENCE_ENDS);
        if (sentenceEnd > 0) {
            return cut.substring(0, sentenceEnd);
        }
        // 退而求其次找停顿标点，并把标点去掉——否则会输出「…咕噜咕噜，」这种残句
        int pause = lastStop(cut, PAUSE_ENDS);
        if (pause > 0) {
            return cut.substring(0, pause - 1);
        }
        return cut;
    }

    /** 从后往前找最后一个指定标点，只在后半段找，避免把句子砍得太短。返回标点后一位。 */
    private static int lastStop(String s, String stops) {
        for (int i = s.length() - 1; i >= s.length() / 2; i--) {
            if (stops.indexOf(s.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return 0;
    }
}
