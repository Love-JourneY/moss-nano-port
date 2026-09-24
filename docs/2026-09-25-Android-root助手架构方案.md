> # ⚠️ 已废弃(2026-09-27 · 只作历史存档)
> 
> 本方案描述的「root 守护」路线**已被移除**。
> 实测:无 root 时 App 自己跑推理的性能几乎一样(1266ms vs 1270ms),
> 且靠**常驻 1×1 透明 overlay** 即可避免被 ColorOS virtualFreeze 冻
> (oom_adj 450→0 · do_freezer_trap→do_epoll_wait · 完整跑完 30 秒)。
> 
> **现行架构见**:`VOICE.md` §9 · `android/README.md` 顶部横幅。
> **不要再按本文实施。** 保留它是为了记住我们是怎么走到这一步的。

# Android 系统 TTS 引擎 —— root 助手架构方案

> **2026-09-25** · 本文提出并论证一个**能真正跑起来**的架构,解决前面 17 轮都没解决的问题。
> **前提已验证**;剩下的是工程实现。**⚠️ 该方案使用 root,需 Nija 拍板。**

## 一、为什么要换架构:问题的精确边界

### 已排除的(全部实测)

```
10 种 App 侧对策:白名单 / set-inactive / wakelock×2 / threadPriority /
                 startForeground(仅合成期间)· (常驻) / 降帧数 /
                 前台客户端持绑 + 亮屏 + wakelock  ⇒ 全部无效
6 条减内存路径:fp16(反而多92MB) / int8(漂移) / 按图裁剪(只剩3MB可砍) /
              砍死代码(仅42MB) / ORT 调优(反而多139MB) / mmap(本来就是)
3 条时序对策:降 maxFrames(375→40→10) ⇒ 无效(冻结点随机,与我们工作量无关)
```

### 根因(最精确的一次实测)

```
合成期间每 3 秒采样:
  t= 3s  cpu=301  synth_wchan=futex_wait_queue   ← 在跑
  t= 6s  cpu=351  synth_wchan=do_freezer_trap    ← 被冻
  t= 9s..60s  cpu=351  ← 【一 jiffy 都没涨,CPU 完全停止】

⇒ ColorOS 的 virtualFreeze 是【定时扫描 + 无差别冻结】:
   · 不看工作量(降帧数无效)
   · 不看进程状态(curProcState=4 FGS 也冻)
   · 不看 freeze-exempt(常驻前台也 isFreezeExempt=false)
   · 不看绑定关系(前台客户端持绑也冻)
```

### 为什么现有引擎(sherpa VITS)能活

```
实测:com.fcitx5sensevoice 的进程【也】在 do_freezer_trap(空闲时)
⇒ do_freezer_trap 是【idle 时的正常状态】,不是故障
⇒ 它能工作的原因:合成是【毫秒级】⇒ 在两次 freeze 扫描之间就跑完
⇒ 【不是因为"它受保护"】

⇒ 核心矛盾:
   老架构之所以在系统 TTS 位置能用,【正是因为它快】;
   换成需要秒级连续 CPU 的自回归模型后,【"慢"本身就是致命伤】
```

## 二、突破:root 进程**完全不被冻**(已实测)

```
实验:起一个 uid=0 的进程持续烧 CPU,每 10 秒量 cpu 时间

  burner(uid=0, root):
    t=10s cpu=108  wchan=pipe_read
    t=20s cpu=158  ← 持续增长
    t=30s cpu=207
    t=40s cpu=257
    t=50s cpu=308
    t=60s cpu=358
    t=70s cpu=407  ← 70 秒一路在涨,【从没进 do_freezer_trap】

  对照 canto.tts(App, uid 10326)同一时段:
    cpu=465 【完全不变】  wchan=do_freezer_trap

⇒ 【ColorOS 的 virtualFreeze 只针对"普通 App 身份",不碰 uid=0】
```

## 三、方案:App 做薄客户端,root 守护做推理

