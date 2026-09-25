package cn.cf3012.xdf.ui

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * ApkUpdater — Release APK 下载 + root 静默安装。
 *
 * 安装为什么不怕宿主进程被杀：
 *   pm 是独立进程（com.android.commands.pm），真正的安装事务由 system_server
 *   的 PackageInstallerService 持有；本 App 进程被杀不会影响事务本身。
 *   最坏情况是 pm 客户端在握手前被杀 → 事务干净回滚，不会出现坏包
 *   （包管理器是原子替换）。这里仍用 setsid+nohup 让安装命令脱离本进程组，
 *   并把结果写入日志文件轮询，保证成功率与可观测性。
 */
object ApkUpdater {

    private const val TIMEOUT_MS = 15_000
    private const val INSTALL_POLL_MS = 600L
    private const val INSTALL_TIMEOUT_MS = 180_000L

    /** 下载 APK 到应用私有目录（无需任何存储权限），返回本地文件 */
    fun download(
        context: Context,
        url: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onDone: (File?, String?) -> Unit,
    ) {
        Thread {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.instanceFollowRedirects = true
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    onDone(null, "下载失败：HTTP ${conn.responseCode}")
                    return@Thread
                }
                val total = conn.contentLengthLong
                val dir = context.getExternalFilesDir("updates")
                    ?: File(context.filesDir, "updates").also { it.mkdirs() }
                val name = url.substringAfterLast('/').ifBlank { "update.apk" }
                val tmp = File(dir, "$name.part")
                val out = File(dir, name)
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(16 * 1024)
                        var got = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            got += n
                            onProgress(got, if (total > 0) total else -1)
                        }
                    }
                }
                if (tmp.renameTo(out)) {
                    onDone(out, null)
                } else {
                    onDone(null, "保存失败")
                }
            } catch (t: Throwable) {
                onDone(null, "下载失败：${t.message}")
            } finally {
                conn?.disconnect()
            }
        }.start()
    }

    /**
     * root 静默安装：用 setsid+nohup 让 pm 脱离本进程组（本 App 被系统杀掉
     * 也不影响），结果写入日志文件并轮询确认。
     */
    fun installWithRoot(
        context: Context,
        apk: File,
        onResult: (success: Boolean, message: String) -> Unit,
    ) {
        val dir = context.getExternalFilesDir("updates")
            ?: File(context.filesDir, "updates").also { it.mkdirs() }
        val log = File(dir, "install.log")
        log.delete()
        val cmd = "setsid nohup sh -c \"pm install -r -t --user 0 " +
            "'${apk.absolutePath}' > '${log.absolutePath}' 2>&1\" " +
            "</dev/null >/dev/null 2>&1 &"
        Thread {
            try {
                ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            } catch (_: Throwable) {
                // 启动失败也会体现在日志缺失/超时上
            }
            val deadline = System.currentTimeMillis() + INSTALL_TIMEOUT_MS
            var last = ""
            while (System.currentTimeMillis() < deadline) {
                if (log.exists()) {
                    val txt = runCatching { log.readText() }.getOrDefault("")
                    if (txt.isNotBlank()) {
                        last = txt
                        if (txt.contains("Success")) {
                            post { onResult(true, "Success") }
                            return@Thread
                        }
                        if (txt.contains("Failure") || txt.contains("Error")) {
                            val line = txt.lineSequence().lastOrNull { it.isNotBlank() } ?: txt
                            post { onResult(false, line.take(300)) }
                            return@Thread
                        }
                    }
                }
                Thread.sleep(INSTALL_POLL_MS)
            }
            post { onResult(false, if (last.isBlank()) "安装超时" else last.take(300)) }
        }.start()
    }

    private fun post(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }
}