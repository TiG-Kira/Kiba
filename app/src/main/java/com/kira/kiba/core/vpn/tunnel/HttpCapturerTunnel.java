package com.kira.kiba.core.vpn.tunnel;

import android.util.Log;

import com.kira.kiba.core.model.GameType;
import com.kira.kiba.core.vpn.crawler.CrawlerCaller;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Locale;

/**
 * 抓「微信 OAuth 回调」的隧道。
 *
 * 流程：微信打开 `http://127.0.0.2:8284/xxx` → 本地 HttpServer 302 到
 * `https://tgk-wcaime.wahlap.com/wc_auth/oauth/authorize/{game}`（**redirect_uri 被改写成 http**）
 * → 微信完成 OAuth → 浏览器请求 `http://tgk-wcaime.wahlap.com/wc_auth/oauth/callback/{game}?code=...`
 * → 这个明文请求被 VPN 抓到 → 交给 [CrawlerCaller.fetchData] 带着 code 去换登录态并抓取成绩。
 *
 * **中二是 `callback/chunithm`，舞萌是 `callback/maimai-dx`。**
 * 早先这里只匹配了 maimai-dx，中二的回调永远进不来 —— 页面抓不到 → 上传时
 * 「页面内容为空」。改的时候两个都要留。
 */
public class HttpCapturerTunnel extends Tunnel {
    private static final String TAG = "HttpCapturerTunnel";

    /** 微信 OAuth 回调前缀（redirect_uri 已被改写成 http，所以这里能抓到明文包）。 */
    private static final String CALLBACK_BASE =
            "http://tgk-wcaime.wahlap.com/wc_auth/oauth/callback/";

    /** 同一回调会被 TCP 重传 / 多次跳转重复触发，用它做去重，避免重复抓取整套成绩。 */
    private static volatile String lastCaughtUrl = null;
    private static volatile long lastCaughtAt = 0L;
    private static final long CAUGHT_DEDUP_MS = 10_000L;

    /** 新会话开始前复位去重状态（由 [CrawlerCaller] 调用）。 */
    public static void resetCaptureState() {
        lastCaughtUrl = null;
        lastCaughtAt = 0L;
    }

    public HttpCapturerTunnel(InetSocketAddress serverAddress, Selector selector) throws Exception {
        super(serverAddress, selector);
    }

    public HttpCapturerTunnel(SocketChannel innerChannel, Selector selector) throws Exception {
        super(innerChannel, selector);
    }

    @Override
    protected void onConnected(ByteBuffer buffer) throws Exception {
        onTunnelEstablished();
    }

    @Override
    protected void beforeSend(ByteBuffer buffer) throws Exception {
        String body = new String(buffer.array());
        if (!body.contains("HTTP")) return;

        // Extract http target from http packet
        String[] lines = body.split("\r\n");
        String path = lines[0].split(" ")[1];
        String host = "";
        for (String line : lines) {
            if (line.toLowerCase(Locale.ROOT).startsWith("host")) {
                host = line.substring(4);
                while (host.startsWith(":") || host.startsWith(" ")) {
                    host = host.substring(1);
                }
                while (host.endsWith("\n") || host.endsWith("\r") || host.endsWith(" ")) {
                    host = host.substring(0, host.length() - 1);
                }
            }
        }
        if (!path.startsWith("/")) path = "/" + path;

        String url = "http://" + host + path;
        Log.d(TAG, "HTTP url: " + url);

        // If it's a auth redirect request, catch it
        if (!url.startsWith(CALLBACK_BASE)) return;

        // 从回调路径判断是哪个游戏：callback/chunithm 还是 callback/maimai-dx
        GameType gameType = url.startsWith(CALLBACK_BASE + "chunithm")
                ? GameType.CHUNITHM
                : GameType.MAIMAI;

        long now = System.currentTimeMillis();
        if (url.equals(lastCaughtUrl) && now - lastCaughtAt < CAUGHT_DEDUP_MS) {
            Log.d(TAG, "Auth callback duplicated, skipped");
            return;
        }
        lastCaughtUrl = url;
        lastCaughtAt = now;

        Log.d(TAG, "Auth request caught! game=" + gameType);
        CrawlerCaller.INSTANCE.fetchData(url, gameType);
    }

    @Override
    protected void afterReceived(ByteBuffer buffer) {
    }

    @Override
    protected boolean isTunnelEstablished() {
        return true;
    }

    @Override
    protected void onDispose() {
        // TODO Auto-generated method stub

    }
}
