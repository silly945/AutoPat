package com.android.system.update;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Random;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * AutoPat v6.0 —— 微信自动回拍
 *
 * 核心原理（全部经真实设备验证）：
 *   1. 接收信号的唯一可靠来源：jw3.l.Kj(pattedUser, fromUser, talker, template, ts, msgId)
 *      - 别人拍我：Kj("我的wxid", "对方wxid", "会话", "...拍了拍我...", ts, msgId)
 *   2. 判定：Kj 的第一个参数 == 我的 wxid → 我被拍了 → 回拍第二个参数
 *   3. 发送：jw3.l.yj(scene, 对方wxid, 会话) —— 微信自己的拍一拍发起/处理入口
 *
 * 无任何行为限流：不设日限、不设同人冷却、不设夜间静默，概率 100%
 * 仅保留 1500~6000ms 随机延迟，避免 0 延迟机械感。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String PKG_WX = "com.tencent.mm";
    private static final String TAG = "AutoPat";

    /** 总开关：true = 真发送 */
    private static final boolean SEND_ENABLED = true;

    /** 延迟范围（毫秒）：立即回拍，无随机等待 */
    private static final int DELAY_MIN = 0;
    private static final int DELAY_MAX = 1;

    /** 同一会话防抖窗口（只防重复触发，不影响正常连拍） */
    private static final long DEBOUNCE_MS = 300L;

    private static final Random RND = new Random();

    public static Context APP_CTX;
    private static Handler sMain;

    /** 我的 wxid（运行时读取） */
    private static volatile String MY_WXID;

    /** jw3.l 的实例 */
    private static Object PAT_INSTANCE;
    private static Method M_YJ;
    /** ★ 真正的发送入口 Dj(String, Pair) */
    private static Method M_DJ;
    /** ★ 配套方法 Lj(String, Pair) */
    private static Method M_LJ;
    /** ★ Ej(ze5, long) */
    private static Method M_EJ;
    /** ★ Fj(ze5, long) */
    private static Method M_FJ;
    /** ★ 最近一次收到的拍一拍 ze5 对象 */
    private static Object LAST_ZE5;
    /** ★ 微信自己的网络层实例 s（从 doScene 参数抓） */
    private static Object NET_S;
    /** ★ 微信自己的网络回调 u0 */
    private static Object NET_CB;
    /** ★ 微信真实 mw3.b 的请求对象（含完整 BaseRequest），用于复制 */
    private static Object GOLD_REQ;
    /** ★ 微信真实群聊 mw3.b 请求对象（群聊黄金样本） */
    private static Object GOLD_REQ_GROUP;
    /** ★ 是否正在 o 自己的回拍发送中（防止污染黄金样本） */
    static volatile boolean inOurPatBack = false;
    /** ★ 微信真实 mw3.c（拍一拍上下文），用于原生路线 */
    private static Object GOLD_PATCTX;
    /** ★ jw3.l.Mj(long, String) —— 拍一拍本地写库 */
    private static java.lang.reflect.Method M_MJ;
    /** ★ jw3.l 实例（Mj 的 thisObject） */
    private static Object PAT_L_INST;
    /** ★ jw3.l.Nj(nd5.ze5) —— 把 ze5 变成消息对象并入库（灰条的关键） */
    private static java.lang.reflect.Method M_NJ;
    /** ★ af5 字段类型是否已打印 */
    private static volatile boolean AF5_DUMPED = false;
    /** ★ 微信自己序列化出的拍一拍 XML（黄金模板） */
    private static volatile String GOLD_XML;
    /** ★ 黄金 XML 是否已打印 */
    private static volatile boolean XML_DUMPED = false;
    /** ★ 调用栈是否已打印 */
    private static volatile boolean STACK_DUMPED = false;
    /** ★ lw3.i 构造参数是否已打印 */
    private static volatile boolean LW3_DUMPED = false;
    /** ★ lw3.j.l0 参数是否已打印 */
    private static volatile boolean LWJ_DUMPED = false;
    /** ★ lw3.j.l0 方法 */
    private static volatile java.lang.reflect.Method M_LWJ_L0;
    /** ★ 微信真实拍一拍 Map 模板 */
    private static volatile java.util.Map<Object, Object> LWJ_MAP;
    /** ★ 微信真实 p0 存储上下文 */
    private static volatile Object LWJ_P0;
    /** ★ lw3.j 实例（l0 是实例方法） */
    private static volatile Object LWJ_INST;
    /** ★ 自己拍时记录的目标/会话 */
    private static volatile String SELF_PAT_TALKER;
    private static volatile String SELF_PAT_TARGET;

    private static long lastKjTs = 0L;
    private static volatile boolean inited = false;
    /** 断网重试计数 */
    private static int NET_RETRY = 0;

    /** 网络是否可用（基于系统 ConnectivityManager，不产生额外网络请求） */
    private static boolean netOk() {
        try {
            Context c = APP_CTX;
            if (c == null) return true;
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            boolean ok = ni != null && ni.isConnected();
            return ok;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 抓到的真实发送参数 */
    private static java.lang.reflect.Constructor<?> SEND_CTOR;
    private static Object SEND_OBJ;
    /** ★ 微信自己构造 fa6 时填入的 g（msgPointer），作为黄金参考值 */
    private static Object GOLD_G;
    /** ★ jw3.i 实例与 handleMessage —— 拍一拍原生执行入口 */
    private static Object PAT_HANDLER;
    private static Method PAT_HANDLE_MSG;
    private static Class<?> CLS_SEND;

    /** 网络队列 */
    private static Class<?> CLS_QUEUE;
    private static volatile Object QUEUE_INSTANCE;
    private static Method METHOD_J;
    private static Class<?> TYPE_J0, TYPE_J1, TYPE_J2, TYPE_J3;
    private static Method METHOD_SUBMIT;

    // ========================= 入口 =========================
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (!PKG_WX.equals(lp.packageName)) return;
        if (lp.processName != null && !lp.processName.equals(PKG_WX)) return;

        log("=== AutoPat v6.0 加载, SDK=" + Build.VERSION.SDK_INT
                + ", 发送=" + (SEND_ENABLED ? "开" : "关") + " ===");

        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach",
                    Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            APP_CTX = (Context) param.args[0];
                            new Thread(() -> {
                                try {
                                    Thread.sleep(5000);
                                    init(lp.classLoader);
                                } catch (Throwable t) {
                                    log("初始化异常: " + t);
                                }
                            }, "AutoPat-Init").start();
                        }
                    });
        } catch (Throwable t) {
            log("attach hook 失败: " + t);
        }
    }

    private static Handler main() {
        if (sMain != null) return sMain;
        synchronized (MainHook.class) {
            if (sMain == null) {
                Looper l = Looper.getMainLooper();
                if (l == null) l = Looper.myLooper();
                if (l != null) sMain = new Handler(l);
            }
        }
        return sMain;
    }

    // ========================= 初始化 =========================
    private void init(ClassLoader cl) {
        if (inited) return;
        inited = true;

        MY_WXID = readMyWxid();
        log("我的 wxid = " + safe(MY_WXID));

        Class<?> clsPat;
        try {
            clsPat = Class.forName("jw3.l", false, cl);
        } catch (Throwable t) {
            log("ERR jw3.l 加载失败: " + t);
            return;
        }

        // 1) 缓存 jw3.l 单例实例（用于调 yj）
        try {
            for (Field f : clsPat.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        && f.getType() == clsPat) {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v != null) {
                        PAT_INSTANCE = v;
                        log("实例字段: " + f.getName());
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            log("取实例字段异常: " + t);
        }

        // 2) 找 yj 方法
        try {
            for (Method m : clsPat.getDeclaredMethods()) {
                if ("yj".equals(m.getName()) && m.getParameterTypes().length == 3
                        && m.getParameterTypes()[0] == int.class) {
                    m.setAccessible(true);
                    M_YJ = m;
                    log("OK 找到 yj 方法");
                    break;
                }
            }
        } catch (Throwable t) {
            log("找 yj 异常: " + t);
        }

        // 2.1) ★ 找 Dj / Lj 方法（真正的发送入口）
        //     Dj(String, Pair)  —— 发拍一拍
        //     Lj(String, Pair)  —— 配套
        for (Method m : clsPat.getDeclaredMethods()) {
            try {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 2 && ps[0] == String.class
                        && ps[1] == android.util.Pair.class) {
                    m.setAccessible(true);
                    if ("Dj".equals(m.getName())) {
                        M_DJ = m;
                        log("OK 找到 Dj 方法（发送入口）");
                    } else if ("Lj".equals(m.getName())) {
                        M_LJ = m;
                        log("OK 找到 Lj 方法");
                    }
                } else if (ps.length == 2 && ps[0].getName().equals("nd5.ze5")
                        && ps[1] == long.class) {
                    // ★ Ej(ze5, long) 与 Fj(ze5, long)
                    m.setAccessible(true);
                    if ("Ej".equals(m.getName())) {
                        M_EJ = m;
                        log("OK 找到 Ej 方法");
                    } else if ("Fj".equals(m.getName())) {
                        M_FJ = m;
                        log("OK 找到 Fj 方法");
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // 3) 钩 Kj：接收信号的唯一来源
        try {
            Class<?> finalClsPat = clsPat;
            for (Method m : clsPat.getDeclaredMethods()) {
                if (!"Kj".equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 6 || ps[0] != String.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object r = param.getResult();
                            if (r == null) return;
                            String a0 = (String) param.args[0];
                            String a1 = (String) param.args[1];
                            String a2 = (String) param.args[2];
                            // ★ 群聊顺序是 (群ID, 拍人, 被拍人)；单聊是 (被拍人, 拍人, 会话)
                            String patted;
                            String from;
                            String talker;
                            if (a0 != null && a0.endsWith("@chatroom")) {
                                // 群聊：a0=群ID a1=拍人 a2=被拍人
                                talker = a0;
                                from = a1;
                                patted = a2;
                            } else {
                                // 单聊：a0=被拍人 a1=拍人 a2=会话
                                patted = a0;
                                from = a1;
                                talker = a2;
                            }
// ★ 保存 ze5 供 Ej/Fj 使用
                            for (Object az : param.args) {
                                if (az != null && az.getClass().getName().equals("nd5.ze5")) {
                                    LAST_ZE5 = az;
                                }
                            }
                            log("[收到] 被拍=" + safe(patted) + " 拍人=" + safe(from)
                                    + " 会话=" + safe(talker));
                            // ★ 若是「我拍对方」（自己发出的回推），记录正确的模板供本地写库用
                            if (MY_WXID != null && MY_WXID.equals(from)) {
                                SELF_PAT_TALKER = talker;
                                SELF_PAT_TARGET = patted;
                                log("[自己拍·回推] target=" + safe(patted)
                                        + " talker=" + safe(talker));
                            }
                            // ★★★ 打印完整调用栈，找出真正插入消息的方法（不过滤，全打）
                            if (!STACK_DUMPED) {
                                STACK_DUMPED = true;
                                StringBuilder sk = new StringBuilder("★★[调用栈] Kj 上游:");
                                StackTraceElement[] stes = Thread.currentThread().getStackTrace();
                                int cnt = 0;
                                for (StackTraceElement se : stes) {
                                    String cn = se.getClassName();
                                    if (cn.startsWith("java.lang.Thread")
                                            || cn.startsWith("dalvik.system")) {
                                        continue;
                                    }
                                    sk.append(" > ").append(cn).append(".")
                                      .append(se.getMethodName()).append(":")
                                      .append(se.getLineNumber());
                                    if (++cnt >= 45) break;
                                }
                                log(sk.toString());
                            }
                            onPat(patted, from, talker);
                        } catch (Throwable t) {
                            log("Kj 处理异常: " + t);
                        }
                    }
                });
                log("OK 已挂 jw3.l.Kj（接收入口）");
                break;
            }
            // ★★★ 立即反射解析 Nj(nd5.ze5) 和 jw3.l 实例，不依赖运行时缓存
            // ★ 先挂 lw3.j.l0，抓拍一拍消息的处理入口真实参数
            try {
                Class<?> clsLj = Class.forName("lw3.j", false, cl);
                for (java.lang.reflect.Method mm : clsLj.getDeclaredMethods()) {
                    if (!"l0".equals(mm.getName())) continue;
                    mm.setAccessible(true);
                    M_LWJ_L0 = mm;
                    XposedBridge.hookMethod(mm, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (LWJ_DUMPED) return;
                                // ★ 每次都刷新模板（保存微信真实的 Map 与 p0，供回拍写库用）
                                if (param.args.length >= 3 && param.args[1] != null
                                        && param.args[1] instanceof java.util.Map) {
                                    Object fu = ((java.util.Map<?, ?>) param.args[1])
                                            .get(".sysmsg.pat.fromusername");
                                    // 只采「对方拍我」的真实样本，不采我们自己的
                                    if (fu instanceof String && !MY_WXID.equals(fu)) {
                                        LWJ_MAP = new java.util.HashMap<Object, Object>(
                                                (java.util.Map<?, ?>) param.args[1]);
                                        LWJ_P0 = param.args[2];
                                    }
                                    // ★ 保存 lw3.j 实例（l0 是实例方法，不是静态）
                                    if (param.thisObject != null) {
                                        LWJ_INST = param.thisObject;
                                    }
                                }
                                if (LWJ_DUMPED) return;
                                LWJ_DUMPED = true;
                                StringBuilder sb = new StringBuilder("★★[lw3.j.l0]");
                                Class<?>[] ps = ((java.lang.reflect.Method)
                                        param.method).getParameterTypes();
                                for (int i = 0; i < param.args.length; i++) {
                                    Object av = param.args[i];
                                    sb.append(" arg").append(i)
                                      .append("(").append(i < ps.length
                                            ? ps[i].getName() : "?").append(")=");
                                    if (av == null || av instanceof String
                                            || av instanceof Number
                                            || av instanceof Boolean) {
                                        sb.append(av);
                                    } else if (av instanceof java.util.Map) {
                                        // ★ Map 只打印键名列表 + 短值，避免超长截断
                                        sb.append(" keys={");
                                        for (Object en : ((java.util.Map<?, ?>) av).entrySet()) {
                                            java.util.Map.Entry<?, ?> ee =
                                                    (java.util.Map.Entry<?, ?>) en;
                                            String vv = String.valueOf(ee.getValue())
                                                    .replaceAll("[\\r\\n\\t]", " ")
                                                    .replace('`', '\'');
                                            if (vv.length() > 16) vv = vv.substring(0, 16);
                                            sb.append(ee.getKey()).append("(")
                                              .append(vv.length()).append(",")
                                              .append(vv).append(") ");
                                        }
                                        sb.append("}");
                                    } else {
                                        sb.append(av.getClass().getName());
                                        dumpObjFields(av, sb);
                                    }
                                }
                                log(sb.toString());
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                }
                log("OK 已挂 lw3.j.l0");
            } catch (Throwable lt2) {
                log("挂 lw3.j.l0 失败: " + lt2);
            }
            // ★ 先挂 lw3.i 构造函数，抓它处理的“包”真实类型（灰条插入的真正入口）
            try {
                Class<?> clsLw = Class.forName("lw3.i", false, cl);
                for (java.lang.reflect.Constructor<?> cc : clsLw.getDeclaredConstructors()) {
                    cc.setAccessible(true);
                    XposedBridge.hookMethod(cc, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!LW3_DUMPED) {
                                    LW3_DUMPED = true;
                                    StringBuilder sb = new StringBuilder("★★[lw3.i 构造]");
                                    Class<?>[] ps = ((java.lang.reflect.Constructor<?>)
                                            param.method).getParameterTypes();
                                    for (int i = 0; i < ps.length; i++) {
                                        sb.append(" arg").append(i).append("=")
                                          .append(ps[i].getName());
                                        Object av = (i < param.args.length) ? param.args[i] : null;
                                        if (av != null && i == 0) {
                                            sb.append(" 值类型=").append(av.getClass().getName());
                                            dumpObjFields(av, sb);
                                        }
                                    }
                                    log(sb.toString());
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                }
                log("OK 已挂 lw3.i 构造函数");
            } catch (Throwable lt) {
                log("挂 lw3.i 失败: " + lt);
            }
            try {
                for (java.lang.reflect.Method m2 : finalClsPat.getDeclaredMethods()) {
                    if ("Nj".equals(m2.getName())
                            && m2.getParameterTypes().length == 1
                            && m2.getParameterTypes()[0].getName().equals("nd5.ze5")) {
                        m2.setAccessible(true);
                        M_NJ = m2;
                        log("OK 已解析 Nj(ze5) " + m2);
                        break;
                    }
                }
            } catch (Throwable nt) {
                log("解析 Nj 失败: " + nt);
            }
            // ★ 兜底：Nj 解析后若仍无实例，直接 new 一个
            try {
                if (PAT_L_INST == null) {
                    java.lang.reflect.Constructor<?> pc =
                            finalClsPat.getDeclaredConstructor();
                    pc.setAccessible(true);
                    PAT_L_INST = pc.newInstance();
                    log("OK 已 new jw3.l 实例 " + PAT_L_INST);
                }
            } catch (Throwable pt) {
                log("new jw3.l 失败: " + pt);
            }
            // ★★★ 挂 Nj 的返回值：微信自己序列化出的合法拍一拍 XML，作为黄金模板
            try {
                if (M_NJ != null) {
                    XposedBridge.hookMethod(M_NJ, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object r = param.getResult();
                                if (r instanceof String && !inOurPatBack) {
                                    GOLD_XML = (String) r;
                                    if (!XML_DUMPED) {
                                        XML_DUMPED = true;
                                        log("★★[黄金XML] " + GOLD_XML);
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    log("OK 已挂 Nj 返回值（黄金XML采集）");
                }
            } catch (Throwable xt) {
                log("挂 Nj 返回值失败: " + xt);
            }
            // ★ 探查发送方法发送候选：扩为 jw3.l 的全部方法
            for (final Method m : finalClsPat.getDeclaredMethods()) {
                String nm = m.getName();
                if ("<init>".equals(nm) || "<clinit>".equals(nm)) continue;
                Class<?>[] ps = m.getParameterTypes();
                final String mn = nm;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        // ★ 抓 ze5（Nj/Ej/Fj 都带 ze5 参数）
                        for (Object a0 : param.args) {
                            if (a0 != null && a0.getClass().getName().equals("nd5.ze5")) {
                                LAST_ZE5 = a0;
                            }
                        }
                        // ★ 抓 Nj(nd5.ze5) —— 这是把 ze5 变成消息对象并入库的关键方法
                        if ("Nj".equals(mn) && M_NJ == null
                                && param.thisObject != null) {
                            M_NJ = (java.lang.reflect.Method) param.method;
                            if (PAT_L_INST == null) PAT_L_INST = param.thisObject;
                            log("OK 已缓存 Nj(ze5) 写库 " + M_NJ);
                        }
                        // ★ 抓 Mj(long, String) 写库方法 + jw3.l 实例
                        if ("Mj".equals(mn) && M_MJ == null
                                && param.thisObject != null) {
                            try {
                                M_MJ = (java.lang.reflect.Method) param.method;
                                PAT_L_INST = param.thisObject;
                                log("OK 已缓存 Mj 写库 " + M_MJ
                                        + " 实例=" + PAT_L_INST.getClass().getName());
                                // ★ 顺带 dump jw3.l 所有字段，找 ze5 成员
                                StringBuilder db = new StringBuilder(
                                        "★★[jw3.l 字段] ");
                                Class<?> lc = PAT_L_INST.getClass();
                                while (lc != null && lc != Object.class) {
                                    for (java.lang.reflect.Field lf
                                            : lc.getDeclaredFields()) {
                                        try {
                                            lf.setAccessible(true);
                                            Object lv = lf.get(PAT_L_INST);
                                            db.append(lf.getName()).append("=")
                                              .append(lv == null ? "null"
                                                    : lv.getClass().getSimpleName())
                                              .append(" | ");
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                    lc = lc.getSuperclass();
                                }
                                log(db.toString());
                                // ★ 查 nd5.ze5 的静态字段（Dj 里读的 ze5.e 可能来自这里）
                                try {
                                    Class<?> zc = Class.forName("nd5.ze5", false,
                                            PAT_L_INST.getClass().getClassLoader());
                                    StringBuilder zb = new StringBuilder(
                                            "★★[ze5 静态字段] ");
                                    for (java.lang.reflect.Field zf
                                            : zc.getDeclaredFields()) {
                                        zb.append(java.lang.reflect.Modifier.isStatic(
                                                    zf.getModifiers()) ? "S:" : "I:")
                                          .append(zf.getName()).append("(")
                                          .append(zf.getType().getSimpleName())
                                          .append(") ");
                                        if (java.lang.reflect.Modifier.isStatic(
                                                zf.getModifiers())) {
                                            try {
                                                zf.setAccessible(true);
                                                zb.append("=").append(zf.get(null))
                                                  .append(" ");
                                            } catch (Throwable ignored) {
                                            }
                                        }
                                    }
                                    log(zb.toString());
                                } catch (Throwable zt) {
                                    log("查 ze5 静态失败: " + zt);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                        StringBuilder sb = new StringBuilder("[l方法] ").append(mn).append("(");
                        for (int i = 0; i < param.args.length; i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(safe(String.valueOf(param.args[i])));
                        }
                        sb.append(")");
                        log(sb.toString());
                        StringBuilder st = new StringBuilder("  栈:");
                        StackTraceElement[] ss = new Throwable().getStackTrace();
                        for (int i = 1; i < Math.min(ss.length, 8); i++) {
                            st.append("\n   at ").append(ss[i].getClassName())
                              .append(".").append(ss[i].getMethodName())
                              .append(":").append(ss[i].getLineNumber());
                        }
                        log(st.toString());
                    }
                });
                log("OK 已挂 " + nm + "（发送候选探查）");
            }
            if (PAT_INSTANCE == null) {
                try {
                    // 兜底：直接 new 一个
                    java.lang.reflect.Constructor<?> c = finalClsPat.getDeclaredConstructor();
                    c.setAccessible(true);
                    PAT_INSTANCE = c.newInstance();
                    log("兜底 new 实例成功");
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            log("ERR 挂 Kj 失败: " + t);
        }

        // 4) 反射打印发送类结构（一次性排查）
        dumpCls(cl, "mw3.b");
        dumpCls(cl, "nd5.fa6");

        // 4.5) ★ 挂钩 mw3.b 构造函数：抓真实发送参数
        try {
            final Class<?> clsSend = Class.forName("mw3.b", false, cl);
            CLS_SEND = clsSend;
            // ★ 立刻解析出构造，不依赖 hook 捕获
            for (java.lang.reflect.Constructor<?> ct : clsSend.getDeclaredConstructors()) {
                Class<?>[] ps = ct.getParameterTypes();
                if (ps.length == 4 && ps[0] == android.util.Pair.class
                        && ps[1] == String.class && ps[2] == String.class
                        && ps[3] == int.class) {
                    ct.setAccessible(true);
                    SEND_CTOR = ct;
                    log("OK 已解析 mw3.b 构造 (Pair,String,String,int)");
                    break;
                }
            }
            for (final java.lang.reflect.Constructor<?> ct : clsSend.getDeclaredConstructors()) {
                XposedBridge.hookMethod(ct, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            StringBuilder sb = new StringBuilder("★★[mw3.b 构造] ");
                            for (Object a : param.args) sb.append(desc(a)).append(" | ");
                            log(sb.toString());
                            // ★ 打印调用栈，找出微信自己 new mw3.b 的入口方法
                            try {
                                StackTraceElement[] st = Thread.currentThread().getStackTrace();
                                StringBuilder cb = new StringBuilder("★★[mw3.b 调用栈] ");
                                for (int si = 0; si < st.length && si < 14; si++) {
                                    StackTraceElement e = st[si];
                                    String cn = e.getClassName();
                                    if (cn.contains("xposed") || cn.contains("Xposed")) continue;
                                    cb.append(cn).append(".").append(e.getMethodName())
                                      .append(":").append(e.getLineNumber()).append(" < ");
                                }
                                log(cb.toString());
                            } catch (Throwable ignored5) {
                            }
                            // ★ 打印 mw3.b 内部 fa6 的全部字段，作为黄金样本
                            // 正确路径：mw3.b.d(modelbase.o) → o.a(modelbase.m) → 里面找 nd5.fa6
                            try {
                                java.lang.reflect.Field fd = param.thisObject.getClass()
                                        .getDeclaredField("d");
                                fd.setAccessible(true);
                                Object oo = fd.get(param.thisObject);
                                if (oo != null) {
                                    java.lang.reflect.Field fam = oo.getClass()
                                            .getDeclaredField("a");
                                    fam.setAccessible(true);
                                    Object mm = fam.get(oo);
                                    Object req = null;
                                    if (mm != null) {
                                        for (java.lang.reflect.Field f1
                                                : mm.getClass().getDeclaredFields()) {
                                            f1.setAccessible(true);
                                            Object v1 = f1.get(mm);
                                            if (v1 != null && v1.getClass().getName()
                                                    .equals("nd5.fa6")) {
                                                req = v1;
                                                break;
                                            }
                                        }
                                    }
                                    if (req != null) {
                                        StringBuilder fb = new StringBuilder("★★[fa6 真实值] ");
                                        String[] ns = { "d", "e", "f", "g", "h" };
                                        for (String n : ns) {
                                            try {
                                                java.lang.reflect.Field x =
                                                        req.getClass().getDeclaredField(n);
                                                x.setAccessible(true);
                                                Object vx = x.get(req);
                                                fb.append(n).append("=")
                                                  .append(vx).append("(")
                                                  .append(vx == null ? "null"
                                                        : vx.getClass().getSimpleName())
                                                  .append(") | ");
                                            } catch (Throwable ignored2) {
                                            }
                                        }
                                        log(fb.toString());
                                        // ★ 完整 dump fa6 所有字段（含类型），用于对比群聊差异
                                        dumpFa6(req);
                                        // ★ 保存黄金 g（msgPointer）
                                        try {
                                            java.lang.reflect.Field xg = req.getClass()
                                                    .getDeclaredField("g");
                                            xg.setAccessible(true);
                                            GOLD_G = xg.get(req);
                                            log("★★[黄金 g] " + GOLD_G);
                                        } catch (Throwable ignored4) {
                                        }
                                        // ★ 群聊样本：只采微信自己发的群包（h=2 且是手动拍）
                                        try {
                                            java.lang.reflect.Field fh5 = req.getClass()
                                                    .getDeclaredField("h");
                                            fh5.setAccessible(true);
                                            Object hv5 = fh5.get(req);
                                            java.lang.reflect.Field fe5 = req.getClass()
                                                    .getDeclaredField("e");
                                            fe5.setAccessible(true);
                                            Object tv5 = fe5.get(req);
                                            if (String.valueOf(tv5).endsWith("@chatroom")
                                                    && !inOurPatBack) {
                                                try {
                                                    java.lang.reflect.Field xdg = param.thisObject
                                                            .getClass().getDeclaredField("d");
                                                    xdg.setAccessible(true);
                                                    GOLD_REQ_GROUP = xdg.get(param.thisObject);
                                                } catch (Throwable ignored8) {
                                                }
                                                log("★★[黄金群REQ] 已保存 h=" + hv5);
                                                dumpReqFull(GOLD_REQ_GROUP, "真实群包");
                                            }
                                        } catch (Throwable ignored7) {
                                        }
                                        // ★ 保存微信真实的请求对象（含完整 BaseRequest）
                                        try {
                                            java.lang.reflect.Field xd = param.thisObject
                                                    .getClass().getDeclaredField("d");
                                            xd.setAccessible(true);
                                            GOLD_REQ = xd.get(param.thisObject);
                                            log("★★[黄金 REQ] " + GOLD_REQ);
                                        } catch (Throwable ignored6) {
                                        }
                                    }
                                }
                            } catch (Throwable ignored3) {
                            }
                            SEND_CTOR = ct;
                            SEND_OBJ = param.thisObject;
                        } catch (Throwable ignored) {
                        }
                    }
                });
                log("OK 已挂 mw3.b 构造（发送参数探查）");
            }
            // ★★★ 挂 jw3.i.handleMessage：微信拍一拍的原生执行入口
            try {
                final Class<?> clsJi = Class.forName("jw3.i", false, cl);
                for (final Method m : clsJi.getDeclaredMethods()) {
                    if (!"handleMessage".equals(m.getName())) continue;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                // ★ 保存 jw3.i 实例与 handleMessage，供回拍时直接投递
                                PAT_HANDLER = param.thisObject;
                                PAT_HANDLE_MSG = (java.lang.reflect.Method) param.method;
                                Object msg = param.args[0];
                                if (msg == null) return;
                                int what = -1;
                                Object obj = null;
                                try {
                                    java.lang.reflect.Field fw =
                                            msg.getClass().getDeclaredField("what");
                                    fw.setAccessible(true);
                                    what = fw.getInt(msg);
                                    java.lang.reflect.Field fo2 =
                                            msg.getClass().getDeclaredField("obj");
                                    fo2.setAccessible(true);
                                    obj = fo2.get(msg);
                                } catch (Throwable ignored6) {
                                }
                                // ★ 保存真实的 mw3.c 模板（微信自己发起拍一拍时）
                                try {
                                    if (obj != null && obj.getClass().getName()
                                            .equals("mw3.c")) {
                                        GOLD_PATCTX = obj;
                                        StringBuilder cb = new StringBuilder(
                                                "★★[黄金 mw3.c] ");
                                        for (java.lang.reflect.Field f5
                                                : obj.getClass().getDeclaredFields()) {
                                            try {
                                                f5.setAccessible(true);
                                                cb.append(f5.getName()).append("=")
                                                  .append(f5.get(obj)).append(" | ");
                                            } catch (Throwable ignored) {
                                            }
                                        }
                                        log(cb.toString());
                                    }
                                } catch (Throwable ignored) {
                                }
                                log("★★[Pat入口] what=" + what
                                        + " obj=" + (obj == null ? "null"
                                                : obj.getClass().getName()));
                                if (obj != null && obj.getClass().getName()
                                        .equals("nd5.ze5")) {
                                    for (java.lang.reflect.Field f3
                                            : obj.getClass().getDeclaredFields()) {
                                        f3.setAccessible(true);
                                        Object v3 = f3.get(obj);
                                        log("★★[ze5." + f3.getName() + "] "
                                                + (v3 == null ? "null"
                                                : v3.getClass().getName() + "=" + v3));
                                    }
                                }
                                // ★ 全量打印 msg.obj 的所有字段（mw3.c 等），含 static
                                if (obj != null) {
                                    Class<?> oc = obj.getClass();
                                    while (oc != null && oc != Object.class) {
                                        for (java.lang.reflect.Field f4
                                                : oc.getDeclaredFields()) {
                                            try {
                                                f4.setAccessible(true);
                                                boolean isStatic =
                                                        java.lang.reflect.Modifier
                                                        .isStatic(f4.getModifiers());
                                                Object v4 = f4.get(isStatic ? null : obj);
                                                log("★★[obj." + oc.getSimpleName() + "."
                                                        + f4.getName() + "]"
                                                        + (isStatic ? "(static)" : "")
                                                        + " " + f4.getType().getSimpleName()
                                                        + " = "
                                                        + (v4 == null ? "null" : String.valueOf(v4)));
                                            } catch (Throwable e4) {
                                                log("★★[obj." + f4.getName() + "] ERR " + e4);
                                            }
                                        }
                                        oc = oc.getSuperclass();
                                    }
                                }
                            } catch (Throwable ignored7) {
                            }
                        }
                    });
                    log("OK 已挂 jw3.i.handleMessage（拍一拍原生入口）");
                }
            } catch (Throwable t) {
                log("[挂载] jw3.i 失败: " + t);
            }
            // ★ 挂 doScene：看谁提交它
            for (final Method m : clsSend.getDeclaredMethods()) {
                if (!"doScene".equals(m.getName())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        // ★ 保存微信自己传入的网络层实例 s（回拍时要复用）
                        try {
                            if (param.args != null && param.args.length >= 1
                                    && param.args[0] != null) {
                                NET_S = param.args[0];
                            }
                            if (param.args != null && param.args.length >= 2
                                    && param.args[1] != null) {
                                NET_CB = param.args[1];
                            }
                        } catch (Throwable ignoredS) {
                        }
                        try {
                            StringBuilder sb = new StringBuilder("★★[mw3.b doScene 被调用] 调用栈:\n");
                            for (StackTraceElement e : new Throwable().getStackTrace()) {
                                String cn = e.getClassName();
                                if (cn.startsWith("de.robv") || cn.startsWith("java.lang")
                                        || cn.startsWith("com.android.system.update")) continue;
                                sb.append("   at ").append(cn).append(".").append(e.getMethodName())
                                        .append(":").append(e.getLineNumber()).append("\n");
                            }
                            log(sb.toString());
                        } catch (Throwable ignored) {
                        }
                    }
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object r = param.getResult();
                            log("★★[mw3.b doScene 返回] " + r
                                    + " ex=" + (param.getThrowable() == null ? "无"
                                            : String.valueOf(param.getThrowable())));
                        } catch (Throwable ignored) {
                        }
                    }
                });
                log("OK 已挂 mw3.b.doScene（提交栈探查）");
            }
            // ★ 挂 onGYNetEnd：看服务端到底回了什么
            for (final Method m : clsSend.getDeclaredMethods()) {
                if (!"onGYNetEnd".equals(m.getName())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            StringBuilder sb = new StringBuilder("★★[sendpat 服务端返回] ");
                            for (Object a : param.args) sb.append(desc(a)).append(" | ");
                            sb.append(" ex=").append(param.getThrowable() == null ? "无"
                                    : String.valueOf(param.getThrowable()));
                            log(sb.toString());
                        } catch (Throwable ignored) {
                        }
                    }
                });
                log("OK 已挂 mw3.b.onGYNetEnd（服务端返回探查）");
            }
        } catch (Throwable t) {
            log("ERR 挂 mw3.b: " + t);
        }

        // 6) ★ 反射 com.tencent.mm.modelbase.s1（网络队列）
        dumpCls(cl, "com.tencent.mm.modelbase.s1");

        // 7) ★★★ 挂 s1.i / s1.j：抓真实队列实例 + 提交方法
        try {
            final Class<?> clsQ = Class.forName("com.tencent.mm.modelbase.s1", false, cl);
            CLS_QUEUE = clsQ;
            for (final Method m : clsQ.getDeclaredMethods()) {
                String mn = m.getName();
                if (!"i".equals(mn) && !"j".equals(mn)) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            QUEUE_INSTANCE = param.thisObject;
                            if ("j".equals(m.getName()) && param.args.length == 4) {
                                METHOD_J = m;
                                TYPE_J0 = m.getParameterTypes()[0];
                                TYPE_J1 = m.getParameterTypes()[1];
                                TYPE_J2 = m.getParameterTypes()[2];
                                TYPE_J3 = m.getParameterTypes()[3];
                                log("[队列] 抓到 s1.j: (" + TYPE_J0.getName() + ", "
                                        + TYPE_J1.getName() + ", " + TYPE_J2.getName() + ", "
                                        + TYPE_J3.getName() + ")");
                            }
                            // ★ s1.i(NetScene, int) 才是真正入队入口
                            if ("i".equals(m.getName()) && param.args.length == 2
                                    && METHOD_SUBMIT == null) {
                                METHOD_SUBMIT = m;
                                log("[队列] 抓到 s1.i 入队方法: ("
                                        + m.getParameterTypes()[0].getName() + ", "
                                        + m.getParameterTypes()[1].getName() + ")");
                            }
                            // ★ 打印每次 j 的真实参数（区分是不是 o 自己发的）
                            if ("j".equals(m.getName()) && param.args.length == 4) {
                                Object a0 = param.args[0];
                                String who = a0 == null ? "null" : a0.getClass().getName();
                                log("[s1.j 参数] " + who + " | " + param.args[1] + " | "
                                        + param.args[2] + " | " + param.args[3]);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                log("OK 已挂 s1." + mn + "（队列实例探查）");
            }
        } catch (Throwable t) {
            log("ERR 挂 s1: " + t);
        }

        // 5) 钩 Dj/Lj 打印真实 Pair 内容
        try {
            for (final Method m : clsPat.getDeclaredMethods()) {
                String mn = m.getName();
                if (!"Dj".equals(mn) && !"Lj".equals(mn) && !"Hj".equals(mn)) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            StringBuilder sb = new StringBuilder("[").append(m.getName()).append("] ");
                            for (Object a : param.args) sb.append(desc(a)).append(" | ");
                            sb.append("=> ").append(desc(param.getResult()));
                            log(sb.toString());
                        } catch (Throwable ignored) {
                        }
                    }
                });
                log("OK 已挂 " + mn + "（Pair 探查）");
            }
        } catch (Throwable t) {
            log("ERR 挂 Dj/Lj: " + t);
        }

        log("---------- 自动回拍就绪 ----------");
    }

    /** 描述对象内容，Pair 拆开看 */
    private static String desc(Object o) {
        if (o == null) return "null";
        if (o instanceof String) return "\"" + o + "\"";
        if (o instanceof android.util.Pair) {
            android.util.Pair<?, ?> p = (android.util.Pair<?, ?>) o;
            return "Pair(" + desc(p.first) + ", " + desc(p.second) + ")";
        }
        try {
            // 尝试 dump 简单对象字段
            Class<?> c = o.getClass();
            String n = c.getName();
            if (n.startsWith("java.") || n.startsWith("android.")) return o.toString();
            StringBuilder sb = new StringBuilder(c.getSimpleName()).append("{");
            for (Field f : c.getDeclaredFields()) {
                f.setAccessible(true);
                Object v;
                try {
                    v = f.get(o);
                } catch (Throwable t) {
                    v = "?";
                }
                sb.append(f.getName()).append("=");
                if (v instanceof String) sb.append("\"").append(v).append("\"");
                else sb.append(String.valueOf(v));
                sb.append(",");
            }
            return sb.append("}").toString();
        } catch (Throwable t) {
            return String.valueOf(o);
        }
    }

    /** 反射打印类结构 */
    private void dumpCls(ClassLoader cl, String name) {
        try {
            Class<?> c = Class.forName(name, false, cl);
            for (java.lang.reflect.Constructor<?> ct : c.getDeclaredConstructors()) {
                StringBuilder sb = new StringBuilder("[构造] ").append(name).append("(");
                Class<?>[] ps = ct.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(ps[i].getName());
                }
                sb.append(")");
                log(sb.toString());
            }
            for (Field f : c.getDeclaredFields()) {
                log("[字段] " + f.getType().getName() + " " + f.getName());
            }
            for (Method m : c.getDeclaredMethods()) {
                StringBuilder sb = new StringBuilder("[方法] ").append(m.getReturnType().getName())
                        .append(" ").append(m.getName()).append("(");
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(ps[i].getName());
                }
                sb.append(")");
                log(sb.toString());
            }
        } catch (Throwable t) {
            log("ERR dump " + name + ": " + t);
        }
    }

    /** 读取自己的 wxid */
    private String readMyWxid() {
        // 方式1：SharedPreferences
        try {
            Context c = APP_CTX;
            if (c != null) {
                android.content.SharedPreferences sp = c.getSharedPreferences(
                        "com.tencent.mm_preferences", Context.MODE_PRIVATE);
                android.content.SharedPreferences.Editor ed = sp.edit();
                ed.putString("__probe__", "1").commit();
                ed.remove("__probe__").commit();
                for (java.util.Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof String && ((String) v).startsWith("wxid_")) {
                        return (String) v;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 方式2：兜底写死（已知账号）
        return "wxid_p3bg69gmjn5g22";
    }

    // ========================= 核心判定 =========================
    /** ★ 读取 wxid 对应的昵称（反射微信联系人存储） */
    private static String readNick(String wxid) {
        if (wxid == null) return null;
        // 方式1：ContactStorage / IContactStorage
        String[] clsNames = new String[] {
                "com.tencent.mm.storage.u3", "com.tencent.mm.storage.ContactStorage",
                "com.tencent.mm.modelbase.i2"
        };
        for (String cn : clsNames) {
            try {
                Class<?> cs = Class.forName(cn, false, APP_CTX.getClassLoader());
                Object inst = null;
                // 单例 getInstance / INSTANCE
                for (String mn : new String[] { "getInstance", "INSTANCE", "a" }) {
                    try {
                        java.lang.reflect.Method gm = cs.getMethod(mn);
                        inst = gm.invoke(null);
                        if (inst != null) break;
                    } catch (Throwable ignored) {
                    }
                }
                if (inst == null) continue;
                for (java.lang.reflect.Method m : cs.getMethods()) {
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length == 1 && pt[0] == String.class
                            && m.getReturnType() != void.class) {
                        try {
                            Object r = m.invoke(inst, wxid);
                            if (r == null) continue;
                            String nick = null;
                            try {
                                java.lang.reflect.Field fn = r.getClass()
                                        .getField("field_nickname");
                                nick = (String) fn.get(r);
                            } catch (Throwable t) {
                                java.lang.reflect.Field fn = r.getClass()
                                        .getDeclaredField("field_nickname");
                                fn.setAccessible(true);
                                nick = (String) fn.get(r);
                            }
                            if (nick != null && nick.trim().length() > 0) {
                                log("[昵称] " + wxid + " => " + nick);
                                return nick;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        log("[昵称] 未取到 " + wxid);
        return null;
    }

    private void onPat(String patted, String from, String talker) {
        if (patted == null || from == null) return;
        if (MY_WXID == null) {
            log("[跳过] 未知自身 wxid");
            return;
        }
        // 只有「被拍的人是我」才回
        if (patted != null && patted.endsWith("@chatroom")) {
            // ★ 群聊：patted 是群 ID，从 xml 里判断被拍的是不是我
            //    Kj 只在群成员拍人时触发，这里放行（后续从 xml 精判）
            log("[群聊] 群=" + safe(patted) + " 拍人=" + safe(from));
        } else if (!patted.equals(MY_WXID)) {
            log("[忽略] 别人拍别人，被拍=" + safe(patted));
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastKjTs < DEBOUNCE_MS) {
            log("[跳过] 防抖");
            return;
        }
        lastKjTs = now;

        int delay = DELAY_MIN + RND.nextInt(Math.max(1, DELAY_MAX - DELAY_MIN));
        log("[计划] " + delay + "ms 后回拍 " + safe(from));

        Handler h = main();
        if (h == null) {
            log("[跳过] Handler 不可用");
            return;
        }
        h.postDelayed(() -> doPatBack(from, talker), delay);
    }

    // ========================= 发送 =========================
    private void doPatBack(String target, String talker) {
        inOurPatBack = true;
        try {
            log("[回拍开始] target=" + safe(target) + " talker=" + safe(talker));
            if (!SEND_ENABLED) {
                log("[观察] 本应回拍 " + safe(target));
                return;
            }
            if (M_YJ == null || PAT_INSTANCE == null) {
                log("[失败] yj 或实例不可用 yj=" + (M_YJ != null)
                        + " inst=" + (PAT_INSTANCE != null));
                return;
            }

            int scene = (talker != null && talker.endsWith("@chatroom")) ? 2 : 1;
            // ★ 网络检查：没网就延迟重试，不要在断网时发（会留下“对方可能不知道”的失败标记）
            if (false && !netOk()) {
                log("[断网] 回拍推迟 3s 重试 " + safe(target));
                try {
                    NET_RETRY++;
                    if (NET_RETRY <= 20) {
                        Handler h2 = main();
                        if (h2 != null) {
                            h2.postDelayed(() -> doPatBack(target, talker), 3000L);
                        }
                    } else {
                        log("[断网] 重试 20 次仍无网，放弃");
                        NET_RETRY = 0;
                    }
                } catch (Throwable ignored) {
                }
                return;
            }
            NET_RETRY = 0;
            log("[网络] ok=" + netOk());
            Object r = null;
            try {
                r = M_YJ.invoke(PAT_INSTANCE, scene, target, talker);
            } catch (Throwable t) {
                log("[yj 调用异常] " + t);
            }
            log("[回拍] target=" + safe(target) + " scene=" + scene
                    + " talker=" + safe(talker) + " => " + r);

            // ★ 停用 Dj 路线（自建 ze5 会导致 Ej/Fj 抛异常，且 Dj 不发包）
            if (false && M_DJ != null && PAT_INSTANCE != null) {
                try {
                    long now2 = System.currentTimeMillis();
                    Long lid = Long.valueOf(now2 % 1000000L);
                    android.util.Pair<Object, Object> pp =
                            new android.util.Pair<Object, Object>(lid, Long.valueOf(now2));
                    // ★ 自建 nd5.ze5：d=String(talker) / e=LinkedList(target)
                    Object ze5 = null;
                    try {
                        ClassLoader ldr2 = PAT_INSTANCE.getClass().getClassLoader();
                        Class<?> zc = Class.forName("nd5.ze5", true, ldr2);
                        ze5 = zc.getDeclaredConstructor().newInstance();
                        java.lang.reflect.Field zd = zc.getDeclaredField("d");
                        zd.setAccessible(true);
                        zd.set(ze5, talker);
                        java.lang.reflect.Field ze = zc.getDeclaredField("e");
                        ze.setAccessible(true);
                        java.util.LinkedList<Object> lst = new java.util.LinkedList<Object>();
                        lst.add(target);
                        ze.set(ze5, lst);
                        log("[自建ze5] d=" + zd.get(ze5) + " e=" + ze.get(ze5));
                    } catch (Throwable zt) {
                        log("[自建ze5] 失败: " + zt);
                    }
                    // ① Ej(ze5, long)
                    if (M_EJ != null && ze5 != null) {
                        try {
                            Object r0 = M_EJ.invoke(PAT_INSTANCE, ze5, now2);
                            log("[Ej] 返回 " + r0);
                        } catch (Throwable t0) {
                            log("[Ej] 异常: " + (t0.getCause() != null ? t0.getCause() : t0));
                        }
                    } else {
                        log("[Ej] 跳过 ze5=" + (ze5 != null) + " M_EJ=" + (M_EJ != null));
                    }
                    log("[Dj] 调用 Dj(" + safe(target) + ", " + pp + ")");
                    Object rr = M_DJ.invoke(PAT_INSTANCE, target, pp);
                    log("[Dj] 返回 " + rr + " ✔");
                    // ③ Lj
                    if (M_LJ != null) {
                        try {
                            Object r2 = M_LJ.invoke(PAT_INSTANCE, target, pp);
                            log("[Lj] 返回 " + r2);
                        } catch (Throwable t2) {
                            log("[Lj] 异常: " + t2);
                        }
                    }
                    // ④ Fj(ze5, long)
                    if (M_FJ != null && ze5 != null) {
                        try {
                            Object r3 = M_FJ.invoke(PAT_INSTANCE, ze5, now2);
                            log("[Fj] 返回 " + r3);
                        } catch (Throwable t3) {
                            log("[Fj] 异常: " + (t3.getCause() != null ? t3.getCause() : t3));
                        }
                    }
                    return;
                } catch (Throwable t) {
                    log("[Dj] 失败: " + t);
                }
            }
            log("[回拍] Dj 不可用，回退");
            if (SEND_CTOR != null) {
                sendReal(target, talker, scene);
            } else {
                log("[发送] 还没有捕获到 mw3.b 构造，跳过");
            }

        } catch (Throwable t) {
            log("[回拍异常] " + t);
        }
    }

    /** 真正发送拍一拍 */
    private void sendReal(String target, String talker, int scene) {
        try {
            log("[发送开始] target=" + safe(target) + " SEND_OBJ=" + (SEND_OBJ != null)
                    + " SEND_CTOR=" + (SEND_CTOR != null));
            if (SEND_CTOR == null) {
                // 反射兜底：直接找 (Pair, String, String, int) 构造
                try {
                    Class<?> cs = CLS_SEND;
                    if (cs == null) {
                        ClassLoader ldr = APP_CTX != null ? APP_CTX.getClassLoader()
                                : MainHook.class.getClassLoader();
                        cs = Class.forName("mw3.b", false, ldr);
                    }
                    for (java.lang.reflect.Constructor<?> ct : cs.getDeclaredConstructors()) {
                        Class<?>[] ps = ct.getParameterTypes();
                        if (ps.length == 4 && ps[0] == android.util.Pair.class
                                && ps[1] == String.class && ps[2] == String.class
                                && ps[3] == int.class) {
                            ct.setAccessible(true);
                            SEND_CTOR = ct;
                            CLS_SEND = cs;
                            log("[发送] 反射找到 mw3.b 构造");
                            break;
                        }
                    }
                } catch (Throwable t) {
                    log("[发送] 反射构造失败: " + t);
                }
            }
            if (SEND_CTOR == null) {
                log("[发送] 未能获得 mw3.b 构造，放弃");
                return;
            }
            if (METHOD_J == null || QUEUE_INSTANCE == null) {
                log("[发送] 队列未就绪 (queue=" + (QUEUE_INSTANCE != null)
                        + ", j=" + (METHOD_J != null) + ")");
                return;
            }
            // Pair 提前构造（模板路线也要用）
            // ★ 关键修正：Pair.first 必须是 Long！微信 jw3.l.onSceneEnd 按 Long 强转，
            //   传 Integer 会 ClassCastException，导致「收到消息瞬间闪退」。
            long now = System.currentTimeMillis();
            Long localId = Long.valueOf(now % 1000000L);
            android.util.Pair<Object, Object> p =
                    new android.util.Pair<Object, Object>(localId, Long.valueOf(now));
            // ★ 关键路线：直接改微信自己构造的那个 mw3.b 的请求体，然后原样重发
            // v30.0：模板路线 doScene 恒为 -1（同一 NetScene 不可重复提交），暂时停用。
            Object tpl = null;
            if (false && SEND_OBJ != null) {
                tpl = SEND_OBJ;
            }
            if (tpl != null) {
                try {
                    java.lang.reflect.Field fd = tpl.getClass().getDeclaredField("d");
                    fd.setAccessible(true);
                    Object oo = fd.get(tpl);                       // modelbase.o
                    log("★★[模板] o=" + (oo == null ? "null" : oo.getClass().getName()));
                    // o.a 是 m 类型，fa6 就在里面
                    Object lg = null;
                    java.lang.reflect.Field fam = oo.getClass().getDeclaredField("a");
                    fam.setAccessible(true);
                    Object mm = fam.get(oo);
                    if (mm == null) {
                        log("★★[模板] o.a 为 null，放弃模板路线");
                        throw new RuntimeException("o.a null");
                    }
                    log("★★[模板 m] " + mm.getClass().getName());
                    for (java.lang.reflect.Field f1 : mm.getClass().getDeclaredFields()) {
                        f1.setAccessible(true);
                        Object v1 = f1.get(mm);
                        String n1 = v1 == null ? "null" : v1.getClass().getName();
                        log("★★[模板 m." + f1.getName() + "] " + n1);
                        if ("com.tencent.mm.protobuf.f".equals(n1)
                                && v1.getClass().getName().equals("nd5.fa6")) {
                            lg = v1;
                        }
                        if (v1 != null && v1.getClass().getName().equals("nd5.fa6")) {
                            lg = v1;
                        }
                    }
                    if (lg == null) {
                        log("★★[模板] m 里没找到 nd5.fa6，放弃模板路线");
                        throw new RuntimeException("no fa6");
                    }
                    log("★★[模板] 已定位 fa6 ✔");
                    Object req = lg;                               // fa6 请求体
                    java.lang.reflect.Field fe = req.getClass().getDeclaredField("e");
                    fe.setAccessible(true);
                    java.lang.reflect.Field ff = req.getClass().getDeclaredField("f");
                    ff.setAccessible(true);
                    java.lang.reflect.Field fdd = req.getClass().getDeclaredField("d");
                    fdd.setAccessible(true);
                    java.lang.reflect.Field fh = req.getClass().getDeclaredField("h");
                    fh.setAccessible(true);
                    log("★★[模板 req 原值] d=" + fdd.get(req) + " e=" + fe.get(req)
                            + " f=" + ff.get(req) + " h=" + fh.get(req));
                    // 改被拍人
                    fe.set(req, target);
                    ff.set(req, target);
                    log("★★[模板 req 改后] e=" + fe.get(req) + " f=" + ff.get(req));
                    // 直接把微信这个 mw3.b 再提交一次
                    Object[] a2 = new Object[] { tpl, Integer.valueOf(0),
                            Integer.valueOf(0), Integer.valueOf(0) };
                    METHOD_J.invoke(QUEUE_INSTANCE, a2);
                    log("[发送] 已用微信原始对象重发（模板路线）✔");
                    return;
                } catch (Throwable t) {
                    log("[发送] 模板路线失败: " + t);
                }
            } else {
                log("[发送] 还没有模板对象，走普通路线");
            }

            // Pair(局部ID = 时间戳后6位, 时间戳ms)，与微信真实值同算法
            // 对齐微信真实参数：mw3.b(Pair, 被拍人, 被拍人, scene)
            Object netScene = SEND_CTOR.newInstance(p, target, target, scene);
            log("[发送] mw3.b 已构造 localId=" + localId + " target=" + safe(target)
                    + " talker=" + safe(talker) + " scene=" + scene);
            // ★ 关键：把 g（msgPointer）补成微信自己的真实值
            try {
                java.lang.reflect.Field fo = netScene.getClass().getDeclaredField("d");
                fo.setAccessible(true);
                Object oo2 = fo.get(netScene);
                java.lang.reflect.Field fam2 = oo2.getClass().getDeclaredField("a");
                fam2.setAccessible(true);
                Object mm2 = fam2.get(oo2);
                Object req2 = null;
                for (java.lang.reflect.Field f2 : mm2.getClass().getDeclaredFields()) {
                    f2.setAccessible(true);
                    Object v2 = f2.get(mm2);
                    if (v2 != null && v2.getClass().getName().equals("nd5.fa6")) {
                        req2 = v2;
                        break;
                    }
                }
                if (req2 != null) {
                    java.lang.reflect.Field fg = req2.getClass().getDeclaredField("g");
                    fg.setAccessible(true);
                    log("[发送] 自建包 g 原值=" + fg.get(req2) + " 黄金G=" + GOLD_G);
                    if (GOLD_G != null) {
                        fg.set(req2, GOLD_G);
                    }
                    java.lang.reflect.Field fe2 = req2.getClass().getDeclaredField("e");
                    fe2.setAccessible(true);
                    java.lang.reflect.Field ff2 = req2.getClass().getDeclaredField("f");
                    ff2.setAccessible(true);
                    java.lang.reflect.Field fd2 = req2.getClass().getDeclaredField("d");
                    fd2.setAccessible(true);
                    java.lang.reflect.Field fh2 = req2.getClass().getDeclaredField("h");
                    fh2.setAccessible(true);
                    log("[发送] 自建包最终 d=" + fd2.get(req2) + " e=" + fe2.get(req2)
                            + " f=" + ff2.get(req2) + " g=" + fg.get(req2)
                            + " h=" + fh2.get(req2));
                } else {
                    log("[发送] 自建包里没找到 fa6");
                }
            } catch (Throwable t) {
                log("[发送] 补 g 失败: " + t);
            }
            // ★★★ 与微信真实包一致：无论单聊群聊，h 都是 1 和 0
            int[] scenes = new int[] { 1, 0 };
            // ★ 单聊：e=对方 wxid；群聊：e=群 ID
            final String sendTalker = (talker != null && talker.endsWith("@chatroom"))
                    ? talker : target;
            log("[发送] 会话=" + safe(talker) + " sendTalker=" + safe(sendTalker));
            for (int si = 0; si < scenes.length; si++) {
                int hv = scenes[si];
                try {
                    Object ns2 = SEND_CTOR.newInstance(p, talker, target, hv);
                    // ★ 补 g：不复用旧值（服务端会按重放丢弃），用当前时间生成唯一 msgPointer
                    try {
                        java.lang.reflect.Field fo = ns2.getClass().getDeclaredField("d");
                        fo.setAccessible(true);
                        Object oo3 = fo.get(ns2);
                        java.lang.reflect.Field fam3 = oo3.getClass().getDeclaredField("a");
                        fam3.setAccessible(true);
                        Object mm3 = fam3.get(oo3);
                        for (java.lang.reflect.Field f3 : mm3.getClass().getDeclaredFields()) {
                            f3.setAccessible(true);
                            Object v3 = f3.get(mm3);
                            if (v3 != null && v3.getClass().getName().equals("nd5.fa6")) {
                                // ★★★ 五个字段全部强制重写，与微信真实包完全对齐
                                //    d=拍人(我)  e=sendTalker  f=被拍人  g=唯一指针  h=1|0
                                setF(v3, "d", MY_WXID);
                                setF(v3, "e", sendTalker);
                                setF(v3, "f", target);
                                String newG = MY_WXID + "_" + localId
                                        + "_" + System.currentTimeMillis();
                                setF(v3, "g", newG);
                                setF(v3, "h", Integer.valueOf(hv));
                                log("[发送#" + si + "] 强制覆盖 d=" + MY_WXID
                                        + " e=" + sendTalker + " f=" + target
                                        + " g=" + newG + " h=" + hv);
                                break;
                            }
                        }
                    } catch (Throwable t3) {
                        log("[发送#" + si + "] 补 g 失败: " + t3);
                    }
                    // ★★★ 关键：把 BaseRequest 整体从微信真实包复制过来
                    //     mw3.b.d 是请求对象，里面的 BaseRequest 含 session/uin/deviceId/clientSeq
                    try {
                        java.lang.reflect.Field fo2 = ns2.getClass().getDeclaredField("d");
                        fo2.setAccessible(true);
                        Object myReq = fo2.get(ns2);       // o 的请求对象
                        Object tplReq = GOLD_REQ;           // 微信真实请求对象
                        if (tplReq != null && myReq != null
                                && tplReq.getClass() == myReq.getClass()) {
                            int copied = 0;
                            Class<?> rc = myReq.getClass();
                            while (rc != null && rc != Object.class) {
                                for (java.lang.reflect.Field f4 : rc.getDeclaredFields()) {
                                    try {
                                        f4.setAccessible(true);
                                        String tn = f4.getType().getName();
                                        // 跳过 fa6（已经单独处理）
                                        if (tn.equals("nd5.fa6")) continue;
                                        // 只复制值类型 + 字符串 + BaseRequest 这类对象引用
                                        Object tv = f4.get(tplReq);
                                        if (tv == null) continue;
                                        if (f4.getType().isPrimitive() || tv instanceof String
                                                || tn.startsWith("com.tencent.mm.modelbase")) {
                                            f4.set(myReq, tv);
                                            copied++;
                                        }
                                    } catch (Throwable ignoredF) {
                                    }
                                }
                                rc = rc.getSuperclass();
                            }
                            log("[发送#" + si + "] BaseRequest 复制字段数=" + copied);
                        } else {
                            log("[发送#" + si + "] 模板请求不可用 tpl="
                                    + (tplReq != null));
                        }
                    } catch (Throwable t5) {
                        log("[发送#" + si + "] 复制 BaseRequest 失败: " + t5);
                    }
                    if (METHOD_SUBMIT != null && QUEUE_INSTANCE != null) {
                        METHOD_SUBMIT.invoke(QUEUE_INSTANCE,
                                new Object[] { ns2, Integer.valueOf(0) });
                        log("[发送#" + si + "] h=" + hv + " 已入队 ✔");
                        // ★★★ 本地写库：走微信自己的 lw3.j.l0("pat", Map, p0)
                        //   它会 new lw3.i 并插入本地消息 → 灰条出现
if (si == 0) {
                            try {
                                insertLocalPat(sendTalker, target);
                            } catch (Throwable lt3) {
                                log("[本地写库] 失败: " + lt3);
                            }
                        }
                    }
                } catch (Throwable t4) {
                    log("[发送#" + si + "] 失败: " + t4);
                }
            }
            return;

        } catch (Throwable t) {
            log("[发送异常] " + t);
        } finally {
            inOurPatBack = false;
        }
    }

    // ========================= 原生路线：走 jw3.i.handleMessage =========================
    /**
     * ★ 关键路线：构造 Message(what=291) + mw3.c，投递给微信自己的拍一拍处理器。
     * 这样写库、生成 msgPointer、构造 mw3.b、发送全部由微信原生完成，
     * 服务端不会返回 errCode=4。全程不碰 UI。
     */
    private void sendNative(String target, String talker, int scene) {
        try {
            if (PAT_HANDLER == null || PAT_HANDLE_MSG == null) {
                log("[原生] 处理器未就绪（需要先手动拍一次以捕获），回退");
                return;
            }
            ClassLoader ldr = APP_CTX != null ? APP_CTX.getClassLoader()
                    : MainHook.class.getClassLoader();
            Class<?> clsC = Class.forName("mw3.c", false, ldr);
            Object c = clsC.getDeclaredConstructor().newInstance();

            long now = System.currentTimeMillis();
            long localId = now % 1000000L;

            java.lang.reflect.Field fa = clsC.getDeclaredField("a");
            fa.setAccessible(true);
            fa.setLong(c, now);
            java.lang.reflect.Field fb = clsC.getDeclaredField("b");
            fb.setAccessible(true);
            fb.setInt(c, scene);
            java.lang.reflect.Field fc = clsC.getDeclaredField("c");
            fc.setAccessible(true);
            fc.set(c, talker);
            java.lang.reflect.Field fd = clsC.getDeclaredField("d");
            fd.setAccessible(true);
            fd.set(c, target);
            java.lang.reflect.Field fe = clsC.getDeclaredField("e");
            fe.setAccessible(true);
            fe.set(c, new android.util.Pair<Long, Long>(
                    Long.valueOf(localId), Long.valueOf(now)));
            java.lang.reflect.Field ff = clsC.getDeclaredField("f");
            ff.setAccessible(true);
            ff.setBoolean(c, false);

            log("[原生] mw3.c a=" + fa.get(c) + " b=" + fb.get(c) + " c=" + fc.get(c)
                    + " d=" + fd.get(c) + " e=" + fe.get(c));

            android.os.Message msg = android.os.Message.obtain();
            java.lang.reflect.Field fw = android.os.Message.class.getDeclaredField("what");
            fw.setAccessible(true);
            fw.setInt(msg, 291);
            java.lang.reflect.Field fo = android.os.Message.class.getDeclaredField("obj");
            fo.setAccessible(true);
            fo.set(msg, c);

            PAT_HANDLE_MSG.invoke(PAT_HANDLER, msg);
            log("[原生] 已投递 jw3.i.handleMessage(what=291) ✔");
            // ★ 主动写本地库：调 jw3.l.Mj(long, String) 让灰条出现在自己这边
            try {
                if (M_MJ != null && PAT_L_INST != null) {
                    Object r = M_MJ.invoke(PAT_L_INST,
                            new Object[] { Long.valueOf(localId), target });
                    log("[本地写库] Mj(" + localId + ", " + target + ") => " + r);
                } else {
                    log("[本地写库] Mj 不可用 M_MJ=" + (M_MJ != null)
                            + " INST=" + (PAT_L_INST != null));
                }
            } catch (Throwable tw) {
                log("[本地写库] 失败: " + tw);
            }
            // ★ 投递后检查：mw3.b 是否被构造（说明真的发出去了）
            final long t0 = System.currentTimeMillis();
            Handler h3 = main();
            if (h3 != null) {
                h3.postDelayed(() -> {
                    log("[原生·1s后] SEND_OBJ=" + (SEND_OBJ != null)
                            + " 耗时=" + (System.currentTimeMillis() - t0) + "ms");
                }, 1000L);
            }
        } catch (Throwable t) {
            log("[原生] 失败: " + t);
        }
    }

    /** 按目标类型做简单适配 */
    private static Object fit(Class<?> type, Object v) {
        if (type == null) return v;
        if (v != null && type.isInstance(v)) return v;
        if (type == int.class || type == Integer.class) {
            return v instanceof Integer ? v : Integer.valueOf(0);
        }
        if (type == long.class || type == Long.class) {
            return v instanceof Long ? v : Long.valueOf(0L);
        }
        if (type == boolean.class || type == Boolean.class) {
            return v instanceof Boolean ? v : Boolean.FALSE;
        }
        return v;
    }

    // ========================= 工具 =========================
    private static void log(String s) {
        XposedBridge.log(TAG + ": " + s);
    }

    private static String safe(String s) {
        if (s == null) return "null";
        return s.length() > 90 ? s.substring(0, 90) + "..." : s;
    }

    /**
     * ★★★ 本地写库：调用微信自己的 lw3.j.l0("pat", Map, p0)
     * 微信会 new lw3.i 并插入本地消息 → 灰条出现
     * Map 从微信收到对方拍一拍时抓到的真实模板克隆，只对调 fromusername / pattedusername
     */
    private static void insertLocalPat(final String chatUser, final String target) {
        if (LWJ_MAP == null || LWJ_P0 == null || M_LWJ_L0 == null) {
            log("[本地写库] 模板未就绪 map=" + (LWJ_MAP != null)
                    + " p0=" + (LWJ_P0 != null) + " m=" + (M_LWJ_L0 != null));
            return;
        }
        // ★ l0 是实例方法，必须有 lw3.j 实例
        Object receiver = LWJ_INST;
        if (receiver == null) {
            try {
                Class<?> cj = Class.forName("lw3.j", false,
                        M_LWJ_L0.getDeclaringClass().getClassLoader());
                java.lang.reflect.Constructor<?> c0 = cj.getDeclaredConstructor();
                c0.setAccessible(true);
                receiver = c0.newInstance();
                LWJ_INST = receiver;
                log("[本地写库] 已 new lw3.j 实例");
            } catch (Throwable it) {
                log("[本地写库] new lw3.j 失败: " + it);
                return;
            }
        }
        try {
            java.util.Map<Object, Object> nm =
                    new java.util.HashMap<Object, Object>(LWJ_MAP);
            // ★ 对调：原本「对方拍我」→ 现在「我拍对方」
            nm.put(".sysmsg.pat.fromusername", MY_WXID);
            nm.put(".sysmsg.pat.pattedusername", target);
            nm.put(".sysmsg.pat.chatusername", chatUser);
            // ★ 后缀用「对方」的：对方没设后缀就清空（模板里的 patsuffix 是收到方=我的）
            nm.put(".sysmsg.pat.patsuffix", "");
            // ★ 模板串也对调：把模板里的实际 wxid 互换
            Object tplObj = nm.get(".sysmsg.pat.template");
            if (tplObj instanceof String) {
                String tpl = (String) tplObj;
                tpl = tpl.replace(target, "@@U@@").replace(MY_WXID, target)
                         .replace("@@U@@", MY_WXID);
                // ★★★ 真实格式：「我拍了拍"备注名"对方后缀」
                // 微信接收侧模板正是用 ${wxid_xxx} 渲染昵称，这里照抄这种方式
                String suffix = String.valueOf(nm.get(".sysmsg.pat.patsuffix"));
                if ("null".equals(suffix)) suffix = "";
                tpl = "我拍了拍\"${" + target + "}\"" + suffix;
                nm.put(".sysmsg.pat.template", tpl);
                nm.put(".sysmsg.pat.fromusername", MY_WXID);
                nm.put(".sysmsg.pat.pattedusername", target);
                nm.put(".sysmsg.pat.chatusername", chatUser);
                log("[本地写库] tpl=" + tpl + " suffix=[" + suffix + "]");
            }
            // ★★★ .sysmsg.pat / .sysmsg（XML 原文）里的 wxid 也要对调
            for (Object k : new String[] { ".sysmsg.pat", ".sysmsg" }) {
                Object xmlObj = nm.get(k);
                if (!(xmlObj instanceof String)) continue;
                String xml = (String) xmlObj;
                if (xml.trim().length() == 0) continue;
                xml = xml.replace(target, "@@U@@").replace(MY_WXID, target)
                         .replace("@@U@@", MY_WXID);
                nm.put(k, xml);
                log("[本地写库] " + k + " len=" + xml.length());
            }
            M_LWJ_L0.invoke(receiver, new Object[] { "pat", nm, LWJ_P0 });
            log("[本地写库] lw3.j.l0 已调用 from=" + safe(MY_WXID)
                    + " to=" + safe(target) + " chat=" + safe(chatUser));
        } catch (Throwable t) {
            log("[本地写库] lw3.j.l0 异常: " + (t.getCause() != null ? t.getCause() : t));
        }
    }

    /** ★ 反射打印一个对象的全部字段（浅层） */
    private static void dumpObjFields(Object obj, StringBuilder sb) {
        if (obj == null) {
            sb.append("(null)");
            return;
        }
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    sb.append(" | ").append(f.getName()).append("=")
                      .append(v == null ? "null"
                             : (v instanceof String
                                ? "\"" + v + "\""
                                : String.valueOf(v)));
                } catch (Throwable t) {
                    sb.append(" | ").append(f.getName()).append("=?");
                }
            }
            c = c.getSuperclass();
        }
    }

    /** ★ 反射设置某字段的值，失败静默 */
    private static void setF(Object obj, String name, Object val) {
        try {
            java.lang.reflect.Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(obj, val);
        } catch (Throwable ignored) {
        }
    }

    /** ★ 完整 dump nd5.fa6（拍一拍参数）所有字段 */
    private static void dumpFa6(Object req) {
        if (req == null) return;
        try {
            StringBuilder b = new StringBuilder("★★[fa6 全字段] ");
            for (java.lang.reflect.Field f : req.getClass().getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(req);
                    b.append(f.getName()).append("(")
                     .append(f.getType().getSimpleName()).append(")=")
                     .append(safe(String.valueOf(v))).append(" | ");
                } catch (Throwable ignored) {
                }
            }
            log(b.toString());
        } catch (Throwable t) {
            log("[fa6 全字段] 失败: " + t);
        }
    }

    /** ★ 完整 dump 一个 mw3.b.d 请求对象的所有字段（用于找群聊缺的字段） */
    private static void dumpReqFull(Object reqObj, String tag) {
        if (reqObj == null) {
            log("[" + tag + "] 请求对象为空");
            return;
        }
        try {
            StringBuilder sb = new StringBuilder("★★[" + tag + "] 类=")
                    .append(reqObj.getClass().getName()).append(" | ");
            for (java.lang.reflect.Field f : reqObj.getClass().getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(reqObj);
                    sb.append(f.getName()).append("=").append(safe(String.valueOf(v)))
                      .append(" | ");
                    // 如果是 modelbase.m / modelbase.n，往下展开一层
                    if (v != null) {
                        String cn = v.getClass().getName();
                        if (cn.equals("com.tencent.mm.modelbase.m")
                                || cn.equals("com.tencent.mm.modelbase.n")) {
                            StringBuilder in = new StringBuilder();
                            for (java.lang.reflect.Field f2
                                    : v.getClass().getDeclaredFields()) {
                                try {
                                    f2.setAccessible(true);
                                    in.append(f2.getName()).append(":")
                                      .append(safe(String.valueOf(f2.get(v)))).append(" ");
                                } catch (Throwable ignored) {
                                }
                            }
                            log("★★[" + tag + "] " + f.getName() + "(" + cn
                                    + ") => " + in);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            log(sb.toString());
        } catch (Throwable t) {
            log("[" + tag + "] dump 失败: " + t);
        }
    }
}