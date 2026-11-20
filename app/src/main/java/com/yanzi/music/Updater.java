package com.yanzi.music;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * APP 内更新（数据源：GitHub Releases https://github.com/zxsjc/YanZiMusic/releases）。
 *
 * 流程：查 releases/latest → 用 tag 里的版本号跟当前 versionName 比 → 有新版才弹窗 →
 * 下载 .apk 到应用缓存目录（带进度）→ 校验包名 / versionCode → 走本应用自带的
 * {@link ApkProvider} 生成 content:// uri → 系统安装器。
 *
 * 几个刻意的取舍：
 * - 不在页面里做 HTTP：WebView 以 file:// 打开，跨域访问 api.github.com 要看 CORS 和
 *   allowUniversalAccessFromFileURLs，原生直连更可靠，也不受页面脚本错误影响。
 * - 不用 DownloadManager：它下载完的 uri 属于系统下载 provider，交给安装器经常拿不到读权限，
 *   而且进度回不到我们的界面上。这里自己下到 cacheDir，配合极简 ContentProvider 授权。
 * - 下载失败（国内网络对 github release 域名的直连时通时不通）不是死路：给「打开发布页」兜底。
 * - 自动检查是静默的：没网、请求失败、已是最新都只在手动检查时说话，不打扰启动起播。
 */
final class Updater {

    private static final String OWNER = "zxsjc";
    private static final String REPO  = "YanZiMusic";
    private static final String API_LATEST = "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest";
    private static final String WEB_PAGE   = "https://github.com/" + OWNER + "/" + REPO + "/releases";
    private static final String UA = "YanZiMusic-Updater/1.0";

    /** 自动检查的最小间隔：同一进程里 6 小时内不重复请求（手动检查不受限制）。 */
    private static final long AUTO_INTERVAL_MS = 6L * 60 * 60 * 1000;

    /**
     * 下载线路：第 1 条直连 GitHub，其余是国内常用的 GitHub 加速镜像（前缀 + 原始完整地址）。
     * 实测：release-assets.githubusercontent.com 在国内被解析到 127.0.0.1，直连必然失败，
     * 手机与电脑都一样；ghfast.top 实测可取回完整 APK，所以按顺序自动切换。
     * 安全上不必担心镜像篡改：系统安装器会校验签名，签名不符的包根本装不上（升级装不上）。
     */
    private static final String[] DOWNLOAD_SOURCES = {
            "",
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://ghproxy.net/"
    };

    private static boolean checking = false;
    private static long lastCheckAt = 0;
    /** 已下载完、等用户把「允许安装应用」开关打开后自动续装的包路径。 */
    private static String pendingApk = null;
    /** 进度对话框引用，退出界面时关掉，避免窗口泄漏。 */
    private static AlertDialog aliveDialog = null;

    private Updater() {
    }

    /** 一条 release 里我们用得上的字段。 */
    private static final class Release {
        String tag = "";
        String name = "";
        String body = "";
        String published = "";
        String apkName = "";
        String apkUrl = "";
        long size = 0;
    }

    /* ============================ 对外入口 ============================ */

