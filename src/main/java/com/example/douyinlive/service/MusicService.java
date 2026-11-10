package com.example.douyinlive.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.douyinlive.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 点歌。观众发「点歌 X / 我想听 X / 切歌」即可，不消耗 LLM 额度。
 *
 * <p>音乐与 AI 语音是两条独立链路：语音走 {@link AudioQueueService}，
 * 音乐走这里，由本机播放器的 music 循环取走，各自 afplay，系统混音后一起进推流。
 *
 * <p>解析依赖自建 NeteaseCloudMusicApi（live-ai.music.resolve-base）：先 search 拿歌曲 id，
 * 再 song/url 拿直链。没配解析服务时只回指令话术、不下发音频。
 */
@Service
public class MusicService {

    private static final Logger log = LoggerFactory.getLogger(MusicService.class);

    /** 播放器最多提前多久判定「切歌」请求，避免误杀刚起播的歌。 */
    private static final long SKIP_GUARD_MS = 1200L;

    /** 本地曲库支持的音频后缀。 */
    private static final Set<String> AUDIO_EXT =
            Set.of(".mp3", ".wav", ".flac", ".m4a", ".ogg", ".aac", ".wma");
    /** 本地曲库最多往下找几层目录。 */
    private static final int WALK_DEPTH = 4;

    private static final Pattern PLAY_RE =
            Pattern.compile("^(?:点歌|我想听|来一首|放一首|唱一首)[\\s:：]*(.+)$");
    private static final Pattern NEXT_RE =
            Pattern.compile("^(?:切歌|下一首|跳过这首|换一首|跳过)\\s*[。！!~～]*$");
    private static final Pattern LIST_RE =
            Pattern.compile("^(?:歌单|点歌列表|队列)\\s*[。！!~～]*$");
    private static final Pattern SONG_ID_IN_URL =
            Pattern.compile("music\\.163\\.com/.*[?&]id=(\\d+)");
    private static final Pattern NUMERIC_ID = Pattern.compile("^\\d{5,}$");

    private final AppProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    private final Deque<Song> queue = new ConcurrentLinkedDeque<>();
    private final AtomicLong seq = new AtomicLong();
    private final AtomicBoolean skipRequested = new AtomicBoolean(false);
    private volatile long skipRequestedAt;
    private volatile Song current;

