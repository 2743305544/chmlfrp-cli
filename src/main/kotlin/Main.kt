package com.shiyi

import com.alibaba.fastjson2.JSON
import kotlinx.coroutines.*
import okhttp3.*
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import kotlin.system.exitProcess


/**
 * @author Shi Yi
 * @date 2025/5/2
 * @Description chmlFRP客户端命令行工具（OAuth2 + PKCE 认证版）
 */
@Command(
    name = "chmlFrp-cli",
    version = ["2.1.0"],
    description = ["命令行工具，用于获取远程FRP配置并启动FRP客户端"],
    mixinStandardHelpOptions = true
)
class FrpClient : Callable<Int> {

    @Option(names = ["-l", "--list"], description = ["列出所有可用的FRP配置"], required = false)
    private var listConfigs: Boolean = false

    @Option(names = ["-s", "--select"], description = ["选择配置的序号"], required = false)
    private var selectIndex: Int = -1

    @Option(names = ["--login"], description = ["通过浏览器OAuth登录"], required = false)
    private var doLogin: Boolean = false

    @Option(names = ["--logout"], description = ["退出登录并清除本地凭据"], required = false)
    private var doLogout: Boolean = false

    @Option(names = ["--token"], description = ["直接指定access_token（跳过OAuth登录）"], required = false)
    private var cliToken: String? = null

    @Option(names = ["--stop"], description = ["停止本地运行的隧道(序号)"], required = false)
    private var stopIndex: Int? = null

    @Option(names = ["--stop-all"], description = ["停止所有本地运行的frpc"], required = false)
    private var stopAll: Boolean = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val osName = System.getProperty("os.name").lowercase()
    private val isWindows = osName.contains("win")
    private val isMac = osName.contains("mac") || osName.contains("darwin")
    private val isLinux = !isWindows && !isMac
    private val configList = mutableListOf<FrpConfig>()

    private var accessToken: String = ""
    private var refreshToken: String = ""
    private var expiresAt: Long = 0

    override fun call(): Int {
        if (doLogin) return runLogin()
        if (doLogout) return runLogout()

        if (isLinux) {
            try {
                println("正在设置当前目录权限...")
                val currentDir = File(".").absolutePath
                ProcessBuilder("chmod", "-R", "755", currentDir).start().waitFor()
                println("已设置当前目录权限")
            } catch (e: Exception) {
                println("设置目录权限失败: ${e.message}")
            }
        }

        loadTokenFromConfig()

        if (cliToken != null) {
            accessToken = cliToken!!
        }

        if (accessToken.isEmpty()) {
            println("未登录，请先运行: chmlfrp-cli --login")
            return 1
        }

        if (!ensureValidToken()) {
            println("Token已过期且无法刷新，请重新登录: chmlfrp-cli --login")
            return 1
        }

        fetchRemoteConfigs()

        if (configList.isEmpty()) {
            println("未找到可用的FRP配置")
            return 1
        }

        val runningMap = getRunningFrpcProcesses()

        if (stopAll) {
            return stopAllFrpc(runningMap)
        }

        if (stopIndex != null) {
            val idx = stopIndex!!
            return if (idx in configList.indices) {
                val id = configList[idx].id
                val pid = runningMap[id]
                if (pid != null) {
                    return if (stopFrpc(pid, configList[idx].name)) 0 else 1
                } else {
                    println("隧道 ${configList[idx].name} 未在本地运行")
                    1
                }
            } else {
                println("无效的序号: $idx")
                1
            }
        }

        if (listConfigs) {
            displayConfigList(runningMap)
            return 0
        }

        if (selectIndex >= 0) {
            return if (selectIndex < configList.size) {
                startFrpClient(configList[selectIndex])
                0
            } else {
                println("无效的配置序号: $selectIndex")
                1
            }
        }

        while (true) {
            configList.clear()
            fetchRemoteConfigs()
            val running = getRunningFrpcProcesses()
            displayConfigList(running)
            val runningCount = running.size
            println()
            val hint = buildString {
                append("操作: [数字]启动/停止")
                if (runningCount > 0) append("  a全部停止")
                append("  r刷新列表")
                append("  exit退出")
            }
            println(hint)
            print("> ")
            val input = readLine()?.trim() ?: ""
            when {
                input.equals("q", true) || input.equals("exit", true) -> return 0
                input.equals("r", true) -> { Thread.sleep(300); continue }
                input.equals("a", true) -> { stopAllFrpc(getRunningFrpcProcesses()); Thread.sleep(500) }
                else -> {
                    val index = input.toIntOrNull() ?: -1
                    if (index in configList.indices) {
                        val cfg = configList[index]
                        val pid = running[cfg.id]
                        if (pid != null) {
                            stopFrpc(pid, cfg.name)
                            Thread.sleep(500)
                        } else {
                            startFrpClient(cfg)
                            Thread.sleep(1500)
                        }
                    } else {
                        println("无效的输入: $input")
                        Thread.sleep(1000)
                    }
                }
            }
        }
    }

