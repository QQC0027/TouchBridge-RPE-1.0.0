package com.touchbridge.rpe.capture

/** 一路采集来源；同一时刻只允许一个来源投递触摸帧。 */
enum class CaptureSource {
    NONE,
    VIEW,
    ACCESSIBILITY,

    /** Shizuku / Root 读取 /dev/input 的全局采集来源。 */
    SHELL,
}

/**
 * 采集来源仲裁器，解决“前台 View 采集”和“后台无障碍采集”同时收到同一物理触摸
 * 造成的重复计数，以及切换瞬间同一根手指被拼成跨来源拖拽的问题。
 *
 * 只允许在单线程（RecordingCoordinator 的采集线程）上调用。
 */
class CaptureRouter {

    /** 当前唯一生效的来源。 */
    var owner: CaptureSource = CaptureSource.NONE
        private set

    /** 每次所有权变更自增；旧来源携带的过期令牌会被拒绝。 */
    var generation: Long = 0L
        private set

    data class SwitchResult(
        val generation: Long,
        val previous: CaptureSource,
        /** 切换来源时需要用“空帧”结算上一路仍在按下的手指。 */
        val flushNeeded: Boolean,
    )

    fun acquire(source: CaptureSource): SwitchResult {
        if (source == owner) {
            return SwitchResult(generation, owner, flushNeeded = false)
        }
        val previous = owner
        owner = source
        generation += 1
        return SwitchResult(
            generation = generation,
            previous = previous,
            flushNeeded = previous != CaptureSource.NONE && source != CaptureSource.NONE,
        )
    }

    /** 释放来源；只有当前持有者能释放，避免延迟回调把新来源踢掉。 */
    fun release(source: CaptureSource): SwitchResult {
        if (owner != source) return SwitchResult(generation, owner, flushNeeded = false)
        val previous = owner
        owner = CaptureSource.NONE
        generation += 1
        return SwitchResult(generation, previous, flushNeeded = previous != CaptureSource.NONE)
    }

    fun isOwner(source: CaptureSource): Boolean = owner == source

    /** 帧是否应被接收：来源必须是当前持有者，且带的是当前令牌。 */
    fun accepts(source: CaptureSource, token: Long): Boolean =
        owner == source && generation == token

    fun currentToken(source: CaptureSource): Long? =
        if (owner == source) generation else null

    fun reset() {
        owner = CaptureSource.NONE
        generation += 1
    }
}
