// SPDX-License-Identifier: AGPL-3.0-or-later
// P2y 的正确性判据:与 Python 版 `p2y` 【逐字节相同】
//
// 用法(Android instrumentation 或桌面 JVM 均可):
//   1) 先在本机生成期望:tests/cases.txt 每行一句 → `p2y` → expected.txt
//   2) 跑本测试:读 cases.txt,逐句 convert,与 expected.txt 比
//
// ⚠️ 这条"与 Python 版逐字节比对"是【硬判据】——
//    因为 p2y 已经过 157 个单元用例 + CER 实测(23.7%→4.3%),
//    我们复刻只要"一模一样"就继承了那些结论,不需要重新验证语言学正确性。
package com.fcitx5sensevoice.canto

object P2yTestCases {
    /** 覆盖各类规则的最小样例(补全交给 cases.txt) */
    val SMOKE = listOf(
        "谢了,这个功能已经完成了。",
        "但是太貴了。",
        "目的很明确。",
        "我哋一齊去食飯。",
        "最好的选择是什么?",
        "有两个人的时间不一致。",
        "他握着声卡。",
        "关键是要相关。",
    )

    data class Result(val src: String, val got: String, val want: String) {
        val ok get() = got == want
    }

    fun compare(p2y: P2y, expected: Map<String, String>): List<Result> =
        SMOKE.map { s -> Result(s, p2y.convert(s), expected[s] ?: "") }
}