    public MusicService(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public enum CommandType { PLAY, NEXT, LIST }

    public record Command(CommandType type, String query) {
    }

    public record Song(String title, String url, String requestedBy) {
    }

    public record EnqueueResult(boolean ok, boolean full, String title) {
    }

    /** 识别点歌相关指令；不是指令返回 empty，交回原来的 FAQ/LLM 流程。 */
    public Optional<Command> parse(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        String text = content.trim();
        Matcher play = PLAY_RE.matcher(text);
        if (play.matches()) {
            String query = play.group(1).trim();
            return query.isEmpty() ? Optional.empty() : Optional.of(new Command(CommandType.PLAY, query));
        }
        if (NEXT_RE.matcher(text).matches()) {
            return Optional.of(new Command(CommandType.NEXT, null));
        }
        if (LIST_RE.matcher(text).matches()) {
            return Optional.of(new Command(CommandType.LIST, null));
        }
        return Optional.empty();
    }

    /** 点歌：解析成功才入队；队列满 / 无播放源都明确告诉观众。 */
    public EnqueueResult enqueue(String query, String nickname) {
        if (!props.getMusic().isEnabled()) {
            return new EnqueueResult(false, false, query);
        }
        int max = Math.max(1, props.getMusic().getQueueMax());
        if (queue.size() >= max) {
            return new EnqueueResult(false, true, query);
        }
        Optional<Song> resolved = resolve(query, nickname);
        if (resolved.isEmpty()) {
            return new EnqueueResult(false, false, query);
        }
        Song song = resolved.get();
        queue.addLast(song);
        seq.incrementAndGet();
        log.info("点歌入队: {} -> {}（队列 {}）", song.title(), song.requestedBy(), queue.size());
        return new EnqueueResult(true, false, song.title());
    }

    /** 切歌：给播放器一个一次性信号，让它杀掉当前 afplay。 */
    public boolean requestSkip() {
        if (!props.getMusic().isEnabled()) {
            return false;
        }
        skipRequested.set(true);
        skipRequestedAt = System.currentTimeMillis();
        seq.incrementAndGet();
        return true;
    }

    /** 播放器轮询：是否需要中断当前播放。读一次即清除。 */
    public boolean consumeSkip() {
        if (!skipRequested.get()) {
            return false;
        }
        // 刚起播的保护窗口内忽略，避免点歌的人把下一首也切掉
        if (System.currentTimeMillis() - skipRequestedAt < SKIP_GUARD_MS) {
            return false;
        }
        return skipRequested.compareAndSet(true, false);
    }

    /** 播放器取下一首；空队列返回 empty（播放器继续轮询）。 */
    public synchronized Optional<Song> takeNext() {
        if (!props.getMusic().isEnabled()) {
            return Optional.empty();
        }
        Song song = queue.pollFirst();
        if (song == null) {
            // 空转轮询时不要动 seq，否则前端每 2 秒白重绘一次
            return Optional.empty();
        }
        current = song;
        seq.incrementAndGet();
        return Optional.of(song);
    }

    /** 当前播放结束后由播放器回调，清掉「正在播」。 */
    public synchronized void finishCurrent() {
        current = null;
        seq.incrementAndGet();
    }

    public boolean isSupported() {
        String base = props.getMusic().getResolveBase();
        return props.getMusic().isEnabled() && base != null && !base.isBlank();
    }

    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.getMusic().isEnabled());
        m.put("supported", isSupported());
        m.put("current", current == null ? "" : current.title());
        m.put("seq", seq.get());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Song s : queue) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", s.title());
            item.put("by", s.requestedBy() == null ? "" : s.requestedBy());
            items.add(item);
        }
        m.put("queue", items);
        m.put("queueMax", props.getMusic().getQueueMax());
        return m;
    }

    /**
     * 把「歌名 / 网易云歌曲 id / 网易云分享链接」解析成可直接下载的音频地址。
     * 解析服务是自建 NeteaseCloudMusicApi，两个接口：/search、/song/url。
     */
    private Optional<Song> resolve(String query, String nickname) {
        Optional<Song> local = findLocal(query, nickname);
        if (local.isPresent()) {
            return local;
        }
        String base = props.getMusic().getResolveBase();
        if (base == null || base.isBlank()) {
            log.debug("未配置 music.resolve-base，点歌只记录不播放: {}", query);
            return Optional.empty();
        }
        String root = base.replaceAll("/+$", "");
        String id = null;
        String title = query;

        Matcher fromUrl = SONG_ID_IN_URL.matcher(query);
        if (fromUrl.find()) {
            id = fromUrl.group(1);
        } else if (NUMERIC_ID.matcher(query).matches()) {
            id = query;
        }

        if (id == null) {
            JsonNode search = get(root + "/search?keywords=" + encode(query) + "&limit=1&type=1");
            JsonNode songs = search.path("result").path("songs");
            if (!songs.isArray() || songs.isEmpty()) {
                log.warn("点歌搜不到: {}", query);
                return Optional.empty();
            }
            JsonNode first = songs.get(0);
            id = first.path("id").asText("");
            String name = first.path("name").asText(query);
            JsonNode artists = first.path("artists");
            if (artists.isArray() && !artists.isEmpty()) {
                title = name + " - " + artists.get(0).path("name").asText("");
            } else {
                title = name;
            }
        }

        if (id.isBlank()) {
            return Optional.empty();
        }
        JsonNode urlResp = get(root + "/song/url?id=" + id);
        String url = urlResp.path("data").path(0).path("url").asText("");
        if (url.isBlank()) {
            log.warn("点歌拿不到播放地址（可能需要会员 cookie）: {}", query);
            return Optional.empty();
        }
        return Optional.of(new Song(title, url, nickname));
    }

    /**
     * 本地曲库优先：在 live-ai.music.library-dir 下按文件名模糊匹配。
     *
     * <p>命中就不走在线解析——VIP 歌没有直链、版权、加载慢这三件事一起绕开。
     * 返回的 url 是相对路径 /music-library/xxx（目录由静态资源映射对外暴露），
     * 播放器下载前会补上 BASE_URL。
     */
    private Optional<Song> findLocal(String query, String nickname) {
        String dir = props.getMusic().getLibraryDir();
        if (dir == null || dir.isBlank() || query == null || query.isBlank()) {
            return Optional.empty();
        }
        Path root;
        try {
            root = Paths.get(dir);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        String q = query.trim().toLowerCase(Locale.ROOT)
                .replace('《', ' ').replace('》', ' ').trim();
        if (q.isEmpty()) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.walk(root, WALK_DEPTH)) {
            Optional<Path> hit = files
                    .filter(Files::isRegularFile)
                    .filter(MusicService::isAudioFile)
                    .filter(p -> matches(stemOf(p), q))
                    .findFirst();
            if (hit.isEmpty()) {
                return Optional.empty();
            }
            Path file = hit.get();
            String rel = root.relativize(file).toString().replace('\\', '/');
            StringBuilder url = new StringBuilder("/music-library/");
            String[] parts = rel.split("/");
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    url.append('/');
                }
                url.append(encode(parts[i]));
            }
            log.info("点歌命中本地曲库: {} -> {}（不走在线解析）", query, rel);
            return Optional.of(new Song(stemOf(file), url.toString(), nickname));
        } catch (IOException | RuntimeException e) {
            log.warn("扫描本地曲库失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean isAudioFile(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot > 0 && AUDIO_EXT.contains(name.substring(dot));
    }

    /** 双向包含就算命中：点「晴天」能找到「周杰伦 - 晴天.mp3」。 */
    private static boolean matches(String stem, String q) {
        String s = stem.toLowerCase(Locale.ROOT);
        return !s.isEmpty() && (s.contains(q) || q.contains(s));
    }

    private static String stemOf(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private JsonNode get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("解析服务返回 {}: {}", resp.statusCode(), url);
                return mapper.missingNode();
            }
            return mapper.readTree(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return mapper.missingNode();
        } catch (Exception e) {
            log.warn("解析服务调用失败: {}", e.getMessage());
            return mapper.missingNode();
        }
    }

    private String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
