package com.cfop.lab.solver

data class SolveOutcome(
    val ok: Boolean,
    val moves: String = "",
    val error: String = ""
)

/**
 * 自动桥接开源纯 Java 极速求解器 cs0x7f/min2phase（Kociemba 两阶段算法，约 20 步最优解）。
 * 运行 scripts/fetch-deps.bat (或 .sh) 后，min2phase 源码会放入 app/src/main/java/cs/min2phase/，
 * 本桥接类无需改代码即可自动调用 cs.min2phase.Search.solution(...)。
 */
object CubeSolverBridge {

    fun solve(facelets54: String): SolveOutcome {
        if (facelets54.length != 54) {
            return SolveOutcome(false, error = "状态串长度必须为 54")
        }
        return try {
            val cls = Class.forName("cs.min2phase.Search")
            val instance = cls.getDeclaredConstructor().newInstance()
            val method = cls.getMethod(
                "solution",
                String::class.java,
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            val raw = method.invoke(instance, facelets54, 21, 100_000_000L, 0L, 0) as String
            if (raw.startsWith("Error")) {
                SolveOutcome(false, error = "魔方状态校验失败（$raw），请检查有色差的贴纸是否拍反。")
            } else {
                SolveOutcome(true, moves = raw.trim().replace(Regex("\\s+"), " "))
            }
        } catch (e: ClassNotFoundException) {
            SolveOutcome(
                false,
                error = "尚未下载 min2phase 源码：请先运行项目根目录 scripts/fetch-deps.bat（或 .sh）自动拉取 cs0x7f/min2phase。"
            )
        } catch (e: Exception) {
            SolveOutcome(false, error = "求解异常：${e.message}")
        }
    }
}
