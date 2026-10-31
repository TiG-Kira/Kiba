package com.kira.kiba.core.vpn.crawler;


import static com.kira.kiba.core.vpn.crawler.CrawlerCaller.finishUpdate;
import static com.kira.kiba.core.vpn.crawler.CrawlerCaller.onError;
import static com.kira.kiba.core.vpn.crawler.CrawlerCaller.startAuth;
import static com.kira.kiba.core.vpn.crawler.CrawlerCaller.writeLog;

import android.util.Log;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Call;
import okhttp3.ConnectionSpec;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.TlsVersion;

public class WechatCrawler {
    // Make this true for Fiddler to capture https request
    private static final boolean IGNORE_CERT = false;

    private static final int MAX_RETRY_COUNT = 4;

    private static final String TAG = "Crawler";

    private static final String WX_WINDOWS_UA =
            "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/81.0.4044.138 Safari/537.36 NetType/WIFI " +
                    "MicroMessenger/7.0.20.1781(0x6700143B) WindowsWechat(0x6307001e)";


    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final MediaType TEXT = MediaType.parse("text/plain");

    private static final MediaType HTML = MediaType.parse("text/html; charset=utf-8");

    private static final SimpleCookieJar jar = new SimpleCookieJar();

    private static final Map<Integer, String> diffMap = new HashMap<>();

    /** 中二节奏难度名（官方 proxy 口径：0..4 标准难度，5=World's End，6=最近游玩/Rating 详情）。 */
    private static final Map<Integer, String> chuniDiffMap = new HashMap<>();

    /** 中二节奏国服站点前缀（官方 proxy 实现：`chunithm.wahlap.com/mobile`）。 */
    private static final String CHUNI_BASE = "https://chunithm.wahlap.com/mobile";

    /** 标准难度需先 POST 的筛选接口（设置会话内的难度筛选）。 */
    private static final String[] CHUNI_POST_URLS = {
            "/record/musicGenre/sendBasic",
            "/record/musicGenre/sendAdvanced",
            "/record/musicGenre/sendExpert",
            "/record/musicGenre/sendMaster",
            "/record/musicGenre/sendUltima",
    };

    /** 成绩页 GET 地址（含 World's End 列表与最近游玩 Rating 详情）。 */
    private static final String[] CHUNI_URLS = {
            "/record/musicGenre/basic",
            "/record/musicGenre/advanced",
            "/record/musicGenre/expert",
            "/record/musicGenre/master",
            "/record/musicGenre/ultima",
            "/record/worldsEndList/",
            "/home/playerData/ratingDetailRecent/",
    };

    /** 中二成绩上传端点（官方 proxy 用的是 chunithmprober/player/update_records_html）。 */
    private static final String CHUNI_UPLOAD_URL =
            "https://www.diving-fish.com/api/chunithmprober/player/update_records_html";

    // ==================== 上传目标：水鱼 / 落雪 ====================
    //
    // false（默认）= 上传水鱼；true = 上传落雪个人 API（HTML 直传，
    // 见 com.kira.kiba.core.network.LxnsUploader）。
    //
    // 用静态标志而不是改一堆方法签名：机台上分同一时刻只跑一条会话
    // （用户点一次「开始抓包」），CrawlerCaller 在进入时设置、结束后清空。
    private static volatile boolean lxnsMode = false;

    /** 设置上传目标：true = 落雪，false = 水鱼。 */
    public static void setLxnsMode(boolean enabled) {
        lxnsMode = enabled;
    }

    private static OkHttpClient client = null;

    public WechatCrawler() {
        diffMap.put(0, "Basic");
        diffMap.put(1, "Advance");
        diffMap.put(2, "Expert");
        diffMap.put(3, "Master");
        diffMap.put(4, "Re:Master");
        chuniDiffMap.put(0, "BASIC");
        chuniDiffMap.put(1, "ADVANCED");
        chuniDiffMap.put(2, "EXPERT");
        chuniDiffMap.put(3, "MASTER");
        chuniDiffMap.put(4, "ULTIMA");
        chuniDiffMap.put(5, "WORLD'S END");
        chuniDiffMap.put(6, "最近游玩（Rating 详情）");
        buildHttpClient(false);
    }

