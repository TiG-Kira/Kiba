package com.kira.kiba.core.vpn.crawler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

public class SimpleCookieJar implements CookieJar {
    private final Map<String, List<Cookie>> cookieStore = new HashMap<String, List<Cookie>>();
    private final Object lock = new Object();

    @Override
    public void saveFromResponse(HttpUrl httpUrl, List<Cookie> newCookies) {
        synchronized (lock) {
            HashMap<String, Cookie> map = new HashMap<>();
            List<Cookie> oldCookies = cookieStore.get(httpUrl.host());
            if (oldCookies != null) {
                for (Cookie cookie : oldCookies) {
                    map.put(cookie.name(), cookie);
                }
            }
            // Override old cookie with same name
            if (newCookies != null) {
                for (Cookie cookie : newCookies) {
                    map.put(cookie.name(), cookie);
                }
            }
            List<Cookie> mergedList = new ArrayList<Cookie>();
            for (Map.Entry<String, Cookie> pair : map.entrySet()) {
                mergedList.add(pair.getValue());
            }
            cookieStore.put(httpUrl.host(), mergedList);
        }
    }

    @Override
    public List<Cookie> loadForRequest(HttpUrl httpUrl) {
        synchronized (lock) {
            List<Cookie> cookies = cookieStore.get(httpUrl.host());
            return cookies != null ? cookies : new ArrayList<Cookie>();
        }
    }

    public void clearCookieStroe() {
        synchronized (lock) {
            this.cookieStore.clear();
        }
    }

    /** 取指定 host 的 cookie 列表（副本）。 */
    public List<Cookie> getCookies(String host) {
        synchronized (lock) {
            List<Cookie> cookies = cookieStore.get(host);
            return cookies != null ? new ArrayList<Cookie>(cookies) : new ArrayList<Cookie>();
        }
    }

    /**
     * 取指定 host 的首个 cookie 值。
     *
     * 中二节奏抓取需要先 POST `/record/musicGenre/send{Difficulty}`，表单带 `token` 字段
     * （官方 proxy 实现取的就是会话首个 cookie 的 value）。会话 cookie 也可能落在
     * 授权域而非成绩域下，取不到时兜底返回 jar 内任意第一个 cookie。
     */
    public String getFirstCookieValue(String host) {
        List<Cookie> list = getCookies(host);
        if (!list.isEmpty()) return list.get(0).value();
        synchronized (lock) {
            for (List<Cookie> cookies : cookieStore.values()) {
                if (!cookies.isEmpty()) return cookies.get(0).value();
            }
        }
        return null;
    }
}