    // ==================== PKCE ====================

    private fun generatePkce(): Pair<String, String> {
        val random = ByteArray(32)
        SecureRandom().nextBytes(random)
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return verifier to challenge
    }

    // ==================== Protocol Handler ====================

    private fun getJarPath(): String {
        return File(FrpClient::class.java.protectionDomain.codeSource.location.toURI()).absolutePath
    }

    private fun registerProtocolHandler() {
        val jarPath = getJarPath()
        val javaHome = System.getProperty("java.home")

        if (isWindows) {
            var javaExe = "$javaHome\\bin\\javaw.exe"
            if (!File(javaExe).exists()) {
                javaExe = "$javaHome\\bin\\java.exe"
            }

            // Build the command value: "javaw.exe" -jar "jar" "%1"
            val cmdValue = "\"$javaExe\" -jar \"$jarPath\" \"%1\""

            // Use a .reg file to avoid quoting issues with reg.exe
            // In .reg format: backslash -> \\, quote -> \"
            val escaped = cmdValue.replace("\\", "\\\\").replace("\"", "\\\"")
            val regContent = buildString {
                appendLine("Windows Registry Editor Version 5.00")
                appendLine()
                appendLine("[HKEY_CURRENT_USER\\Software\\Classes\\chmlerp]")
                appendLine("@=\"URL:ChmlFrp Protocol\"")
                appendLine("\"URL Protocol\"=\"\"")
                appendLine()
                appendLine("[HKEY_CURRENT_USER\\Software\\Classes\\chmlerp\\shell\\open\\command]")
                appendLine("@=\"$escaped\"")
            }

            val regFile = File(tempDir, "chmlfrp_register.reg")
            // Write as UTF-16LE with BOM (standard .reg encoding)
            regFile.outputStream().use { out ->
                out.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
                out.write(regContent.toByteArray(Charsets.UTF_16LE))
            }

            try {
                val pb = ProcessBuilder("reg", "import", regFile.absolutePath)
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val output = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                regFile.delete()
                println("已注册 chmlerp:// 协议处理器 ($javaExe)")
            } catch (e: Exception) {
                println("注册协议处理器失败: ${e.message}")
            }
        } else if (isLinux) {
            val javaExe = "$javaHome/bin/java"
            val desktopDir = File(System.getProperty("user.home"), ".local/share/applications")
            desktopDir.mkdirs()
            val desktopFile = File(desktopDir, "chmlfrp-handler.desktop")
            desktopFile.writeText(
                "[Desktop Entry]\n" +
                "Type=Application\n" +
                "Name=ChmlFrp CLI\n" +
                "Exec=$javaExe -jar $jarPath %u\n" +
                "MimeType=x-scheme-handler/chmlerp;\n" +
                "NoDisplay=true\n"
            )
            try {
                ProcessBuilder("update-desktop-database", desktopDir.absolutePath).start().waitFor()
            } catch (_: Exception) {}
            println("已注册 chmlerp:// 协议处理器")
        } else if (isMac) {
            // macOS: create a minimal .app bundle to register the URL scheme
            val javaExe = "$javaHome/bin/java"
            val appDir = File(System.getProperty("user.home"), "Library/Application Support/ChmlFrpCLI/chmlfrp-handler.app")
            val contentsDir = File(appDir, "Contents")
            val macosDir = File(contentsDir, "MacOS")
            macosDir.mkdirs()

            // Info.plist
            File(contentsDir, "Info.plist").writeText(
                """<?xml version="1.0" encoding="UTF-8"?>
                |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                |<plist version="1.0">
                |<dict>
                |    <key>CFBundleExecutable</key>
                |    <string>chmlfrp-handler</string>
                |    <key>CFBundleIdentifier</key>
                |    <string>com.shiyi.chmlfrp</string>
                |    <key>CFBundleName</key>
                |    <string>ChmlFrp CLI</string>
                |    <key>CFBundleURLTypes</key>
                |    <array>
                |        <dict>
                |            <key>CFBundleURLName</key>
                |            <string>ChmlFrp Protocol</string>
                |            <key>CFBundleURLSchemes</key>
                |            <array>
                |                <string>chmlerp</string>
                |            </array>
                |        </dict>
                |    </array>
                |</dict>
                |</plist>
                """.trimMargin()
            )

            // Executable script
            val handlerScript = File(macosDir, "chmlfrp-handler")
            handlerScript.writeText(
                "#!/bin/bash\n\"$javaExe\" -jar \"$jarPath\" \"\$1\"\n"
            )
            handlerScript.setExecutable(true)

            // Register with LaunchServices
            try {
                val lsregister = "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister"
                ProcessBuilder(lsregister, appDir.absolutePath).start().waitFor()
                println("已注册 chmlerp:// 协议处理器 (macOS)")
            } catch (e: Exception) {
                println("注册协议处理器失败: ${e.message}")
            }
        }
    }