```
┌──────────────────────────────────────────────────┐
│ TTS 服务(App, uid 10326)                          │
│   · 系统 bind 它、调 onSynthesizeText(这部分【已跑通】)│
│   · 收到文本 ⇒ 通过【Unix domain socket】转发       │
│   · 收回 PCM ⇒ callback.audioAvailable            │
│   ⚠️ 它只做转发,【毫秒级】⇒ 不会被冻                 │
└──────────────────┬───────────────────────────────┘
                   │ /data/local/tmp/moss-nano-port.sock
┌──────────────────▼───────────────────────────────┐
│ 推理守护(root, uid 0, 常驻)                       │
│   · 由 install.sh 用 su + setsid 启动(开机自启)   │
│   · 加载 683MB 模型【一次】,常驻不再重载            │
│   · 收文本 → p2y → G2P → ORT → 回 PCM             │
│   · 【不被冻结】⇒ 可以跑任意久                     │
└──────────────────────────────────────────────────┘
```

### 为什么这条路【符合 goal】而不是"绕过问题"

```
① 平板本来就有 KernelSU root(本会话一直在用 su -c)
② 上下文里平板已有 chroot(也是 root 跑的)
③ install.sh 里本来就用 su -c 做 chown/chcon
④ 原型(app_process, uid 2000)跑通过 ⇒ 证明"非 App 身份"这条路可行
⑤ 目标②要的"进程内推理引擎"仍然成立 —— 只是那个"进程"不是 App 进程,
   而是我们自己起的【推理守护进程】(这更贴近 goal ① 说的"常驻守护"!)
```

### 额外收益

```
· 模型只加载一次(3.4s),不再每次合成都等 ⇒ 首字延迟从 3.4s+ 降到接近纯推理时间
· 守护常驻 ⇒ 权重留在内存里,不会被反复换出
· App 侧变得极薄 ⇒ 更不容易被系统盯上
```

## 四、要实现的部分(工程清单)

| # | 项 | 说明 |
|---|---|---|
| 1 | **socket 协议** | 简单行协议:`TEXT <len>\n<utf8>` → 回 `PCM <len>\n<bytes>`;或长度前缀二进制 |
| 2 | **守护主类** | `CantoDaemon`(有 `main()`,可被 app_process 拉起):监听 socket、加载模型、循环处理 |
| 3 | **SELinux 上下文** | socket 放 `/data/local/tmp/`;⚠️ root 建的 socket 与 App 的通信可能受 MLS 限制 —— **要实测**(前面踩过 chcon 的坑) |
| 4 | **生命周期** | install.sh 起 · uninstall.sh 停 · 开机自启(放 `/data/adb/service.d/`,KernelSU 约定) |
| 5 | **预热** | 守护启动即加载模型;就绪后写一个 ready 标记 |
| 6 | **失败可见** | App 侧连不上守护时,**明确报错**(不静默失败 —— 我们吃过"绑定成功、合成成功、永远静音"的亏) |
| 7 | **回退** | 没有 root 的设备 ⇒ App 侧退回"自己做推理"(结果是会被冻,但至少行为可预期) |

## 五、风险与未知

| 风险 | 说明 |
|---|---|
| **socket 通信的 SELinux** | root(可能 `u:r:magisk:s0`)↔ App(`u:r:untrusted_app:s0:c70,...`)跨域通信,可能需要 `chcon` 或放特定目录 —— **必须实测** |
| **root 依赖** | 无 root 的设备用不了;需明确文档化 |
| **安全面** | 多了一个 root 常驻进程 ⇒ 要最小化(只监听一个 socket、只做合成、不做别的) |
| **开机自启** | `/data/adb/service.d/` 是 KernelSU 约定;需确认平板上可用 |

## 六、下一步

1. 写 `CantoDaemon`(main 类 + socket 服务)
2. App 侧 `CantoTtsService` 改成"先试守护,socket 不可用则回退本地推理"
3. install/uninstall 里加守护的起停
4. **实测 socket 跨 SELinux 域能不能通**(这是最大的未知)
5. 用现有测试客户端验收