    private static void uploadData(Integer diff, String data, Integer retryCount) {
        Request request = new Request.Builder().url("https://www.diving-fish.com/api/pageparser/page").addHeader("content-type", "text/plain").post(RequestBody.create(data, TEXT)).build();

        Call call = client.newCall((request));

        try {
            Response response = call.execute();
            String result = response.body().string();
            writeLog(diffMap.get(diff) + " 难度数据上传状态：" + result);
        } catch (Exception e) {
            retryUploadData(e, diff, data, retryCount);
        }
    }

    private static void retryUploadData(Exception e, Integer diff, String data, Integer currentRetryCount) {
        writeLog("上传 " + diffMap.get(diff) + " 分数数据至水鱼查分器时出现错误: " + e);
        if (currentRetryCount < MAX_RETRY_COUNT) {
            writeLog("进行第" + currentRetryCount + "次重试");
            uploadData(diff, data, currentRetryCount + 1);
        } else {
            writeLog(diffMap.get(diff) + "难度数据上传失败！");
        }
    }

    /**
     * 上传单页 HTML 到落雪（个人 API）。
     *
     * 与水鱼的区别：落雪**不做自研解析**，直接把 NET 页面源码交给服务端
     * （`POST /api/v0/user/{game}/player/html`，头用 X-User-Token），
     * 所以这里不需要账密 / jwt，也不需要 `<login>` 包裹。
     *
     * 401（Key 无效）与 404（账号还没绑定玩家）都是**重试也没用**的失败，
     * 直接放弃并提示原因，避免无意义地重复打四次。
     */
    private static void uploadDataToLxns(String game, Integer diff, String data, Integer retryCount) {
        String label = ("chunithm".equals(game) ? "中二 " + chuniDiffMap.get(diff) : diffMap.get(diff)) + " ";
        String result = com.kira.kiba.core.network.LxnsUploader.uploadHtmlBlocking(game, data);
        writeLog(label + result);

        boolean fatal = result.contains("Key 无效") || result.contains("绑定玩家");
        if (result.startsWith("上传失败") && !fatal && retryCount <= MAX_RETRY_COUNT) {
            writeLog("进行第 " + retryCount + " 次重试");
            uploadDataToLxns(game, diff, data, retryCount + 1);
        }
    }

    private static void fetchAndUploadData(String username, String password, Set<Integer> difficulties) {
        List<CompletableFuture<Object>> tasks = new ArrayList<>();
        for (Integer diff : difficulties) {
            tasks.add(CompletableFuture.supplyAsync(() -> {
                fetchAndUploadData(username, password, diff, 1);
                return null;
            }));
        }
        for (CompletableFuture<Object> task : tasks) {
            task.join();
        }
    }

    private static void fetchAndUploadData(String username, String password, Integer diff, Integer retryCount) {
        writeLog("开始获取 " + diffMap.get(diff) + " 难度的数据");
        // 落雪只认 NET「最佳成绩」标准页（record/musicSort/search?...&diff=N），官方要求筛选条件保持不变；
        // 水鱼 pageparser 沿用官方 proxy 的 musicGenre 地址。两者页面同构，区别只在 URL 参数。
        String pageUrl = lxnsMode
                ? "https://maimai.wahlap.com/maimai-mobile/record/musicSort/search/?search=V&sort=1&playCheck=on&diff=" + diff
                : "https://maimai.wahlap.com/maimai-mobile/record/musicGenre/search/?genre=99&diff=" + diff;
        Request request = new Request.Builder().url(pageUrl).build();

        Call call = client.newCall(request);
        try {
            Response response = call.execute();
            String data = Objects.requireNonNull(response.body()).string();
//            Matcher matcher = Pattern.compile("<html.*>([\\s\\S]*)</html>").matcher(data);
//            if (matcher.find()) data = Objects.requireNonNull(matcher.group(1));
//            data = Pattern.compile("\\s+").matcher(data).replaceAll(" ");

            reportFetched(diffMap.get(diff), data, response.code());
            if (data.length() < 200) {
                writeLog(diffMap.get(diff) + " 页面内容异常，已跳过上传（大概率是登录态没拿到，请确认是微信里打开链接完成的授权）");
                return;
            }

            if (lxnsMode) {
                // 落雪不收 <login> 包裹（那是水鱼 pageparser 的账密约定），原样上传页面源码
                writeLog(diffMap.get(diff) + " 难度的数据已获取，正在上传至落雪查分器");
                uploadDataToLxns("maimai", diff, data, 1);
            } else {
                // Upload data to maimai-prober
                writeLog(diffMap.get(diff) + " 难度的数据已获取，正在上传至水鱼查分器");
                uploadData(diff, "<login><u>" + username + "</u><p>" + password + "</p></login>" + data, 1);
            }
        } catch (Exception e) {
            retryFetchAndUploadData(e, username, password, diff, retryCount);
        }
    }