    /** 页面/原生都可以调的手动检查：会明确告诉用户结果（已最新 / 失败）。 */
    static void check(final Activity act, final boolean manual) {
        if (act == null || act.isFinishing()) {
            return;
        }
        if (checking) {
            if (manual) {
                toast(act, "正在检查更新，请稍候…");
            }
            return;
        }
        checking = true;
        lastCheckAt = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Release rel = null;
                String err = null;
                try {
                    rel = fetchLatest();
                } catch (Exception e) {
                    err = describe(e);
                }
                final Release fRel = rel;
                final String fErr = err;
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        checking = false;
                        if (act.isFinishing()) {
                            return;
                        }
                        if (fRel == null) {
                            if (manual) {
                                simple(act, "检查更新失败",
                                        "连不上更新服务器（" + fErr + "）。\n"
                                                + "可以稍后再试，或者打开发布页手动下载。", true);
                            }
                            return;
                        }
                        handleRelease(act, fRel, manual);
                    }
                });
            }
        }).start();
    }

    /** 启动后自动检查：静默，只在新版本存在时弹窗。 */
    static void checkAuto(Activity act) {
        if (System.currentTimeMillis() - lastCheckAt < AUTO_INTERVAL_MS) {
            return;
        }
        check(act, false);
    }

    /** 回到前台时调用：如果刚下载完但卡在「允许安装应用」授权，这里自动续装。 */
    static void onResume(Activity act) {
        if (act == null || pendingApk == null) {
            return;
        }
        File f = new File(pendingApk);
        if (!f.exists()) {
            pendingApk = null;
            return;
        }
        PackageManager pm = act.getPackageManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !pm.canRequestPackageInstalls()) {
            return;   // 用户还没把开关打开，继续等
        }
        pendingApk = null;
        install(act, f);
    }

    /** 界面销毁时收尾。 */
    static void onDestroy() {
        closeDialog();
        checking = false;
    }

    /** 当前版本号文本，供页面显示或调试用。 */
    static String versionText(Context c) {
        return currentVersionName(c);
    }

    /* ============================ 查版本 ============================ */

    private static Release fetchLatest() throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(API_LATEST).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception("HTTP " + code + (code == 403 ? "（请求过于频繁）" : ""));
            }
            in = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            return parseRelease(sb.toString());
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            if (conn != null) conn.disconnect();
        }
    }

    private static Release parseRelease(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        Release r = new Release();
        r.tag = o.optString("tag_name", "");
        r.name = o.optString("name", "");
        r.body = o.optString("body", "");
        r.published = o.optString("published_at", "");
        JSONArray assets = o.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) {
                    continue;
                }
                String name = a.optString("name", "");
                if (name.toLowerCase().endsWith(".apk")) {
                    r.apkName = name;
                    r.apkUrl = a.optString("browser_download_url", "");
                    r.size = a.optLong("size", 0);
                    break;
                }
            }
        }
        return r;
    }

    private static void handleRelease(final Activity act, Release rel, boolean manual) {
        String curName = currentVersionName(act);
        String newVer = extractVersion(rel.tag.length() > 0 ? rel.tag : rel.name);
        if (rel.apkUrl.length() == 0) {
            if (manual) {
                simple(act, "没有可下载的安装包",
                        "最新发布 " + rel.tag + " 里没有 .apk 附件。\n可以打开发布页手动下载。", true);
            }
            return;
        }
        if (newVer.length() == 0 || !isNewer(curName, newVer)) {
            if (manual) {
                simple(act, "已是最新版本",
                        "当前版本 " + curName + "，发布页最新为 " + (rel.tag.length() > 0 ? rel.tag : "同版本") + "。",
                        false);
            }
            return;
        }
        prompt(act, rel, curName, newVer);
    }

    private static void prompt(final Activity act, final Release rel, String curName, String newVer) {
        StringBuilder msg = new StringBuilder();
        msg.append("当前版本：").append(curName);
        msg.append("\n最新版本：").append(rel.tag.length() > 0 ? rel.tag : ("v" + newVer));
        if (rel.size > 0) {
            msg.append("\n安装包：").append(rel.apkName).append("（").append(mb(rel.size)).append("）");
        }
        String body = cleanBody(rel.body);
        if (body.length() > 0) {
            if (body.length() > 360) {
                body = body.substring(0, 360) + "…";
            }
            msg.append("\n\n更新说明：\n").append(body);
        }
        msg.append("\n\n下载完成后会自动弹出系统安装界面。");

        new AlertDialog.Builder(act)
                .setTitle("发现新版本 " + (rel.tag.length() > 0 ? rel.tag : newVer))
                .setMessage(msg.toString())
                .setPositiveButton("立即更新", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        download(act, rel);
                    }
                })
                .setNeutralButton("发布页", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openPage(act);
                    }
                })
                .setNegativeButton("以后再说", null)
                .show();
    }

    /* ============================ 下载 ============================ */

    private static void download(final Activity act, final Release rel) {
        final File out = new File(act.getCacheDir(),
                "yanzi_update_" + sanitize(extractVersion(rel.tag)) + ".apk");

        final LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18,
                act.getResources().getDisplayMetrics()));
        box.setPadding(pad, pad, pad, pad);
        final TextView tv = new TextView(act);
        tv.setText("正在准备下载…");
        box.addView(tv);
        final ProgressBar pb = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad / 2;
        box.addView(pb, lp);

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("下载更新")
                .setView(box)
                .setCancelable(false)
                .create();
        closeDialog();
        aliveDialog = dlg;
        dlg.show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                Exception last = null;
                boolean ok = false;
                final int totalLines = DOWNLOAD_SOURCES.length;
                for (int i = 0; i < totalLines && !ok; i++) {
                    final int line = i + 1;
                    final String url = DOWNLOAD_SOURCES[i] + rel.apkUrl;
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            pb.setProgress(0);
                            tv.setText("连接第 " + line + "/" + totalLines + " 条下载线路…");
                        }
                    });
                    try {
                        downloadOnce(act, url, out, rel.size, pb, tv, line, totalLines);
                        ok = true;
                    } catch (Exception e) {
                        last = e;
                        try {
                            out.delete();
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (!ok) {
                    final Exception fErr = last;
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            closeDialog();
                            if (act.isFinishing()) {
                                return;
                            }
                            simple(act, "下载失败",
                                    "已试过 " + totalLines + " 条下载线路，都没成功（最后一条：" + describe(fErr) + "）。\n"
                                            + "可以稍后再试，或打开发布页用浏览器手动下载。", true);
                        }
                    });
                    return;
                }
                final long size = out.length();
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        closeDialog();
                        if (act.isFinishing()) {
                            return;
                        }
                        if (size <= 0) {
                            simple(act, "下载失败", "下载到 0 字节，请稍后再试。", true);
                            return;
                        }
                        install(act, out);
                    }
                });
            }
        }).start();
    }

    /** 从一条线路（镜像前缀 + 原始地址；前缀为空即直连）把 APK 下到 out，失败就抛异常交给上一条换线路。 */
    private static void downloadOnce(final Activity act, String url, File out, long hint,
                                     final ProgressBar pb, final TextView tv,
                                     final int line, final int totalLines) throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream fo = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception("HTTP " + code);
            }
            // minSdk 23：getContentLengthLong() 是 API 24 才有的，这里用 int 版本再兜底
            int len = conn.getContentLength();
            final long total = len > 0 ? (long) len : hint;
            in = conn.getInputStream();
            fo = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            long got = 0;
            int n;
            int lastPct = -1;
            while ((n = in.read(buf)) > 0) {
                fo.write(buf, 0, n);
                got += n;
                if (total > 0) {
                    int pct = (int) (got * 100L / total);
                    if (pct > 100) {
                        pct = 100;
                    }
                    if (pct != lastPct) {
                        lastPct = pct;
                        final int fp = pct;
                        final long fg = got;
                        act.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                pb.setProgress(fp);
                                tv.setText("第 " + line + "/" + totalLines + " 条线路下载中… "
                                        + fp + "%（" + mb(fg) + " / " + mb(total) + "）");
                            }
                        });
                    }
                }
            }
            fo.flush();
        } finally {
            try {
                if (fo != null) fo.close();
            } catch (Exception ignored) {
            }
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /* ============================ 安装 ============================ */

    private static void install(final Activity act, final File apk) {
        PackageManager pm = act.getPackageManager();
        PackageInfo info;
        try {
            info = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        } catch (Exception e) {
            info = null;
        }
        if (info == null) {
            simple(act, "安装包异常", "下载的 APK 无法解析（可能下载不完整），已删除，请重试。", false);
            try {
                apk.delete();
            } catch (Exception ignored) {
            }
            return;
        }
        if (!act.getPackageName().equals(info.packageName)) {
            simple(act, "安装包不匹配",
                    "下载到的包名是 " + info.packageName + "，不是燕子音乐（" + act.getPackageName() + "），已删除。", false);
            try {
                apk.delete();
            } catch (Exception ignored) {
            }
            return;
        }
        long newCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode() : info.versionCode;
        long curCode = currentVersionCode(act);
        if (newCode <= curCode) {
            simple(act, "版本不比当前新",
                    "下载到的 APK versionCode = " + newCode + "，当前 = " + curCode
                            + "，系统会拒绝降级安装，已删除。\n"
                            + "请确认 GitHub 上放的是新编译的安装包。", false);
            try {
                apk.delete();
            } catch (Exception ignored) {
            }
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !pm.canRequestPackageInstalls()) {
            pendingApk = apk.getAbsolutePath();
            new AlertDialog.Builder(act)
                    .setTitle("需要允许安装应用")
                    .setMessage("系统要求先允许「燕子音乐」安装应用。\n点「去设置」打开开关，返回本应用后会自动继续安装。")
                    .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            openInstallSettings(act);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        launchInstaller(act, apk);
    }

    private static void launchInstaller(Activity act, File apk) {
        try {
            Uri uri = ApkProvider.uriFor(act, apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception e) {
            simple(act, "打不开安装界面",
                    "系统里没有可用的安装器（" + describe(e) + "）。\n安装包已保留在：" + apk.getAbsolutePath(), false);
        }
    }

    /* ============================ 小工具 ============================ */

    private static String currentVersionName(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName == null ? "未知" : pi.versionName;
        } catch (Exception e) {
            return "未知";
        }
    }

    private static long currentVersionCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 从 "v2.0" / "燕子音乐 1.7.33" 这类字符串里抠出第一个版本号数字串。 */
    private static String extractVersion(String s) {
        if (s == null) {
            return "";
        }
        Matcher m = Pattern.compile("\\d+(?:\\.\\d+)*").matcher(s);
        return m.find() ? m.group() : "";
    }

    /** 逐段数字比较：a 比 b 旧返回 true。 */
    private static boolean isNewer(String current, String candidate) {
        String[] a = current.split("\\.");
        String[] b = candidate.split("\\.");
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            long x = 0;
            long y = 0;
            try {
                x = i < a.length ? Long.parseLong(a[i].trim()) : 0;
            } catch (Exception ignored) {
            }
            try {
                y = i < b.length ? Long.parseLong(b[i].trim()) : 0;
            } catch (Exception ignored) {
            }
            if (y > x) {
                return true;
            }
            if (y < x) {
                return false;
            }
        }
        return false;
    }

    private static String cleanBody(String body) {
        if (body == null) {
            return "";
        }
        String s = body.replace("\r", "").trim();
        // 里面常是带 ### / - 的 markdown，去掉符号留正文，够用户判断更新内容即可
        s = s.replaceAll("(?m)^#{1,6}\\s*", "");
        s = s.replaceAll("[`*_>]", "");
        s = s.replaceAll("\n{3,}", "\n\n");
        return s;
    }

    private static String sanitize(String s) {
        if (s == null || s.length() == 0) {
            return "latest";
        }
        return s.replaceAll("[^0-9A-Za-z.]", "_");
    }

    private static String mb(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(java.util.Locale.US, "%.0f KB", kb);
        }
        return String.format(java.util.Locale.US, "%.2f MB", kb / 1024.0);
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        if (m == null || m.length() == 0) {
            return e.getClass().getSimpleName();
        }
        return m;
    }

    private static void closeDialog() {
        if (aliveDialog != null) {
            try {
                aliveDialog.dismiss();
            } catch (Exception ignored) {
            }
            aliveDialog = null;
        }
    }

    private static void simple(Activity act, String title, String msg, boolean withPageButton) {
        if (act.isFinishing()) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(act)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("知道了", null);
        if (withPageButton) {
            b.setNeutralButton("打开发布页", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    openPage(act);
                }
            });
        }
        b.show();
    }

    private static void openPage(Activity act) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(WEB_PAGE));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception e) {
            toast(act, "没有可用的浏览器打开 " + WEB_PAGE);
        }
    }

    private static void openInstallSettings(Activity act) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + act.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception e) {
            try {
                act.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            } catch (Exception ignored) {
                toast(act, "请到系统设置里允许本应用安装应用");
            }
        }
    }

    private static void toast(Activity act, String msg) {
        try {
            Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }
}
