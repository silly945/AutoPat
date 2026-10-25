package com.greenbubble.tapcat;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 拍拍猫 设置页
 * 绿泡泡的拍一拍小助手
 */
public class SettingsActivity extends Activity {

    private SharedPreferences sp;

    // 配色
    private static final int C_BG = 0xFFFDF7F0;
    private static final int C_PRIMARY = 0xFFFFB259;
    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_TEXT = 0xFF4A3628;
    private static final int C_SUB = 0xFF9A8574;
    private static final int C_ACCENT = 0xFFE8A87C;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = getSharedPreferences("tapcat", MODE_PRIVATE);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(32));
        sv.addView(root);

        // ══════ 顶部标题 ══════
        TextView title = new TextView(this);
        title.setText("拍拍猫");
        title.setTextSize(30);
        title.setTextColor(C_TEXT);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("绿泡泡的拍一拍小助手");
        subtitle.setTextSize(14);
        subtitle.setTextColor(C_SUB);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(6);
        slp.bottomMargin = dp(22);
        subtitle.setLayoutParams(slp);
        root.addView(subtitle);

        // ══════ 总开关 ══════
        LinearLayout mainCard = card();
        TextView mainTitle = new TextView(this);
        mainTitle.setText("自动回拍");
        mainTitle.setTextSize(18);
        mainTitle.setTextColor(C_TEXT);
        mainTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        mainCard.addView(mainTitle);

        TextView mainDesc = new TextView(this);
        mainDesc.setText("别人拍你一下，拍拍猫就立刻拍回去");
        mainDesc.setTextSize(13);
        mainDesc.setTextColor(C_SUB);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(4);
        dlp.bottomMargin = dp(12);
        mainDesc.setLayoutParams(dlp);
        mainCard.addView(mainDesc);

        final Switch swMain = new Switch(this);
        swMain.setText("启用");
        swMain.setTextColor(C_TEXT);
        swMain.setTextSize(15);
        swMain.setChecked(cfgBool("enabled", true));
        swMain.setOnCheckedChangeListener((b, v) -> cfgPut("enabled", String.valueOf(v)));
        mainCard.addView(swMain);
        root.addView(mainCard);

        // ══════ 延迟 ══════
        LinearLayout delayCard = card();
        final TextView delayLabel = new TextView(this);
        delayLabel.setText("回拍延迟：" + cfgInt("delay", 0) + " 毫秒");
        delayLabel.setTextSize(16);
        delayLabel.setTextColor(C_TEXT);
        delayCard.addView(delayLabel);

        TextView delayDesc = new TextView(this);
        delayDesc.setText("0 表示立刻回拍；数值越大越像真人");
        delayDesc.setTextSize(12);
        delayDesc.setTextColor(C_SUB);
        LinearLayout.LayoutParams dlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp2.topMargin = dp(4);
        dlp2.bottomMargin = dp(10);
        delayDesc.setLayoutParams(dlp2);
        delayCard.addView(delayDesc);

        SeekBar sb = new SeekBar(this);
        sb.setMax(3000);
        sb.setProgress(cfgInt("delay", 0));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = (progress / 100) * 100;
                delayLabel.setText("回拍延迟：" + v + " 毫秒");
                cfgPut("delay", String.valueOf(v));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        delayCard.addView(sb);
        root.addView(delayCard);

        // ══════ 场景开关 ══════
        LinearLayout sceneCard = card();
        TextView sceneTitle = new TextView(this);
        sceneTitle.setText("生效场景");
        sceneTitle.setTextSize(16);
        sceneTitle.setTextColor(C_TEXT);
        sceneTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        sceneCard.addView(sceneTitle);

        final Switch swSingle = new Switch(this);
        swSingle.setText("单聊");
        swSingle.setTextColor(C_TEXT);
        swSingle.setTextSize(15);
        swSingle.setChecked(cfgBool("single", true));
        swSingle.setOnCheckedChangeListener((b, v) -> cfgPut("single", String.valueOf(v)));
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l1.topMargin = dp(10);
        sceneCard.addView(swSingle, l1);

        final Switch swGroup = new Switch(this);
        swGroup.setText("群聊");
        swGroup.setTextColor(C_TEXT);
        swGroup.setTextSize(15);
        swGroup.setChecked(cfgBool("group", true));
        swGroup.setOnCheckedChangeListener((b, v) -> cfgPut("group", String.valueOf(v)));
        sceneCard.addView(swGroup);
        root.addView(sceneCard);

        // ══════ 夜间静默 ══════
        LinearLayout nightCard = card();
        final Switch swNight = new Switch(this);
        swNight.setText("夜间静默");
        swNight.setTextColor(C_TEXT);
        swNight.setTextSize(16);
        swNight.setChecked(cfgBool("night", false));
        swNight.setOnCheckedChangeListener((b, v) -> cfgPut("night", String.valueOf(v)));
        nightCard.addView(swNight);

        TextView nightDesc = new TextView(this);
        nightDesc.setText("开启后 23:00 - 07:00 不回拍");
        nightDesc.setTextSize(12);
        nightDesc.setTextColor(C_SUB);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(4);
        nightDesc.setLayoutParams(nlp);
        nightCard.addView(nightDesc);
        root.addView(nightCard);

        // ══════ 不拍名单（手动填 wxid） ══════
        LinearLayout listCard = card();
        TextView listTitle = new TextView(this);
        listTitle.setText("不拍名单");
        listTitle.setTextSize(16);
        listTitle.setTextColor(C_TEXT);
        listTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        listCard.addView(listTitle);

        TextView listDesc = new TextView(this);
        listDesc.setText("填 wxid，一行一个。名单里的人拍你，不会回拍\n怎么查 wxid：微信 → 对方资料页 → 微信号（不是昵称）");
        listDesc.setTextSize(12);
        listDesc.setTextColor(C_SUB);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(4);
        llp.bottomMargin = dp(10);
        listDesc.setLayoutParams(llp);
        listCard.addView(listDesc);

        final EditText etBlack = new EditText(this);
        etBlack.setHint("wxid_xxxxxxx");
        etBlack.setText(cfgStr("blacklist", ""));
        etBlack.setTextSize(14);
        etBlack.setTextColor(C_TEXT);
        etBlack.setHintTextColor(C_SUB);
        etBlack.setBackgroundColor(0xFFF2F2F5);
        etBlack.setPadding(dp(12), dp(12), dp(12), dp(12));
        etBlack.setMinLines(3);
        etBlack.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        listCard.addView(etBlack);

        final TextView blackHint = new TextView(this);
        blackHint.setTextSize(12);
        blackHint.setTextColor(0xFF3BA55D);
        LinearLayout.LayoutParams bhlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bhlp.topMargin = dp(8);
        blackHint.setLayoutParams(bhlp);
        listCard.addView(blackHint);

        TextView btnSaveBlack = new TextView(this);
        btnSaveBlack.setText("保存名单");
        btnSaveBlack.setTextSize(14);
        btnSaveBlack.setTextColor(0xFFFFFFFF);
        btnSaveBlack.setGravity(android.view.Gravity.CENTER);
        btnSaveBlack.setBackgroundColor(C_ACCENT);
        btnSaveBlack.setPadding(0, dp(12), 0, dp(12));
        LinearLayout.LayoutParams bslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bslp.topMargin = dp(10);
        btnSaveBlack.setLayoutParams(bslp);
        btnSaveBlack.setOnClickListener(v -> {
            String raw = etBlack.getText().toString();
            StringBuilder sbBlack = new StringBuilder();
            int cnt = 0;
            for (String ln : raw.split("\n")) {
                String w = ln.trim();
                if (w.length() > 0) {
                    if (sbBlack.length() > 0) sbBlack.append("\n");
                    sbBlack.append(w);
                    cnt++;
                }
            }
            cfgPut("blacklist", sbBlack.toString());
            blackHint.setText("已保存，共 " + cnt + " 人");
        });
        listCard.addView(btnSaveBlack);
        root.addView(listCard);

        // ══════ 每日上限 ══════
        LinearLayout capCard = card();
        final TextView capLabel = new TextView(this);
        capLabel.setText("每日上限：" + (cfgInt("daily", 0) == 0
                ? "不限" : cfgInt("daily", 0) + " 次"));
        capLabel.setTextSize(16);
        capLabel.setTextColor(C_TEXT);
        capCard.addView(capLabel);

        TextView capDesc = new TextView(this);
        capDesc.setText("0 表示不限次数");
        capDesc.setTextSize(12);
        capDesc.setTextColor(C_SUB);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(4);
        clp.bottomMargin = dp(10);
        capDesc.setLayoutParams(clp);
        capCard.addView(capDesc);

        SeekBar sbCap = new SeekBar(this);
        sbCap.setMax(50);
        int cur = cfgInt("daily", 0);
        sbCap.setProgress(cur);
        sbCap.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                capLabel.setText("每日上限：" + (progress == 0 ? "不限" : progress + " 次"));
                cfgPut("daily", String.valueOf(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        capCard.addView(sbCap);
        root.addView(capCard);

        // ══════ 入口说明 ══════
        LinearLayout hideCard = card();
        TextView hideTitle = new TextView(this);
        hideTitle.setText("入口与隐藏");
        hideTitle.setTextSize(16);
        hideTitle.setTextColor(C_TEXT);
        hideTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        hideCard.addView(hideTitle);

        TextView hideDesc = new TextView(this);
        hideDesc.setTextSize(12);
        hideDesc.setTextColor(C_SUB);
        LinearLayout.LayoutParams hdlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hdlp.topMargin = dp(6);
        hideDesc.setLayoutParams(hdlp);
        hideDesc.setText("桌面图标点进去，或：LSPosed／君子Xposed → 模块 → 拍拍猫 → 模块设置\n"
                + "想从微信视野里彻底隐身，用「隐藏应用列表」勾选 com.greenbubble.tapcat");
        hideCard.addView(hideDesc);
        root.addView(hideCard);

        // ══════ 底部说明 ══════
        TextView footer = new TextView(this);
        footer.setText( "v0.1010 · 改完设置后无需重启微信\n若未生效，请确认 LSPosed 作用域已勾选微信");
        footer.setTextSize(11);
        footer.setTextColor(C_SUB);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(24);
        footer.setLayoutParams(flp);
        root.addView(footer);

        setContentView(sv);
    }

    // ══════ 配置读写（自己进程的 SharedPreferences，Provider 也读这一份） ══════
    private android.content.SharedPreferences sp0() {
        return getSharedPreferences("tapcat_cfg", MODE_PRIVATE);
    }

    private java.util.HashMap<String, String> cfgAll() {
        java.util.HashMap<String, String> m = new java.util.HashMap<>();
        try {
            for (java.util.Map.Entry<String, ?> e : sp0().getAll().entrySet()) {
                m.put(e.getKey(), String.valueOf(e.getValue()));
            }
        } catch (Throwable ignored) {
        }
        return m;
    }

    private boolean cfgBool(String key, boolean def) {
        try {
            return sp0().getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private int cfgInt(String key, int def) {
        try {
            return sp0().getInt(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private String cfgStr(String key, String def) {
        try {
            return sp0().getString(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private void cfgPut(String key, String value) {
        try {
            // 按 key 类型分别写
            if ("enabled".equals(key) || "single".equals(key)
                    || "group".equals(key) || "night".equals(key)) {
                sp0().edit().putBoolean(key, "true".equals(value)).apply();
            } else if ("delay".equals(key) || "daily".equals(key)) {
                sp0().edit().putInt(key, Integer.parseInt(value)).apply();
            } else {
                sp0().edit().putString(key, value).apply();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 白色圆角卡片 */
    private LinearLayout card() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackground(makeRound(C_CARD, 18));
        ll.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        ll.setLayoutParams(lp);
        return ll;
    }

    private GradientDrawable makeRound(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}