    private static void retryFetchAndUploadData(Exception e, String username, String password, Integer diff, Integer currentRetryCount) {
        writeLog("获取 " + diffMap.get(diff) + " 难度数据时出现错误: " + e);
        if (currentRetryCount < MAX_RETRY_COUNT) {
            writeLog("进行第" + currentRetryCount + "次重试");
            fetchAndUploadData(username, password, diff, currentRetryCount + 1);
        } else {
            writeLog(diffMap.get(diff) + "难度数据更新失败！");
        }
    }

    protected String getWechatAuthUrl() throws IOException {
        return getWechatAuthUrl("maimai-dx");
    }

    /**
     * 取微信授权链接。
     *
     * @param game 游戏标识：`maimai-dx`（舞萌）或 `chunithm`（中二节奏）。
     *             两者共用同一公众号授权域，仅路径末段不同（官方 proxy 实现即如此）。
     */
    protected String getWechatAuthUrl(String game) throws IOException {
        this.buildHttpClient(true);

        Request request = new Request.Builder().addHeader("Host", "tgk-wcaime.wahlap.com").addHeader("Upgrade-Insecure-Requests", "1").addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 12; IN2010 Build/RKQ1.211119.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/86.0.4240.99 XWEB/4317 MMWEBSDK/20220903 Mobile Safari/537.36 MMWEBID/363 MicroMessenger/8.0.28.2240(0x28001C57) WeChat/arm64 Weixin NetType/WIFI Language/zh_CN ABI/arm64").addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/wxpic,image/tpg,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9").addHeader("X-Requested-With", "com.tencent.mm").addHeader("Sec-Fetch-Site", "none").addHeader("Sec-Fetch-Mode", "navigate").addHeader("Sec-Fetch-User", "?1").addHeader("Sec-Fetch-Dest", "document").addHeader("Accept-Encoding", "gzip, deflate").addHeader("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7").url("https://tgk-wcaime.wahlap.com/wc_auth/oauth/authorize/" + game).build();

        Call call = client.newCall(request);
        Response response = call.execute();
        String url = response.request().url().toString().replace("redirect_uri=https", "redirect_uri=http");

