package com.eratw.port.android.dbg;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.util.Date;

/**
 * **不**经 instrumentation 的网络读数探针（跑在装置包自己的进程与 UID 里）。
 *
 * 为什么要有它：`am instrument` 要求装置包与目标应用**同章**，机上应用是发布章、
 * AGP 的 androidTest 只有 debug 变体 ⇒ 注不进去。但「这台 ROM 缺 `ACCESS_NETWORK_STATE`
 * 时到底把 `getActiveNetwork()` 交成什么」只与**调用方有没有这条权限**有关，与章无关，
 * 于是装置包自己就是最干净的实验体：manifest 里不写这条权限跑一次（A）、写上再跑一次（B），
 * 两次读数一比，根因与修复各证一半。
 *
 * 为什么是 Java 而不是 Kotlin：装置包**不随带 Kotlin 运行库**（androidTest 的类平时跑在目标
 * 应用的进程里，std 由应用提供），独立起 Activity 时加载 Kotlin 类直接
 * `ClassNotFoundException: kotlin.Pair`（2026-11-03 真机实测）。这个文件因此只用 Java。
 *
 * 读数落 `<外置 filesDir>/netprobe-self.txt`（vivo 会吞 logcat，同 DeviceSweepTest 的口径），
 * 每条前缀 intent extra `tag`（A/B）与时刻，追加不覆盖。
 */
public class NetProbeActivity extends Activity {

    private static final String NET = "android.permission.ACCESS_NETWORK_STATE";
    private static final String TAG = "EratwNetProbe";

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        final String tag = getIntent() == null ? "?" : getIntent().getStringExtra("tag");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String text = read(NetProbeActivity.this, tag == null ? "?" : tag);
                try {
                    File root = getExternalFilesDir(null);
                    if (root == null) {
                        Log.e(TAG, "外置目录不可用：" + text);
                    } else {
                        if (!root.exists() && !root.mkdirs()) Log.w(TAG, "建目录失败：" + root);
                        File out = new File(root, "netprobe-self.txt");
                        FileWriter writer = new FileWriter(out, true);
                        writer.write(text);
                        writer.close();
                        Log.i(TAG, text);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "写读数失败", t);
                }
                finish();
            }
        }).start();
    }

    /** 抄一遍现场：权限有没有、三个 API 各交什么、按 isUnmetered 的判据算出来是哪一边。 */
    static String read(Context context, String tag) {
        StringBuilder sb = new StringBuilder();
        sb.append("### tag=").append(tag).append(" at=").append(new Date()).append('\n');
        boolean granted = context.checkPermission(NET, Process.myPid(), Process.myUid())
            == PackageManager.PERMISSION_GRANTED;
        sb.append("package=").append(context.getPackageName())
            .append(" sdk=").append(Build.VERSION.SDK_INT)
            .append(" model=").append(Build.MODEL)
            .append(" granted[").append(NET).append("]=").append(granted).append('\n');

        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return sb.append("ConnectivityManager=null\n").toString();
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return sb.append("legacy activeNetworkInfo=").append(label(attempt(new Call() {
                @Override
                public Object get() {
                    return cm.getActiveNetworkInfo();
                }
            }))).append('\n').toString();
        }

        Outcome network = attempt(new Call() {
            @Override
            public Object get() {
                return cm.getActiveNetwork();
            }
        });
        Outcome caps = network.value == null ? null : attemptCaps(cm, network.value);
        Outcome legacy = attempt(new Call() {
            @Override
            @SuppressWarnings("deprecation")
            public Object get() {
                return cm.getActiveNetworkInfo();
            }
        });
        sb.append("activeNetwork=").append(label(network)).append('\n');
        sb.append("caps=").append(caps == null ? "没网络不查" : label(caps)).append('\n');
        sb.append("legacy=").append(label(legacy)).append('\n');
        // 判据本体 = UpdateManager.isUnmetered 的 API 23+ 分支：Wi-Fi/以太网，或「不是蜂窝」。
        NetworkCapabilities c = caps == null ? null : (caps.value instanceof NetworkCapabilities
            ? (NetworkCapabilities) caps.value : null);
        String unmetered;
        if (c == null) {
            unmetered = "false（判不了→界面按蜂窝处理，多问一次流量）";
        } else {
            unmetered = String.valueOf(
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    || !c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
        }
        return sb.append("wouldBeUnmetered=").append(unmetered).append('\n').toString();
    }

    private static Outcome attemptCaps(final ConnectivityManager cm, final Object network) {
        return attempt(new Call() {
            @Override
            public Object get() {
                return cm.getNetworkCapabilities((android.net.Network) network);
            }
        });
    }

    private static Outcome attempt(Call call) {
        try {
            return new Outcome(call.get(), null);
        } catch (Throwable t) {
            return new Outcome(null, t);
        }
    }

    private static String label(Outcome o) {
        if (o.error != null) return "抛" + o.error.getClass().getSimpleName() + ":" + o.error.getMessage();
        if (o.value == null) return "null";
        if (o.value instanceof NetworkCapabilities) return summary((NetworkCapabilities) o.value);
        if (o.value instanceof NetworkInfo) return String.valueOf(o.value);
        return String.valueOf(o.value);
    }

    private static String summary(NetworkCapabilities caps) {
        String[] names = {"WIFI", "ETHERNET", "CELLULAR", "VPN"};
        int[] ids = {
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_ETHERNET,
            NetworkCapabilities.TRANSPORT_CELLULAR,
            NetworkCapabilities.TRANSPORT_VPN,
        };
        StringBuilder transports = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            if (caps.hasTransport(ids[i])) {
                if (transports.length() > 0) transports.append(',');
                transports.append(names[i]);
            }
        }
        return "(transports=" + (transports.length() == 0 ? "NONE" : transports)
            + ", notMetered=" + caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) + ")";
    }

    private interface Call {
        Object get() throws Exception;
    }

    private static final class Outcome {
        final Object value;
        final Throwable error;

        Outcome(Object value, Throwable error) {
            this.value = value;
            this.error = error;
        }
    }
}
