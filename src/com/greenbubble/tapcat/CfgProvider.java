package com.greenbubble.tapcat;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * 配置桥接 Provider（跑在拍拍猫自己进程里，存真实配置）
 *
 * 微信进程里的 hook 通过它读写配置，不需要 root / Shizuku：
 *   - hook 读：call("get", null) 拿到全部 key=value
 *   - hook 写：call("put", bundle) 写入
 *   - hook 记录拍客：call("guest", bundle{wxid})
 */
public class CfgProvider extends ContentProvider {

    public static final String AUTH = "com.greenbubble.tapcat.cfg";
    public static final String SP_NAME = "tapcat_cfg";

    @Override
    public boolean onCreate() {
        return true;
    }

    private SharedPreferences sp() {
        return getContext().getSharedPreferences(SP_NAME, android.content.Context.MODE_PRIVATE);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        try {
            SharedPreferences sp = sp();
            if ("get".equals(method)) {
                Bundle all = new Bundle();
                for (java.util.Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof Boolean) {
                        all.putBoolean(e.getKey(), (Boolean) v);
                    } else if (v instanceof Integer) {
                        all.putInt(e.getKey(), (Integer) v);
                    } else if (v instanceof Long) {
                        all.putLong(e.getKey(), (Long) v);
                    } else {
                        all.putString(e.getKey(), String.valueOf(v));
                    }
                }
                out.putBundle("data", all);
                out.putBoolean("ok", true);
            } else if ("put".equals(method)) {
                Bundle data = extras == null ? null : extras.getBundle("data");
                if (data != null) {
                    SharedPreferences.Editor ed = sp.edit();
                    for (String k : data.keySet()) {
                        Object v = data.get(k);
                        if (v instanceof Boolean) {
                            ed.putBoolean(k, (Boolean) v);
                        } else if (v instanceof Integer) {
                            ed.putInt(k, (Integer) v);
                        } else {
                            ed.putString(k, String.valueOf(v));
                        }
                    }
                    ed.commit();
                }
                out.putBoolean("ok", true);
            } else if ("guest".equals(method)) {
                String wxid = extras == null ? null : extras.getString("wxid");
                if (wxid != null && wxid.length() > 0) {
                    String raw = sp.getString("guests", "");
                    StringBuilder sb = new StringBuilder(wxid);
                    int cnt = 0;
                    if (raw != null && raw.length() > 0) {
                        for (String s : raw.split("\\|")) {
                            String w = s.trim();
                            if (w.length() == 0 || w.equals(wxid)) continue;
                            if (++cnt > 40) break;
                            sb.append("|").append(w);
                        }
                    }
                    sp.edit().putString("guests", sb.toString()).commit();
                    out.putString("guests", sb.toString());
                }
                out.putBoolean("ok", true);
            } else {
                out.putBoolean("ok", false);
            }
        } catch (Throwable t) {
            out.putBoolean("ok", false);
            out.putString("err", String.valueOf(t));
        }
        return out;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return new MatrixCursor(new String[] { "k", "v" });
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}