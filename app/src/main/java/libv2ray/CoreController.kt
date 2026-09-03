package libv2ray

interface CoreController {
    val isRunning: Boolean
    fun startLoop(config: String, tunFd: Int)
    fun stopLoop()
    fun queryAllOutboundTrafficStats(): String
    fun measureDelay(url: String): Long
    fun registerProcessFinder(finder: ProcessFinder?)
}
