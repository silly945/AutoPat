# AutoPat — 微信自动回拍（Xposed 模块）

对方拍你一下，微信立即自动回拍对方一下，**并且你这边会正常出现那条灰色的「我拍了拍"XXX"」提示**，和手动拍完全一致。

- 版本：**v1.0**
- 包名（伪装）：`com.android.system.update`
- 适配：微信 8.0.79（versionCode 3200）
- 环境：Android + LSPosed（已 root）

---

## 功能

| 项目 | 说明 |
|---|---|
| 自动回拍 | 对方拍你 → 立即回拍对方，**延迟 0** |
| 本地灰条 | 你这边出现「我拍了拍"对方昵称"」，与实际手拍一模一样 |
| 单聊 / 群聊 | 均支持 |
| 后台静默 | 全程无弹窗、无点击、不抢前台 |
| 频率 | 无日限、无同人冷却、无夜间静默、概率 100% |

---

## 安装

1. 安装 `AutoPat_v1.0.apk`
2. 在 **LSPosed** 中启用模块，作用域勾选 **微信**
3. 强制停止微信并重新打开

---

## 技术原理

微信处理「拍一拍」消息的入口是：

```
lw3.j.l0("pat", Map<9键>, p0)  →  new lw3.i → run() → 插入本地消息 + 刷新 UI
```

模块 Hook 收到拍一拍后，克隆微信自己生成的 9 键 Map，做三件事：

1. 对调 `fromusername` / `pattedusername` / `chatusername`
2. 把 template 重写为发送视角：

   ```java
   tpl = "我拍了拍\"${" + 对方wxid + "}\"" + 对方后缀;
   ```

3. 调用 `lw3.j.l0("pat", 新Map, p0)`

微信自己完成插入消息、显示灰条、刷新界面 —— **模块不碰任何 UI**。

### 关键坑

| 写法 | 结果 |
|---|---|
| `${pattedusername}` | 填「微信用户」❌ |
| `${pattedusername@textstatusicon}` | 空引号 ❌ |
| 硬编码 wxid | 原样显示 wxid ❌ |
| **`${wxid_xxxxxxxx}`** | **正确显示备注/昵称** ✅ |

其他要点：

- `l0` 是**实例方法**，必须用真实 `lw3.j` 实例调用，`invoke(null, ...)` 会 `NullPointerException: null receiver`
- 只改顶层 3 个键会造成**两条灰条**，必须同时把 template 里的 wxid 对调（用 `@@U@@` 中转防止二次替换）
- `patsuffix` 用**对方**的后缀，不能清空

---

## 文件

```
AndroidManifest.xml
build_apk.sh                                 # 构建脚本
src/com/android/system/update/MainHook.java  # 全部逻辑
AutoPat_v1.0.apk                             # 成品
```

---

## 许可

仅供学习研究，请勿用于骚扰他人。使用风险自负。