    // ==================== OAuth2 Login ====================

    private fun runLogin(): Int {
        println("正在启动OAuth2登录流程...")

        val (codeVerifier, codeChallenge) = generatePkce()
        val state = UUID.randomUUID().toString().replace("-", "")

        registerProtocolHandler()

        val redirectEncoded = URLEncoder.encode(REDIRECT_URI, "UTF-8")
        val scopeEncoded = URLEncoder.encode(SCOPES, "UTF-8")
        val authUrl = "$AUTHORIZE_URL?response_type=code&client_id=$CLIENT_ID" +
                      "&redirect_uri=$redirectEncoded&scope=$scopeEncoded" +
                      "&state=$state&code_challenge=$codeChallenge&code_challenge_method=S256"

        println()
        println("==================================================")
        println("  请在浏览器中完成登录")
        println("  如果浏览器没有自动打开，请手动访问:")
        println("  $authUrl")
        println("==================================================")
        println()

        openBrowser(authUrl)

        println("等待浏览器回调（5分钟超时）...")
        println("(浏览器会通过 chmlerp:// 协议回调本程序)")
        println("(如果浏览器弹出\"想要打开此应用程序\"对话框，请点击\"打开\")")
        println("(临时文件: ${File(tempDir, OAUTH_RESPONSE_FILE).absolutePath})")

        val responseFile = File(tempDir, OAUTH_RESPONSE_FILE)
        responseFile.delete()

        val deadline = System.currentTimeMillis() + 5 * 60 * 1000
        while (System.currentTimeMillis() < deadline) {
            if (responseFile.exists()) {
                val content = responseFile.readText().trim()
                responseFile.delete()

                val params = content.split("&").filter { it.contains("=") }.associate {
                    val idx = it.indexOf("=")
                    it.substring(0, idx) to it.substring(idx + 1)
                }

                val error = params["error"]
                val code = params["code"]
                val returnedState = params["state"]

                when {
                    error != null -> {
                        println("登录失败: $error")
                        return 1
                    }
                    code != null && returnedState == state -> {
                        return exchangeCodeForToken(code, codeVerifier)
                    }
                    returnedState != state -> {
                        println("State验证失败，可能存在安全风险，请重试")
                        return 1
                    }
                    else -> {
                        println("回调数据异常")
                        return 1
                    }
                }
            }
            Thread.sleep(500)
        }

        println("登录超时，请重试")
        return 1
    }

