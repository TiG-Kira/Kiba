package com.kira.kiba.core.vpn.core;


import static android.content.ContentValues.TAG;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.kira.kiba.R;
import com.kira.kiba.MainActivity;
import com.kira.kiba.core.vpn.dns.DnsPacket;
import com.kira.kiba.core.vpn.server.HttpRedirectServer;
import com.kira.kiba.core.vpn.server.HttpServer;
import com.kira.kiba.core.vpn.tcpip.CommonMethods;
import com.kira.kiba.core.vpn.tcpip.IPHeader;
import com.kira.kiba.core.vpn.tcpip.TCPHeader;
import com.kira.kiba.core.vpn.tcpip.UDPHeader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LocalVpnService extends VpnService implements Runnable {

    /**
     * 前台服务通知渠道 / ID。
     *
     * 为什么必须前台化：抓包链路要求用户切到微信里打开链接，此时 Kiba 退到后台。
     * Android 8+ 的「后台执行限制」会在应用进入后台约 **1 分钟**后停止普通后台 Service，
     * VPN 随之被系统 revoke —— 这正是「VPN 不到一分钟就自己掉」的根因。
     * 前台服务（带常驻通知）不受该限制，能一直撑到微信授权回调回来。
     */
    private static final String NOTIFICATION_CHANNEL_ID = "kiba_vpn_capture";
    private static final int NOTIFICATION_ID = 1001;

    public static LocalVpnService Instance;

    /**
     * 会话开关。**必须是 volatile**：抓包线程写、VPN 读循环读，
     * 普通 boolean 存在可见性问题，会导致「置了 false 但 VPN 还在跑」。
     */
    public static volatile boolean IsRunning = false;

    /**
     * 请求结束本次 VPN 会话。
     *
     * 只把 [IsRunning] 置 false 是不够的：VPN 读循环阻塞在 `in.read()` 上，
     * 没有流量进来就永远醒不了，VPN 会一直挂着。这里顺手关掉虚拟网卡，
     * 让 read 立刻抛异常退出循环，系统 VPN 图标随即消失。
     */
    public static void requestStop() {
        IsRunning = false;
        LocalVpnService service = Instance;
        if (service != null) service.closeVpnInterface();
    }

    /**
     * 结束整个抓包会话：断 VPN + 停掉服务本身。
     *
     * 抓取完成（或失败）后必须走这里，否则前台通知会一直挂着
     * 「机台抓包进行中」，用户以为还在跑。
     */
    public static void shutdown() {
        requestStop();
        LocalVpnService service = Instance;
        if (service != null) service.stopSelf();
    }

    private final String device = Build.DEVICE;
    private final String model = Build.MODEL;
    private final String version = "" + Build.VERSION.SDK_INT + " (" + Build.VERSION.RELEASE + ")";

    private static int ID;
    private static int LOCAL_IP;
    private static ConcurrentHashMap<onStatusChangedListener, Object> m_OnStatusChangedListeners = new ConcurrentHashMap<onStatusChangedListener, Object>();

    private Thread m_VPNThread;
    private ParcelFileDescriptor m_VPNInterface;
    private TcpProxyServer m_TcpProxyServer;
    private DnsProxy m_DnsProxy;
    private FileOutputStream m_VPNOutputStream;

    /**
     * 本地 HTTP 跳转服务（127.0.0.2:8284 / :9457）。
     *
     * 以前由独立的 HttpServerService 承载，但它同样是「后台 Service」，
     * 会被系统在应用退到后台后停掉，微信里打开链接就直接连不上了。
     * 现在改为由本前台服务内联托管：一个通知、一套生命周期，不会被单独杀掉。
     */
    private HttpServer m_HttpServer;
    private HttpRedirectServer m_HttpRedirectServer;

    private byte[] m_Packet;
    private IPHeader m_IPHeader;
    private TCPHeader m_TCPHeader;
    private UDPHeader m_UDPHeader;
    private ByteBuffer m_DNSBuffer;
    private Handler m_Handler;
    private long m_SentBytes;
    private long m_ReceivedBytes;
    private String[] m_Blacklist;

    public LocalVpnService() {
        ID++;
        m_Handler = new Handler();
        m_Packet = new byte[20000];
        m_IPHeader = new IPHeader(m_Packet, 0);
        m_TCPHeader = new TCPHeader(m_Packet, 20);
        m_UDPHeader = new UDPHeader(m_Packet, 20);
        m_DNSBuffer = ((ByteBuffer) ByteBuffer.wrap(m_Packet).position(28)).slice();
        Instance = this;

        Log.d("VpnProxy", "New VPNService" + ID);
    }

    public static void addOnStatusChangedListener(onStatusChangedListener listener) {
        if (!m_OnStatusChangedListeners.containsKey(listener)) {
            m_OnStatusChangedListeners.put(listener, 1);
        }
    }

    public static void removeOnStatusChangedListener(onStatusChangedListener listener) {
        if (m_OnStatusChangedListeners.containsKey(listener)) {
            m_OnStatusChangedListeners.remove(listener);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            m_TcpProxyServer = new TcpProxyServer(0);
            m_TcpProxyServer.start();
            writeLog("LocalTcpServer started.");

            m_DnsProxy = new DnsProxy();
            m_DnsProxy.start();
        } catch (Exception e) {
            writeLog("Failed to start TCP/DNS Proxy");
        }

        startLocalHttpServers();

        super.onCreate();
    }

    /**
     * 起本地 HTTP 跳转服务（微信里打开 http://127.0.0.2:8284/xxx 才会走到这里）。
     *
     * 端口被占用（上一次会话没清理干净）时只记日志，不影响 VPN 本身。
     */
    private void startLocalHttpServers() {
        try {
            if (m_HttpServer == null) m_HttpServer = new HttpServer();
            m_HttpServer.start();
            writeLog("LocalHttpServer started on %d", HttpServer.Port);
        } catch (Exception e) {
            Log.w(TAG, "start HttpServer failed", e);
            writeLog("本地跳转服务启动失败: %s", e.toString());
        }
        try {
            if (m_HttpRedirectServer == null) m_HttpRedirectServer = new HttpRedirectServer();
            m_HttpRedirectServer.start();
            writeLog("LocalRedirectServer started on %d", HttpRedirectServer.Port);
        } catch (Exception e) {
            Log.w(TAG, "start HttpRedirectServer failed", e);
            writeLog("本地回调服务启动失败: %s", e.toString());
        }
    }

    private void stopLocalHttpServers() {
        try {
            if (m_HttpServer != null) {
                m_HttpServer.stop();
                m_HttpServer = null;
            }
        } catch (Exception e) {
            // ignore
        }
        try {
            if (m_HttpRedirectServer != null) {
                m_HttpRedirectServer.stop();
                m_HttpRedirectServer = null;
            }
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * 把本服务提升为前台服务。
     *
     * 不提升就会被系统在应用退到后台约 1 分钟后停掉（VPN 掉线、微信打不开链接）。
     * Android 13+ 若用户没给通知权限，通知不显示但前台服务照常生效，所以这里不强制拦。
     */
    private void startVpnForeground() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
                NotificationChannel channel = new NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        getString(R.string.vpn_capture_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription(getString(R.string.vpn_capture_channel_desc));
                channel.setShowBadge(false);
                channel.setSound(null, null);
                nm.createNotificationChannel(channel);
            }

            Intent intent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

            Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.kiba_alpha)
                    .setContentTitle(getString(R.string.vpn_capture_notification_title))
                    .setContentText(getString(R.string.vpn_capture_notification_text))
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .setSilent(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .build();

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    : 0;
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type);
            Log.d(TAG, "startForeground ok, type=" + type);
        } catch (Exception e) {
            // 前台化失败不能拖死抓包：退化成普通 Service 至少还能跑一阵
            Log.w(TAG, "startForeground failed", e);
            writeLog("前台服务启动失败: %s", e.toString());
        }
    }

    private void stopVpnForeground() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        } catch (Exception e) {
            // ignore
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        // 必须先前台化再干活：Android 要求 startForegroundService 后在 5s 内调用 startForeground
        startVpnForeground();
        IsRunning = true;
        // Start a new session by creating a new thread.
        if (m_VPNThread == null || !m_VPNThread.isAlive()) {
            m_VPNThread = new Thread(this, "VPNServiceThread");
            m_VPNThread.start();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        String action = intent.getAction();
        if (action.equals(VpnService.SERVICE_INTERFACE)) {
            return super.onBind(intent);
        }
        return null;
    }

    private void onStatusChanged(final String status, final boolean isRunning) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                for (Map.Entry<onStatusChangedListener, Object> entry : m_OnStatusChangedListeners.entrySet()) {
                    entry.getKey().onStatusChanged(status, isRunning);
                }
            }
        });
    }

    public void writeLog(final String format, Object... args) {
        final String logString = String.format(format, args);
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                for (Map.Entry<onStatusChangedListener, Object> entry : m_OnStatusChangedListeners.entrySet()) {
                    entry.getKey().onLogReceived(logString);
                }
            }
        });
    }

    public void sendUDPPacket(IPHeader ipHeader, UDPHeader udpHeader) {
        try {
            CommonMethods.ComputeUDPChecksum(ipHeader, udpHeader);
            this.m_VPNOutputStream.write(ipHeader.m_Data, ipHeader.m_Offset, ipHeader.getTotalLength());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    String getAppInstallID() {
        SharedPreferences preferences = getSharedPreferences(TAG, MODE_PRIVATE);
        String appInstallID = preferences.getString("AppInstallID", null);
        if (appInstallID == null || appInstallID.isEmpty()) {
            appInstallID = UUID.randomUUID().toString();
            Editor editor = preferences.edit();
            editor.putString("AppInstallID", appInstallID);
            editor.commit();
        }
        return appInstallID;
    }

    String getVersionName() {
        try {
            PackageManager packageManager = getPackageManager();
            PackageInfo packInfo = packageManager.getPackageInfo(getPackageName(), 0);
            String version = packInfo.versionName;
            return version;
        } catch (Exception e) {
            return "0.0";
        }
    }

    public String configDirFor(Context context, String suffix) {
        return new File(context.getFilesDir().getAbsolutePath(), ".lantern" + suffix).getAbsolutePath();
    }

    @Override
    public synchronized void run() {
        try {
            Log.d(TAG, "VPNService work thread is running... " + ID);

            ProxyConfig.AppInstallID = getAppInstallID();
            ProxyConfig.AppVersion = getVersionName();
            writeLog("Android version: %s", Build.VERSION.RELEASE);
            writeLog("App version: %s", ProxyConfig.AppVersion);

            waitUntilPreapred();

            runVPN();

        } catch (InterruptedException e) {
            Log.e(TAG, "Exception", e);
        } catch (Exception e) {
            e.printStackTrace();
            writeLog("Fatal error: %s", e.toString());
        }

        writeLog("VpnProxy terminated.");
        dispose();
    }

    private void runVPN() throws Exception {
        this.m_VPNInterface = establishVPN();
        this.m_VPNOutputStream = new FileOutputStream(m_VPNInterface.getFileDescriptor());
        FileInputStream in = new FileInputStream(m_VPNInterface.getFileDescriptor());
        try {
            while (IsRunning) {
                boolean idle = true;
                int size;
                try {
                    size = in.read(m_Packet);
                } catch (IOException readError) {
                    // 主动 requestStop() 会关掉网卡，这里抛 IOException 属预期，不当错误上报
                    if (!IsRunning) break;
                    throw readError;
                }
                if (size > 0) {
                    if (m_DnsProxy.Stopped || m_TcpProxyServer.Stopped) {
                        in.close();
                        throw new Exception("LocalServer stopped.");
                    }
                    try {
                        onIPPacketReceived(m_IPHeader, size);
                        idle = false;
                    } catch (IOException ex) {
                        Log.e(TAG, "IOException when processing IP packet", ex);
                    }
                }
                if (idle) {
                    Thread.sleep(100);
                }
            }
        } finally {
            in.close();
        }
    }

    void onIPPacketReceived(IPHeader ipHeader, int size) throws IOException {
        switch (ipHeader.getProtocol()) {
            case IPHeader.TCP:
                TCPHeader tcpHeader = m_TCPHeader;
                tcpHeader.m_Offset = ipHeader.getHeaderLength();
                if (ipHeader.getSourceIP() == LOCAL_IP) {
                    if (tcpHeader.getSourcePort() == m_TcpProxyServer.Port) {
                        NatSession session = NatSessionManager.getSession(tcpHeader.getDestinationPort());
                        if (session != null) {
                            ipHeader.setSourceIP(ipHeader.getDestinationIP());
                            tcpHeader.setSourcePort(session.RemotePort);
                            ipHeader.setDestinationIP(LOCAL_IP);

                            CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader);
                            m_VPNOutputStream.write(ipHeader.m_Data, ipHeader.m_Offset, size);
                            m_ReceivedBytes += size;
                        } else {
                            if (ProxyConfig.IS_DEBUG)
                                Log.d(TAG, "NoSession: " +
                                        ipHeader.toString() + " " +
                                        tcpHeader.toString());
                        }
                    } else {
                        int portKey = tcpHeader.getSourcePort();
                        NatSession session = NatSessionManager.getSession(portKey);
                        if (session == null || session.RemoteIP != ipHeader.getDestinationIP() || session.RemotePort != tcpHeader.getDestinationPort()) {
                            session = NatSessionManager.createSession(portKey, ipHeader.getDestinationIP(), tcpHeader.getDestinationPort());
                        }

                        session.LastNanoTime = System.nanoTime();
                        session.PacketSent++;

                        int tcpDataSize = ipHeader.getDataLength() - tcpHeader.getHeaderLength();
                        if (session.PacketSent == 2 && tcpDataSize == 0) {
                            return;
                        }

                        if (session.BytesSent == 0 && tcpDataSize > 10) {
                            int dataOffset = tcpHeader.m_Offset + tcpHeader.getHeaderLength();
                            String host = HttpHostHeaderParser.parseHost(tcpHeader.m_Data, dataOffset, tcpDataSize);
                            if (host != null) {
                                session.RemoteHost = host;
                            }
                        }

                        ipHeader.setSourceIP(ipHeader.getDestinationIP());
                        ipHeader.setDestinationIP(LOCAL_IP);
                        tcpHeader.setDestinationPort(m_TcpProxyServer.Port);

                        CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader);
                        m_VPNOutputStream.write(ipHeader.m_Data, ipHeader.m_Offset, size);
                        session.BytesSent += tcpDataSize;
                        m_SentBytes += size;
                    }
                }
                break;
            case IPHeader.UDP:
                UDPHeader udpHeader = m_UDPHeader;
                udpHeader.m_Offset = ipHeader.getHeaderLength();
                if (ipHeader.getSourceIP() == LOCAL_IP && udpHeader.getDestinationPort() == 53) {
                    m_DNSBuffer.clear();
                    m_DNSBuffer.limit(ipHeader.getDataLength() - 8);
                    DnsPacket dnsPacket = DnsPacket.FromBytes(m_DNSBuffer);
                    if (dnsPacket != null && dnsPacket.Header.QuestionCount > 0) {
                        m_DnsProxy.onDnsRequestReceived(ipHeader, udpHeader, dnsPacket);
                    }
                }
                break;
        }
    }

    private void waitUntilPreapred() {
        while (prepare(this) != null) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // Ignore
            }
        }
    }

    private ParcelFileDescriptor establishVPN() throws Exception {

        NatSessionManager.clearAllSessions();

        Builder builder = new Builder();
        builder.setMtu(ProxyConfig.Instance.getMTU());

        ProxyConfig.IPAddress ipAddress = ProxyConfig.Instance.getDefaultLocalIP();
        LOCAL_IP = CommonMethods.ipStringToInt(ipAddress.Address);

        builder.addAddress(ipAddress.Address, ipAddress.PrefixLength);
        if (ProxyConfig.IS_DEBUG)
            Log.d(TAG, String.format("addAddress: %s/%d\n", ipAddress.Address, ipAddress.PrefixLength));

        if (m_Blacklist == null) {
            m_Blacklist = getResources().getStringArray(R.array.black_list);
        }

        ProxyConfig.Instance.resetDomain(m_Blacklist);

        for (String routeAddress : getResources().getStringArray(R.array.bypass_private_route)) {
            String[] addr = routeAddress.split("/");
            builder.addRoute(addr[0], Integer.parseInt(addr[1]));
        }

        builder.addRoute(CommonMethods.ipIntToString(ProxyConfig.FAKE_NETWORK_IP), 16);

        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        builder.setConfigureIntent(pendingIntent);

        builder.setSession(ProxyConfig.Instance.getSessionName());

        ParcelFileDescriptor pfdDescriptor = builder.establish();
        onStatusChanged(ProxyConfig.Instance.getSessionName() + " " + getString(R.string.vpn_connected_status), true);
        return pfdDescriptor;
    }

    /**
     * 关闭虚拟网卡。
     *
     * 关闭后 VPN 读循环的 `in.read()` 会立刻抛 IOException 并退出，VPN 才算真断开；
     * 只把 IsRunning 置 false 不会唤醒阻塞中的 read。
     */
    private synchronized void closeVpnInterface() {
        try {
            if (m_VPNInterface != null) {
                m_VPNInterface.close();
                m_VPNInterface = null;
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private synchronized void dispose() {

        onStatusChanged(ProxyConfig.Instance.getSessionName() + " " + getString(R.string.vpn_disconnected_status), false);

        IsRunning = false;
        closeVpnInterface();

        try {
            if (m_VPNOutputStream != null) {
                m_VPNOutputStream.close();
                m_VPNOutputStream = null;
            }
        } catch (Exception e) {
            // ignore
        }

//        try {
//            Lantern.RemoveOverrides();
//        } catch (Exception e) {
//            // Ignore
//        }

        if (m_VPNThread != null) {
            m_VPNThread.interrupt();
            m_VPNThread = null;
        }

        // VPN 断开就撤掉常驻通知，别让「机台抓包进行中」一直挂在通知栏
        stopVpnForeground();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "VPNService(%s) destroyed: " + ID);
        if (IsRunning) dispose();
        stopVpnForeground();
        stopLocalHttpServers();
        try {
            // ֹͣTcpServer
            if (m_TcpProxyServer != null) {
                m_TcpProxyServer.stop();
                m_TcpProxyServer = null;
                // writeLog("LocalTcpServer stopped.");
            }
        } catch (Exception e) {
            // ignore
        }

        try {
            // DnsProxy
            if (m_DnsProxy != null) {
                m_DnsProxy.stop();
                m_DnsProxy = null;
                // writeLog("LocalDnsProxy stopped.");
            }
        } catch (Exception e) {
            // ignore
        }
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        super.onRevoke();
        // 系统撤销（用户在其他 App 里断开 VPN / 被别的 VPN 抢占）：彻底收摊，别留半死不活的服务
        writeLog("VPN 被系统撤销");
        IsRunning = false;
        dispose();
        stopSelf();
    }

    public interface onStatusChangedListener {
        void onStatusChanged(String status, Boolean isRunning);
        void onLogReceived(String logString);
    }

}