        Log.d(TAG, "Auth url:" + url);
        return url;
    }

    public void fetchAndUploadData(String username, String password, Set<Integer> difficulties, String wechatAuthUrl) throws IOException {
        // 仅对明文 http:// 提升为 https://。原来 startsWith("http") 会把已经 https:// 的
        // 授权链接改写成 httpss://，OkHttp 直接抛 IllegalArgumentException。
        if (wechatAuthUrl.startsWith("http://"))
            wechatAuthUrl = wechatAuthUrl.replaceFirst("http://", "https://");

        jar.clearCookieStroe();

        // Login wechat
        try {
            startAuth();
            writeLog("开始登录net，请稍后...");
            this.loginWechat(wechatAuthUrl);
            writeLog("登陆完成");
        } catch (Exception error) {
            writeLog("登陆时出现错误:\n");
            onError(error);
            return;
        }

        // Fetch maimai data
        try {
            this.fetchMaimaiData(username, password, difficulties);
            writeLog("maimai 数据更新完成");
            finishUpdate();
        } catch (Exception error) {
            writeLog("maimai 数据更新时出现错误:");
            onError(error);
        }
    }


    private void loginWechat(String wechatAuthUrl) throws Exception {
        this.buildHttpClient(true);

        Log.d(TAG, wechatAuthUrl);

        Headers headers = new Headers.Builder()
                .add("Connection", "keep-alive")
                .add("Upgrade-Insecure-Requests", "1")
                .add("User-Agent", WX_WINDOWS_UA)
                .add(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9," +
                                "image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9"
                )
                .add("Sec-Fetch-Site", "none")
                .add("Sec-Fetch-Mode", "navigate")
                .add("Sec-Fetch-User", "?1")
                .add("Sec-Fetch-Dest", "document")
                .add("Accept-Encoding", "gzip, deflate, br")
                .add("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                .build();

        Request request = new Request.Builder()
                .headers(headers)
                .get()
                .url(wechatAuthUrl)
                .build();

        Call call = client.newCall(request);
        Response response = call.execute();

        try {
            String responseBody = response.body().string();
            Log.d(TAG, responseBody);
        } catch (NullPointerException error) {
            onError(error);
        }

        int code = response.code();
        writeLog(String.valueOf(code));
        if (code >= 400) {
            Exception exception = new Exception("登陆时出现错误，请重试！");
            onError(exception);
            throw new Exception(exception);
        }

        // 登录态诊断：只要有 code 换到的会话 cookie，各域数量应该都 > 0。
        // 全为 0 说明拿到的不是带 code 的回调地址，后续页面必然是登录页/空页。
        writeLog("登录态 Cookie：tgk-wcaime=" + jar.getCookies("tgk-wcaime.wahlap.com").size()
                + " maimai=" + jar.getCookies("maimai.wahlap.com").size()
                + " chunithm=" + jar.getCookies("chunithm.wahlap.com").size());

        // Handle redirect manually
        String location = response.headers().get("Location");
        if (response.code() >= 300 && response.code() < 400 && location != null) {
            request = new Request.Builder().url(location).get().build();
            call = client.newCall(request);
            call.execute().close();
        }
    }

    private void fetchMaimaiData(String username, String password, Set<Integer> difficulties) throws IOException {
        this.buildHttpClient(false);
        fetchAndUploadData(username, password, difficulties);
    }

    // ==================== 中二节奏（CHUNITHM） ====================
    //
    // 与舞萌的差异（依据水鱼官方 proxy 工具 proxy/cmd/maimaidx-prober-proxy 实现）：
    // 1. 微信授权：/wc_auth/oauth/authorize/chunithm（舞萌是 maimai-dx）；
    // 2. 取数：标准难度需**先 POST** /record/musicGenre/send{Difficulty}
    //    （表单 genre=99 + token=<会话首个 cookie>）**再 GET** 对应成绩页 —— 两步依赖
    //    服务端会话内的筛选状态，因此各难度必须**串行**执行，不能像舞萌那样并发；
    // 3. 上传：POST /api/chunithmprober/player/update_records_html，**用水鱼 jwt Cookie 鉴权**，
    //    不再像舞萌那样用 `<login><u>..</u><p>..</p></login>` 包裹账号密码；
    //    diff=6（最近游玩）需追加 ?recent=1。
    //
    // 注：/api/chunithmprober/page 实测 404 不存在，勿用。

    /**
     * 中二节奏取数并上传（串行：POST send → GET 成绩页 → POST 上传）。
     *
     * @param jwt 水鱼账号密码登录得到的 jwt（Cookie 头），OAuth 通道不支持中二
     */
    private void fetchChuniData(String jwt, Set<Integer> difficulties) throws IOException {
        this.buildHttpClient(false);
        List<Integer> sorted = new ArrayList<>(difficulties);
        java.util.Collections.sort(sorted);
        for (Integer diff : sorted) {
            if (diff < 0 || diff >= CHUNI_URLS.length) continue;
            fetchAndUploadChuniData(jwt, diff, 1);
            // 服务端「send → get」依赖会话内筛选状态，串行 + 间隔，避免并发串味与触发风控
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private static void fetchAndUploadChuniData(String jwt, Integer diff, Integer retryCount) {
        writeLog("开始获取中二 " + chuniDiffMap.get(diff) + " 的数据");
        try {
            // 1) 标准难度先 POST 筛选接口
            if (diff < 5) {
                String token = jar.getFirstCookieValue("chunithm.wahlap.com");
                FormBody.Builder form = new FormBody.Builder().add("genre", "99");
                if (token != null && !token.isEmpty()) form.add("token", token);
                Request post = new Request.Builder()
                        .url(CHUNI_BASE + CHUNI_POST_URLS[diff])
                        .post(form.build())
                        .build();
                Call call = client.newCall(post);
                try (Response ignored = call.execute()) {
                    // 只用于设置会话筛选，响应体无意义
                }
            }

            // 2) GET 成绩页
            Request request = new Request.Builder()
                    .url(CHUNI_BASE + CHUNI_URLS[diff])
                    .build();
            Call call = client.newCall(request);
            try (Response response = call.execute()) {
                String data = Objects.requireNonNull(response.body()).string();
                reportFetched("中二 " + chuniDiffMap.get(diff), data, response.code());
                if (data.length() < 200) {
                    // 未登录 / 授权失败时服务端会 302 到登录页，而 buildHttpClient(false) 不跟随重定向，
                    // 拿到的就是空 body。落雪那边会直接显示「页面内容为空」，这里提前说清原因。
                    writeLog("中二 " + chuniDiffMap.get(diff) + " 页面内容异常，已跳过上传"
                            + "（请确认链接是在微信里打开、并已用机台账号完成授权）");
                    return;
                }
                writeLog("中二 " + chuniDiffMap.get(diff) + " 的数据已获取，正在上传至"
                        + (lxnsMode ? "落雪查分器" : "水鱼查分器"));
                uploadChuniData(jwt, diff, data, 1);
            }
        } catch (Exception e) {
            retryFetchAndUploadChuniData(e, jwt, diff, retryCount);
        }
    }

    /** 统一的「页面抓到了」日志：带字节数与状态码，出问题一眼看出是空页还是解析失败。 */
    private static void reportFetched(String label, String data, int httpCode) {
        writeLog(label + " 页面已获取：" + data.length() + " 字节，HTTP " + httpCode);
    }

    private static void retryFetchAndUploadChuniData(Exception e, String jwt, Integer diff, Integer currentRetryCount) {        writeLog("获取中二 " + chuniDiffMap.get(diff) + " 难度数据时出现错误: " + e);
        if (currentRetryCount < MAX_RETRY_COUNT) {
            writeLog("进行第" + currentRetryCount + "次重试");
            fetchAndUploadChuniData(jwt, diff, currentRetryCount + 1);
        } else {
            writeLog("中二 " + chuniDiffMap.get(diff) + " 难度数据更新失败！");
        }
    }

    private static void uploadChuniData(String jwt, Integer diff, String data, Integer retryCount) {
        if (lxnsMode) {
            // 落雪支持中二的最佳成绩（record/musicGenre*）与 World's End（record/worldsEndList），
            // 但**不支持**最近游玩 Rating 详情页（/home/playerData/ratingDetailRecent），
            // 那个页面只服务于水鱼的 recent=1 通道，直接跳过免得刷一条失败日志。
            if (diff == 6) {
                writeLog("中二 最近游玩（Rating 详情）页落雪不支持，已跳过");
                return;
            }
            uploadDataToLxns("chunithm", diff, data, 1);
            return;
        }
        String url = CHUNI_UPLOAD_URL;
        // diff=6 是最近游玩（Rating 详情），服务端要求 recent=1
        if (diff == 6) url += "?recent=1";

        Request request = new Request.Builder()
                .url(url)
                .addHeader("content-type", "text/html; charset=utf-8")
                .addHeader("Cookie", jwt)
                .post(RequestBody.create(data, HTML))
                .build();

        Call call = client.newCall(request);
        try (Response response = call.execute()) {
            String result = response.body() != null ? response.body().string() : "";
            writeLog("中二 " + chuniDiffMap.get(diff) + " 难度数据上传状态：" + result + " (HTTP " + response.code() + ")");
        } catch (Exception e) {
            retryUploadChuniData(e, jwt, diff, data, retryCount);
        }
    }

    private static void retryUploadChuniData(Exception e, String jwt, Integer diff, String data, Integer currentRetryCount) {
        writeLog("上传中二 " + chuniDiffMap.get(diff) + " 分数数据至水鱼查分器时出现错误: " + e);
        if (currentRetryCount < MAX_RETRY_COUNT) {
            writeLog("进行第" + currentRetryCount + "次重试");
            uploadChuniData(jwt, diff, data, currentRetryCount + 1);
        } else {
            writeLog("中二 " + chuniDiffMap.get(diff) + " 难度数据上传失败！");
        }
    }

    /**
     * 中二节奏一条龙：微信登录 → 串行抓取各难度 → 上传水鱼。
     *
     * @param jwt 水鱼账号密码登录的 jwt（OAuth 不支持中二，必须走账号密码通道）
     */
    public void fetchAndUploadChuniData(String jwt, Set<Integer> difficulties, String wechatAuthUrl) throws IOException {
        // 仅对明文 http:// 提升为 https://。原来 startsWith("http") 会把已 https:// 的
        // 授权链接改写成 httpss://，OkHttp 直接抛 IllegalArgumentException。
        if (wechatAuthUrl.startsWith("http://"))
            wechatAuthUrl = wechatAuthUrl.replaceFirst("http://", "https://");

        jar.clearCookieStroe();

        // Login wechat
        try {
            startAuth();
            writeLog("开始登录net，请稍后...");
            this.loginWechat(wechatAuthUrl);
            writeLog("登陆完成");
        } catch (Exception error) {
            writeLog("登陆时出现错误:\n");
            onError(error);
            return;
        }

        // Fetch chunithm data
        try {
            this.fetchChuniData(jwt, difficulties);
            writeLog("中二节奏数据更新完成");
            finishUpdate();
        } catch (Exception error) {
            writeLog("中二节奏数据更新时出现错误:");
            onError(error);
        }
    }

    private void buildHttpClient(boolean followRedirect) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();

        if (IGNORE_CERT) ignoreCertBuilder(builder);

        builder.connectTimeout(120, TimeUnit.SECONDS);
        builder.readTimeout(120, TimeUnit.SECONDS);
        builder.writeTimeout(120, TimeUnit.SECONDS);
        builder.followRedirects(followRedirect);
        builder.followSslRedirects(followRedirect);

        builder.cookieJar(jar);

        // No cache for http request
        builder.cache(null);
        Interceptor noCacheInterceptor = chain -> {
            Request request = chain.request();
            Request.Builder builder1 = request.newBuilder().addHeader("Cache-Control", "no-cache");
            request = builder1.build();
            return chain.proceed(request);
        };
        builder.addInterceptor(noCacheInterceptor);

        // Fix SSL handle shake error
        ConnectionSpec spec = new ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS).tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0).allEnabledCipherSuites().build();
        // 兼容http接口
        ConnectionSpec spec1 = new ConnectionSpec.Builder(ConnectionSpec.CLEARTEXT).build();
        builder.connectionSpecs(Arrays.asList(spec, spec1));

        builder.pingInterval(3, TimeUnit.SECONDS);

        client = builder.build();
    }

    private void ignoreCertBuilder(OkHttpClient.Builder builder) {
        try {
            final TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                }

                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[]{};
                }
            }};
            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            // Create an ssl socket factory with our all-trusting manager
            final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
            builder.sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0]);
            builder.hostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });
        } catch (Exception ignored) {

        }
    }
}
