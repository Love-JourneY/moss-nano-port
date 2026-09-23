// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026  Nija (bubu12) and contributors
//
// P2y —— 普通话书面文 → 粤语口语(供 TTS 朗读用)
//
// ⚠️ 设计要点(为什么这么写):
//   1) 【不把 1907 条规则写进代码】—— 规则表是【数据】,随包走(assets/p2y-rules.tsv),
//      这样:① Kotlin 侧只有薄实现,易维护 ② 规则可独立更新,不用重编 App
//   2) 【单遍最长匹配】—— 输出字符【永不再扫描】。上游 Python 版 v1 用 replace 链,
//      会级联污染(`但是太貴了` → `但係係太貴喇`);v2 改单遍最长匹配才修好。我们复刻 v2 语义。
//   3) 规则表【已按长度降序排好】(上游 `p2y --list` 就是这么排的)⇒ 顺序扫描即可,
//      第一个命中的就是最长匹配,不需要额外的 trie。
//   4) 【保护表已并入同一张表】—— 上游把 PROTECT(身份保留,如"目的"→"目的")和
//      LEX(真正转换)合成一张表,靠最长优先天然生效 ⇒ 我们不用分开处理。
//
// ⚠️ 与 Python 版的一致性:本实现是【机械复刻】,不是"重新设计"。
//    验证方式:对同一批语料,Android 输出应与 `p2y` 输出【逐字节相同】。

package com.fcitx5sensevoice.canto

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

class P2y private constructor(
    /** 规则表:src → dst;已按 src 长度降序 + 字典序排好 */
    private val rules: Array<Pair<String, String>>,
    /** 最长 src 的长度(用于剪枝:剩余文本不足这么长就不用试了) */
    private val maxSrcLen: Int,
) {

    companion object {
        const val ASSET_NAME = "p2y-rules.tsv"

        /** 从 assets 加载(TSV:源词<TAB>目标词,一行一条,已排序) */
        fun fromAssets(ctx: Context): P2y {
            val list = ArrayList<Pair<String, String>>(2048)
            var maxLen = 1
            ctx.assets.open(ASSET_NAME).use { ins ->
                BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).use { br ->
                    br.forEachLine { line ->
                        if (line.isEmpty()) return@forEachLine
                        val tab = line.indexOf('\t')
                        if (tab <= 0) return@forEachLine
                        val src = line.substring(0, tab)
                        val dst = line.substring(tab + 1)
                        list.add(src to dst)
                        if (src.length > maxLen) maxLen = src.length
                    }
                }
            }
            return P2y(list.toTypedArray(), maxLen)
        }

        /** 测试用:直接给规则表 */
        fun fromRules(rules: List<Pair<String, String>>): P2y =
            P2y(rules.toTypedArray(), rules.maxOfOrNull { it.first.length } ?: 1)
    }

    /**
     * 转换。**单遍最长匹配** —— 输出的字符不再参与后续匹配(防级联污染)。
     *
     * 算法:
     *   i 从 0 起;在位置 i,尝试长度从 min(maxSrcLen, 剩余) 递减到 1 的 src
     *   ⇒ 第一个命中就用它,把 dst 追加到输出,i 前进 src.length
     *   ⇒ 都不命中就把 text[i] 原样追加,i 前进 1
     */
    fun convert(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length + 16)
        var i = 0
        val n = text.length
        while (i < n) {
            val remain = n - i
            val tryMax = if (remain < maxSrcLen) remain else maxSrcLen
            var matched = false
            var len = tryMax
            while (len >= 1) {
                val cand = text.substring(i, i + len)
                val hit = lookup(cand)
                if (hit != null) {
                    sb.append(hit)
                    i += len
                    matched = true
                    break
                }
                len--
            }
            if (!matched) {
                sb.append(text[i])
                i++
            }
        }
        return sb.toString()
    }

    /**
     * 查表。规则表已排序,但为了 O(1) 仍建索引。
     * ⚠️ 规则表里同一 src 可能出现多次(PROTECT + LEX),【先出现的赢】(与上游 set() 后的行为一致
     *    —— 上游是 set(PROTECT + LEX),PROTECT 在前 ⇒ 保护条目优先)。
     */
    private val index: HashMap<String, String> by lazy {
        HashMap<String, String>(rules.size * 2).apply {
            for ((s, d) in rules) putIfAbsent(s, d)   // 先出现者赢
        }
    }

    private fun lookup(s: String): String? = index[s]
}