    private fun exchangeCodeForToken(code: String, codeVerifier: String): Int {
        println("正在获取token...")
        try {
            val formBody = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("client_id", CLIENT_ID)
                .add("code_verifier", codeVerifier)
                .build()

            val request = Request.Builder().url(TOKEN_URL).post(formBody).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: throw Exception("响应为空")
                if (!response.isSuccessful) {
                    println("获取token失败: ${response.code}")
                    println(body)
                    return 1
                }

                val json = JSON.parseObject(body)
                accessToken = json.getString("access_token") ?: throw Exception("响应中缺少access_token")
                refreshToken = json.getString("refresh_token") ?: ""
                val expiresIn = json.getLong("expires_in") ?: 3600L
                expiresAt = System.currentTimeMillis() / 1000 + expiresIn

                saveTokenToConfig()
                println("登录成功！Token已保存到 $CONFIG_FILE")
                return 0
            }
        } catch (e: Exception) {
            println("获取token异常: ${e.message}")
            return 1
        }
    }

    private fun runLogout(): Int {
        loadTokenFromConfig()
        if (refreshToken.isNotEmpty()) {
            try {
                val formBody = FormBody.Builder()
                    .add("token", refreshToken)
                    .add("token_type_hint", "refresh_token")
                    .add("client_id", CLIENT_ID)
                    .build()
                val request = Request.Builder().url(REVOKE_URL).post(formBody).build()
                client.newCall(request).execute().close()
                println("已撤销服务端token")
            } catch (e: Exception) {
                println("服务端登出失败: ${e.message}")
            }
        }
        File(CONFIG_FILE).delete()
        println("已清除本地登录信息")
        return 0
    }

    // ==================== Token Management ====================

    private fun ensureValidToken(): Boolean {
        val now = System.currentTimeMillis() / 1000
        if (expiresAt > now + 60) return true
        if (refreshToken.isEmpty()) return false
        println("Token即将过期，正在刷新...")
        return doRefreshToken()
    }

    private fun doRefreshToken(): Boolean {
        try {
            val formBody = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", CLIENT_ID)
                .build()

            val request = Request.Builder().url(TOKEN_URL).post(formBody).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    println("刷新token失败: ${response.code}")
                    return false
                }
                val body = response.body?.string() ?: return false
                val json = JSON.parseObject(body)

                accessToken = json.getString("access_token") ?: return false
                refreshToken = json.getString("refresh_token") ?: refreshToken
                val expiresIn = json.getLong("expires_in") ?: 3600L
                expiresAt = System.currentTimeMillis() / 1000 + expiresIn

                saveTokenToConfig()
                println("Token已刷新")
                return true
            }
        } catch (e: Exception) {
            println("刷新token异常: ${e.message}")
            return false
        }
    }

    private fun loadTokenFromConfig() {
        try {
            val configFile = File(CONFIG_FILE)
            if (!configFile.exists()) return
            for (line in configFile.readLines()) {
                val t = line.trim()
                when {
                    t.startsWith("access_token=") -> accessToken = t.substringAfter("access_token=").trim()
                    t.startsWith("refresh_token=") -> refreshToken = t.substringAfter("refresh_token=").trim()
                    t.startsWith("expires_at=") -> expiresAt = t.substringAfter("expires_at=").trim().toLongOrNull() ?: 0
                }
            }
            if (accessToken.isNotEmpty()) println("已从配置文件加载token")
        } catch (e: Exception) {
            println("读取配置文件失败: ${e.message}")
        }
    }

    private fun saveTokenToConfig() {
        File(CONFIG_FILE).writeText(
            "access_token=$accessToken\n" +
            "refresh_token=$refreshToken\n" +
            "expires_at=$expiresAt\n"
        )
    }

    // ==================== API Calls ====================

    private fun authedRequest(url: String): Request.Builder {
        return Request.Builder().url(url).header("Authorization", "Bearer $accessToken")
    }

    private fun fetchRemoteConfigs() {
        println("正在获取隧道列表...")
        try {
            client.newCall(authedRequest(TUNNEL_API_URL).get().build()).execute().use { response ->
                if (response.code == 401) {
                    println("Token已过期，尝试刷新...")
                    if (doRefreshToken()) {
                        client.newCall(authedRequest(TUNNEL_API_URL).get().build()).execute().use { retry ->
                            val body = retry.body?.string() ?: throw Exception("响应为空")
                            parseConfigList(body)
                        }
                    } else {
                        println("Token刷新失败，请重新登录: chmlfrp-cli --login")
                    }
                    return
                }
                if (!response.isSuccessful) throw Exception("请求失败: ${response.code}")
                val body = response.body?.string() ?: throw Exception("响应为空")
                parseConfigList(body)
            }
        } catch (e: Exception) {
            println("获取配置列表失败: ${e.message}")
        }
    }

    private fun parseConfigList(json: String) {
        try {
            val apiResponse = JSON.parseObject(json, ApiResponse::class.java)
            if (apiResponse.code == 200 && apiResponse.state == "success") {
                configList.addAll(apiResponse.data)
                println("成功获取到 ${apiResponse.data.size} 个隧道配置")
            } else {
                println("API返回错误: ${apiResponse.msg}")
            }
        } catch (e: Exception) {
            println("解析JSON失败: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun fetchTunnelConfig(node: String, tunnelName: String): String? {
        val url = "$TUNNEL_CONFIG_API_URL?node=${URLEncoder.encode(node, "UTF-8")}" +
                  "&tunnel_names=${URLEncoder.encode(tunnelName, "UTF-8")}"
        return try {
            client.newCall(authedRequest(url).get().build()).execute().use { response ->
                if (response.code == 401 && doRefreshToken()) {
                    client.newCall(authedRequest(url).get().build()).execute().use { retry ->
                        val body = retry.body?.string() ?: return@use null
                        val json = JSON.parseObject(body)
                        if (json.getInteger("code") == 200) json.getString("data")
                        else { println("获取配置文件失败: ${json.getString("msg")}"); null }
                    }
                } else if (!response.isSuccessful) {
                    println("获取配置文件失败: ${response.code}")
                    null
                } else {
                    val body = response.body?.string() ?: return null
                    val json = JSON.parseObject(body)
                    if (json.getInteger("code") == 200) json.getString("data")
                    else { println("获取配置文件失败: ${json.getString("msg")}"); null }
                }
            }
        } catch (e: Exception) {
            println("获取配置文件异常: ${e.message}")
            null
        }
    }

    // ==================== Tunnel Management ====================

    // ==================== Local frpc Process Management ====================

    /**
     * Scan system for running frpc processes, return Map<tunnelId, PID>
     */
    private fun getRunningFrpcProcesses(): Map<Int, Long> {
        val result = mutableMapOf<Int, Long>()
        try {
            if (isWindows) {
                val proc = ProcessBuilder("wmic", "process", "where", "name='frpc.exe'", "get", "commandline,processid")
                    .redirectErrorStream(true).start()
                val output = proc.inputStream.bufferedReader(Charset.defaultCharset()).readText()
                proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)

                val pattern = Regex("frpc_(\\d+)")
                output.lineSequence().forEach { line ->
                    if (!line.contains("frpc_")) return@forEach
                    val match = pattern.find(line) ?: return@forEach
                    val tunnelId = match.groupValues[1].toIntOrNull() ?: return@forEach
                    val pid = line.trim().split(Regex("\\s+")).lastOrNull()?.toLongOrNull()
                    if (pid != null && pid > 0) result[tunnelId] = pid
                }
            } else {
                // Linux and macOS: use ps
                val proc = ProcessBuilder("sh", "-c", "ps -eo pid,args | grep '[f]rpc'")
                    .redirectErrorStream(true).start()
                val output = proc.inputStream.bufferedReader().readText()
                proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)

                val pattern = Regex("frpc_(\\d+)")
                output.lineSequence().forEach { line ->
                    val match = pattern.find(line) ?: return@forEach
                    val tunnelId = match.groupValues[1].toIntOrNull() ?: return@forEach
                    val pid = line.trim().split(Regex("\\s+"))[0].toLongOrNull()
                    if (pid != null && pid > 0) result[tunnelId] = pid
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private fun stopFrpc(pid: Long, name: String): Boolean {
        println("正在停止隧道: $name (PID: $pid) ...")
        return try {
            if (isWindows) {
                ProcessBuilder("cmd", "/c", "taskkill /f /t /fi \"WINDOWTITLE eq FRP - ${name}*\"")
                    .redirectErrorStream(true).start().let { p ->
                        p.inputStream.bufferedReader(Charset.defaultCharset()).readText()
                        p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                    }
                Thread.sleep(500)
            } else {
                ProcessBuilder("kill", "-9", pid.toString()).redirectErrorStream(true).start().let { p ->
                    p.inputStream.bufferedReader().readText(); p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                }
                Thread.sleep(300)
            }
            val stillRunning = getRunningFrpcProcesses()
            val alive = stillRunning.values.contains(pid) ||
                stillRunning.any { entry -> configList.find { c -> c.id == entry.key }?.name == name }
            if (!alive) {
                println("已停止: $name")
                true
            } else {
                if (isWindows) {
                    ProcessBuilder("cmd", "/c", "taskkill /f /pid $pid")
                        .redirectErrorStream(true).start().let { p ->
                            p.inputStream.bufferedReader(Charset.defaultCharset()).readText()
                            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                        }
                    Thread.sleep(300)
                }
                val finalCheck = getRunningFrpcProcesses()
                if (!finalCheck.values.contains(pid)) {
                    println("已停止: $name")
                    true
                } else {
                    println("停止失败: 进程仍在运行")
                    false
                }
            }
        } catch (e: Exception) {
            println("停止失败: ${e.message}")
            false
        }
    }

    private fun stopAllFrpc(running: Map<Int, Long>): Int {
        if (running.isEmpty()) {
            println("没有正在运行的frpc进程")
            return 0
        }
        println("正在停止 ${running.size} 个frpc进程...")
        var allOk = true
        // Build reverse map: tunnelId -> config
        val cfgMap = configList.associateBy { it.id }
        running.forEach { (tunnelId, pid) ->
            val name = cfgMap[tunnelId]?.name ?: "tunnel_$tunnelId"
            if (!stopFrpc(pid, name)) allOk = false
        }
        return if (allOk) 0 else 1
    }

    // ==================== Display ====================

    private fun displayWidth(s: String): Int {
        var w = 0
        for (ch in s) w += if (ch.code > 0x7F) 2 else 1
        return w
    }

    private fun pad(s: String, width: Int): String {
        val dw = displayWidth(s)
        return if (dw < width) s + " ".repeat(width - dw) else s
    }

    private fun row(vararg cols: String): String {
        return cols.joinToString(" | ")
    }

    private val colWidths = intArrayOf(4, 14, 14, 8, 22, 5, 6, 6)

    private fun displayConfigList(running: Map<Int, Long> = emptyMap()) {
        val headers = arrayOf("序号", "隧道名称", "节点", "本地端口", "远程端口/域名", "类型", "服务器", "本地")
        val totalWidth = colWidths.sumOf { it + 3 } - 1
        val sep = "-" + "-".repeat(totalWidth)

        println("可用的FRP隧道列表:")
        println(sep)
        println(headers.indices.joinToString(" | ") { pad(headers[it], colWidths[it]) })
        println(sep)
        configList.forEachIndexed { index, c ->
            val serverSt = if (c.nodestate == "online") "在线" else "离线"
            val localSt = if (running.containsKey(c.id)) "运行中" else "-"
            println(row(
                pad(index.toString(), colWidths[0]),
                pad(c.name, colWidths[1]),
                pad(c.node, colWidths[2]),
                pad(c.nport.toString(), colWidths[3]),
                pad(c.dorp, colWidths[4]),
                pad(c.type, colWidths[5]),
                pad(serverSt, colWidths[6]),
                pad(localSt, colWidths[7])
            ))
        }
        println(sep)
    }

    // ==================== Browser ====================

    private fun openBrowser(url: String) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                return
            }
        } catch (_: Exception) {}
        try {
            if (isWindows) {
                Runtime.getRuntime().exec(arrayOf("rundll32", "url.dll,FileProtocolHandler", url))
            } else if (isMac) {
                Runtime.getRuntime().exec(arrayOf("open", url))
            } else {
                Runtime.getRuntime().exec(arrayOf("xdg-open", url))
            }
        } catch (e: Exception) {
            println("无法自动打开浏览器，请手动访问上方链接")
        }
    }

    // ==================== FRP Client Start ====================

    private fun extractFrpc(): String {
        val tempDir = File("temp")
        if (!tempDir.exists()) tempDir.mkdir()

        // Determine platform-specific resource name
        val arch = when (val a = System.getProperty("os.arch").lowercase()) {
            "x86_64", "amd64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> a
        }
        val resourceName = when {
            isWindows -> "/frpc-windows-$arch.exe"
            isMac -> "/frpc-darwin-$arch"
            else -> "/frpc-linux-$arch"
        }
        val localName = if (isWindows) "frpc.exe" else "frpc"
        val frpcFile = File(tempDir, localName)

        // Extract embedded binary
        if (!frpcFile.exists() || frpcFile.length() < 100000) {
            val res = FrpClient::class.java.getResourceAsStream(resourceName)
            if (res != null) {
                res.use { input ->
                    frpcFile.outputStream().use { output -> input.copyTo(output) }
                }
                if (!isWindows) frpcFile.setExecutable(true)
                println("已释放内置 frpc ($resourceName)")
            } else {
                println("未找到内置 frpc ($resourceName)，尝试使用本地安装的 frpc")
            }
        }

        if (frpcFile.exists() && frpcFile.length() > 100000) return frpcFile.absolutePath

        // Fallback: look for local frpc
        val candidates = if (isWindows) {
            listOf(File("frpc.exe"), File(tempDir, "frpc.exe"))
        } else {
            listOf(File("frpc"), File("/usr/local/bin/frpc"), File("/usr/bin/frpc"))
        }
        for (f in candidates) {
            if (f.exists()) return f.absolutePath
        }
        // Try PATH
        try {
            val cmd = if (isWindows) arrayOf("where", "frpc.exe") else arrayOf("sh", "-c", "command -v frpc")
            val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val path = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (path.isNotEmpty() && File(path).exists()) return path
        } catch (_: Exception) {}

        println("错误: 未找到 frpc，请下载对应平台的 frp 并放在程序同目录")
        return localName
    }

    private fun startFrpClient(config: FrpConfig) {
        println("正在获取隧道配置: ${config.name} (${config.node})")

        val configContent = fetchTunnelConfig(config.node, config.name)
        if (configContent.isNullOrEmpty()) {
            println("无法获取隧道配置文件，无法启动")
            return
        }

        val tempDir = File("temp")
        if (!tempDir.exists()) tempDir.mkdir()

        val frpcPath = extractFrpc()
        val configFile = File(tempDir, "frpc_${config.id}.toml")
        configFile.writeText(configContent, Charsets.UTF_8)

        println()
        println("=== 隧道信息 ===")
        println("名称:   ${config.name}")
        println("节点:   ${config.node}")
        println("类型:   ${config.type}")
        println("本地:   ${config.localip}:${config.nport}")
        println("远程:   ${config.dorp}")
        println("节点IP: ${config.node_ip}")
        println("===============\n")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (isWindows) {
                    val batchFile = File(tempDir, "start_frpc_${config.id}.bat")
                    val batchContent = """
                        @echo off
                        chcp 936 >nul
                        echo ========================================
                        echo  FRP: ${config.name}
                        echo  Node: ${config.node} (${config.node_ip})
                        echo  Local: ${config.localip}:${config.nport}
                        echo  Remote: ${config.dorp}
                        echo  Type: ${config.type}
                        echo ========================================
                        "${frpcPath}" -c "${configFile.absolutePath}"
                        pause
                    """.trimIndent()
                    batchFile.writeText(batchContent.replace("\n", "\r\n"), Charset.forName("GBK"))

                    val pb = ProcessBuilder()
                    pb.command("cmd.exe", "/c", "start", "\"FRP - ${config.name}\"", batchFile.absolutePath)
                    pb.start()
                    println("FRP客户端已在新窗口中启动")
                } else {
                    val shellFile = File(tempDir, "start_frpc_${config.id}.sh")
                    val shellContent = """
                        #!/bin/bash
                        echo "========================================"
                        echo " FRP: ${config.name}"
                        echo " Node: ${config.node} (${config.node_ip})"
                        echo " Local: ${config.localip}:${config.nport}"
                        echo " Remote: ${config.dorp}"
                        echo " Type: ${config.type}"
                        echo "========================================"
                        "$frpcPath" -c "${configFile.absolutePath}"
                        echo "Press Enter to exit..."
                        read
                    """.trimIndent()
                    shellFile.writeText(shellContent, Charsets.UTF_8)
                    shellFile.setExecutable(true)

                    if (isMac) {
                        // macOS: use Terminal.app via osascript
                        try {
                            val script = """tell application "Terminal"
                                activate
                                do script "bash '${shellFile.absolutePath}'"
                            end tell"""
                            ProcessBuilder("osascript", "-e", script).start()
                            delay(1000)
                            println("FRP客户端已在Terminal中启动")
                        } catch (_: Exception) {
                            println("无法打开Terminal，请在当前终端手动运行: bash ${shellFile.absolutePath}")
                        }
                    } else {
                        // Linux: try various terminal emulators
                        val terminals = listOf(
                            arrayOf("gnome-terminal", "--", "bash", "-c", "bash ${shellFile.absolutePath}; exec bash"),
                            arrayOf("konsole", "--hold", "-e", "bash", shellFile.absolutePath),
                            arrayOf("xterm", "-hold", "-e", "bash", shellFile.absolutePath),
                            arrayOf("mate-terminal", "--", "bash", "-c", "bash ${shellFile.absolutePath}; exec bash"),
                            arrayOf("xfce4-terminal", "--hold", "-e", "bash ${shellFile.absolutePath}"),
                            arrayOf("lxterminal", "-e", "bash -c 'bash ${shellFile.absolutePath}; exec bash'"),
                            arrayOf("terminator", "-e", "bash -c 'bash ${shellFile.absolutePath}; exec bash'"),
                            arrayOf("alacritty", "-e", "bash", "-c", "bash ${shellFile.absolutePath}; exec bash"),
                            arrayOf("kitty", "bash", "-c", "bash ${shellFile.absolutePath}; exec bash")
                        )

                        var success = false
                        for (terminal in terminals) {
                            try {
                                val pb = ProcessBuilder(*terminal)
                                pb.redirectErrorStream(true)
                                val proc = pb.start()
                                delay(1000)
                                if (!proc.isAlive && proc.exitValue() != 0) continue
                                println("FRP客户端已在新窗口中启动 (${terminal[0]})")
                                success = true
                                break
                            } catch (_: Exception) {
                                continue
                            }
                        }

                        if (!success) {
                            try {
                                val pb = ProcessBuilder("x-terminal-emulator", "-e", "bash ${shellFile.absolutePath}")
                                pb.start()
                                delay(1000)
                                success = true
                                println("FRP客户端已在系统默认终端中启动")
                            } catch (_: Exception) {}
                        }

                        if (!success) {
                            println("无法找到可用的终端模拟器，将在当前终端运行")
                            val pb = ProcessBuilder("bash", shellFile.absolutePath)
                            pb.start()
                        }
                    }
                }
            } catch (e: Exception) {
                println("启动FRP客户端失败: ${e.message}")
            }
        }
    }

    companion object {
        private const val AUTHORIZE_URL = "https://account-api.qzhua.net/oauth2/authorize"
        private const val TOKEN_URL = "https://account-api.qzhua.net/oauth2/token"
        private const val REVOKE_URL = "https://account-api.qzhua.net/oauth2/revoke"
        private const val BASE_API_URL = "https://cf-v2.uapis.cn"
        private const val TUNNEL_API_URL = "https://cf-v2.uapis.cn/tunnel"
        private const val TUNNEL_CONFIG_API_URL = "https://cf-v2.uapis.cn/tunnel_config"
        private const val CLIENT_ID = "019f548df473745dad7f7a0789f6625b"
        private const val SCOPES = "openid profile email phone offline_access chmlfrp_api"
        private const val REDIRECT_URI = "chmlerp://oauth/callback"
        private const val CONFIG_FILE = "user.config"
        private const val OAUTH_RESPONSE_FILE = "chmlfrp_oauth_response.txt"
        private val tempDir = File(System.getProperty("java.io.tmpdir"))

        @JvmStatic
        fun main(args: Array<String>) {
            // Check if launched as protocol handler (chmlerp://...)
            if (args.isNotEmpty() && args[0].startsWith("chmlerp://")) {
                handleProtocolCallback(args[0])
                return
            }

            val exitCode = CommandLine(FrpClient())
                .setExecutionStrategy { parseResult ->
                    val banner = """
                        ________  ___  ___  _____ ______   ___       ________ ________  ________                ________  ___       ___
                        |\   ____\|\  \|\  \|\   _ \  _   \|\  \     |\  _____\\   __  \|\   __  \              |\   ____\|\  \     |\  \
                        \ \  \___|\ \  \\\  \ \  \\\__\ \  \ \  \    \ \  \__/\ \  \|\  \ \  \|\  \ ____________\ \  \___|\ \  \    \ \  \
                         \ \  \    \ \   __  \ \  \\|__| \  \ \  \    \ \   __\\ \   _  _\ \   ____\\____________\ \  \    \ \  \    \ \  \
                          \ \  \____\ \  \ \  \ \  \    \ \  \ \  \____\ \  \_| \ \  \\  \\ \  \___\|____________|\ \  \____\ \  \____\ \  \
                           \ \_______\ \__\ \__\ \__\    \ \__\ \_______\ \__\   \ \__\\ _\\ \__\                  \ \_______\ \_______\ \__\
                            \|_______|\|__|\|__|\|__|     \|__|\|_______|\|__|    \|__|\|__|\|__|                   \|_______|\|_______|\|__|
                        version 2.1.0
                    """.trimIndent()
                    println(CommandLine.Help.Ansi.AUTO.text("@|bold,cyan $banner|@"))
                    CommandLine.RunLast().execute(parseResult)
                }
                .setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.AUTO))
                .execute(*args)
            exitProcess(exitCode)
        }

        private fun handleProtocolCallback(url: String) {
            val logFile = File(tempDir, "chmlfrp_handler.log")
            try {
                logFile.appendText("[${java.util.Date()}] Callback received: $url\n")
                val uri = URI(url)
                val query = uri.rawQuery ?: ""
                logFile.appendText("[${java.util.Date()}] Extracted query: $query\n")
                File(tempDir, OAUTH_RESPONSE_FILE).writeText(query)
                logFile.appendText("[${java.util.Date()}] Response file written to: ${File(tempDir, OAUTH_RESPONSE_FILE).absolutePath}\n")
            } catch (e: Exception) {
                logFile.appendText("[${java.util.Date()}] ERROR: ${e.message}\n")
                e.printStackTrace(java.io.PrintWriter(java.io.FileWriter(logFile, true)))
            }
        }
    }
}

// ==================== Data Classes ====================

data class FrpConfig(
    val id: Int = 0,
    val name: String = "",
    val localip: String = "127.0.0.1",
    val type: String = "tcp",
    val nport: Int = 0,
    val dorp: String = "",
    val node: String = "",
    val state: String = "false",
    val userid: Int = 0,
    val encryption: String = "false",
    val compression: String = "false",
    val ap: String = "",
    val uptime: String = "",
    val client_version: String? = null,
    val today_traffic_in: Int = 0,
    val today_traffic_out: Int = 0,
    val cur_conns: Int = 0,
    val nodestate: String = "",
    val ip: String = "",
    val server_port: Int = 7000,
    val node_token: String = "",
    val node_ip: String = "",
    val node_ipv6: String? = null
)

data class ApiResponse(
    val msg: String,
    val code: Int,
    val data: List<FrpConfig>,
    val state: String
)
