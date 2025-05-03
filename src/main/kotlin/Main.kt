package com.shiyi

import com.alibaba.fastjson2.JSON
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.Callable
import kotlin.system.exitProcess


/**
 * @author Shi Yi
 * @date 2025/5/2
 * @Description chmlFRP客户端命令行工具
 */
@Command(
    name = "chmlFrp-cli",
    version = ["1.0.1"],
    description = ["命令行工具，用于获取远程FRP配置并启动FRP客户端"]
)
class FrpClient : Callable<Int> {

    @Option(names = ["-u", "--url"], description = ["远程配置列表的URL"], required = false)
    private var url: String = "http://cf-v2.uapis.cn/tunnel" // 替换为实际的URL

    @Option(names = ["-l", "--list"], description = ["列出所有可用的FRP配置"], required = false)
    private var listConfigs: Boolean = false

    @Option(names = ["-s", "--select"], description = ["选择配置的序号"], required = false)
    private var selectIndex: Int = -1

    private var token: String = ""

    private val client = OkHttpClient()
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val configList = mutableListOf<FrpConfig>()

    override fun call(): Int {
        try {
            // 在Linux上设置当前目录的权限
            if (!isWindows) {
                try {
                    println("正在设置当前目录权限...")
                    val currentDir = File(".").absolutePath
                    val processBuilder = ProcessBuilder("chmod", "-R", "777", currentDir)
                    val process = processBuilder.start()
                    process.waitFor()
                    println("已设置当前目录权限为777")
                } catch (e: Exception) {
                    println("设置目录权限失败: ${e.message}")
                }
            }

            // 从配置文件读取token
            loadTokenFromConfig()

            if (token.isEmpty()) {
                println("错误: 未找到有效的token，请检查user.config文件")
                return 1
            }

            // 获取远程配置列表
            fetchRemoteConfigs()

            if (configList.isEmpty()) {
                println("未找到可用的FRP配置")
                return 1
            }

            // 如果指定了--list参数，显示配置列表并退出
            if (listConfigs) {
                displayConfigList()
                return 0
            }

            // 如果指定了--select参数，启动对应的FRP客户端
            if (selectIndex >= 0) {
                if (selectIndex < configList.size) {
                    startFrpClient(configList[selectIndex])
                    return 0
                } else {
                    println("无效的配置序号: $selectIndex")
                    return 1
                }
            }

            // 如果没有指定参数，显示配置列表并提示用户选择
            while (true) {
                displayConfigList()
                print("请选择配置序号 (0-${configList.size - 1}), 输入q退出: ")
                val input = readLine()

                if (input?.lowercase() == "q") {
                    return 0
                }

                val index = input?.toIntOrNull() ?: -1

                if (index in 0 until configList.size) {
                    startFrpClient(configList[index])
                    println("\n已启动FRP客户端，按Enter键继续...")
                    readLine() // 等待用户按Enter继续
                } else {
                    println("无效的配置序号: $index")
                }
            }

        } catch (e: Exception) {
            System.err.println("错误: ${e.message}")
            return 1
        }
    }

    /**
     * 从远程URL获取FRP配置列表
     */
    private fun fetchRemoteConfigs() {
        println("正在从 $url 获取配置列表...")

        try {
            val urlWithToken = if (url.contains("?")) {
                "$url&token=$token"
            } else {
                "$url?token=$token"
            }

            val request = Request.Builder()
                .url(urlWithToken)
                .build()

            println("请求URL: $urlWithToken")

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("请求失败: ${response.code}")
                }

                val responseBody = response.body?.string() ?: throw IOException("响应内容为空")
                parseConfigList(responseBody)
            }
        } catch (e: Exception) {
            println("获取配置列表失败: ${e.message}")
        }
    }

    /**
     * 解析配置列表JSON
     */
    private fun parseConfigList(json: String) {
        try {
            println("接收到的JSON数据:")
            println(json)

            // 使用FastJSON2正确解析,之前忘记加反射了，以为是hutool的原因才换成fastjson的
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

    /**
     * 显示配置列表
     */
    private fun displayConfigList() {
        println("可用的FRP隧道列表:")
        println("--------------------------------------------------")
        println("序号 | 隧道名称 | 节点 | 本地端口 | 远程端口 | 类型")
        println("--------------------------------------------------")

        configList.forEachIndexed { index, config ->
            println("$index | ${config.name} | ${config.node} | ${config.nport} | ${config.dorp} | ${config.type}")
        }

        println("--------------------------------------------------")
    }

    /**
     * 启动FRP客户端
     */
    private fun startFrpClient(config: FrpConfig) {
        println("正在启动FRP客户端: ${config.name}")

        val tempDir = File("temp")
        if (!tempDir.exists()) {
            tempDir.mkdir()
            println("创建临时目录: ${tempDir.absolutePath}")
        }

        val frpcPath = if (isWindows) {
            File("frpc.exe").absolutePath
        } else {
            File("frpc").absolutePath
        }

        val batchFile = if (isWindows) {
            File(tempDir, "start_frpc_${config.id}.bat")
        } else {
            File(tempDir, "start_frpc_${config.id}.sh")
        }

        val batchContent = if (isWindows) {
            """
                @echo off
                chcp 936
                SET info_title=FRP Client Information
                                SET info_name=${config.name}
                                SET info_node=${config.node}
                                SET info_server=${config.ip}
                                SET info_local=${config.localip}:${config.nport}
                                SET info_remote=${config.dorp}
                                SET info_type=${config.type}
                                SET info_id=${config.id}
                echo %info_title%
                echo ----------------------------------------
                echo Name: %info_name%
                echo Node: %info_node%
                echo Server: %info_server%
                echo Local: %info_local%
                echo Remote Port: %info_remote%
                echo Type: %info_type%
                echo ID: %info_id%
                echo ----------------------------------------
                "$frpcPath" -u "$token" -p "${config.id}"
                pause
            """.trimIndent()
        } else {
            """
                #!/bin/bash
                echo "正在启动FRP客户端..."
                echo "隧道名称: ${config.name}"
                echo "节点: ${config.node}"
                echo "服务器: ${config.ip}"
                echo "本地IP: ${config.localip}"
                echo "本地端口: ${config.nport}"
                echo "远程端口: ${config.dorp}"
                echo "类型: ${config.type}"
                echo "隧道id: ${config.id}"
                "$frpcPath" -u "$token" -p "${config.id}"
                echo "按Enter键退出..."
                read
            """.trimIndent()
        }

        if(isWindows){
            val outputStream = batchFile.outputStream()
            outputStream.use { out ->
                out.write(batchContent.toByteArray(Charset.forName("GBK")))
            }
        }else{
            batchFile.writeText(batchContent, Charsets.UTF_8)
        }
        println("创建批处理文件: ${batchFile.absolutePath}")

        if (!isWindows) {
            try {
                val processBuilder = ProcessBuilder("chmod", "+x", batchFile.absolutePath)
                processBuilder.start().waitFor()
                println("已设置脚本执行权限")
            } catch (e: Exception) {
                println("设置执行权限失败: ${e.message}")
            }
        }

        // 使用协程启动FRP客户端，不阻塞主线程
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (isWindows) {
                    val processBuilder = ProcessBuilder()
                    processBuilder.command("cmd.exe", "/c", "start", "\"FRP客户端 - ${config.name}\"", batchFile.absolutePath)
                    val process = processBuilder.start()
                    println("FRP客户端已在新窗口中启动")
                } else {
                    // 在想怎么确保每个Linux都能打开新窗口，这里直接暴力遍历每一个终端
                    val terminals = listOf(
                        arrayOf("gnome-terminal", "--", "bash", "-c", "bash ${batchFile.absolutePath}; exec bash"),
                        arrayOf("konsole", "--hold", "-e", "bash", batchFile.absolutePath),
                        arrayOf("xterm", "-hold", "-e", "bash", batchFile.absolutePath),
                        arrayOf("mate-terminal", "--", "bash", "-c", "bash ${batchFile.absolutePath}; exec bash"),
                        arrayOf("xfce4-terminal", "--hold", "-e", "bash ${batchFile.absolutePath}"),
                        arrayOf("lxterminal", "-e", "bash -c 'bash ${batchFile.absolutePath}; exec bash'"),
                        arrayOf("terminator", "-e", "bash -c 'bash ${batchFile.absolutePath}; exec bash'"),
                        arrayOf("alacritty", "-e", "bash", "-c", "bash ${batchFile.absolutePath}; exec bash"),
                        arrayOf("kitty", "bash", "-c", "bash ${batchFile.absolutePath}; exec bash")
                    )

                    var success = false
                    for (terminal in terminals) {
                        try {
                            println("尝试使用终端: ${terminal[0]}")
                            val processBuilder = ProcessBuilder(*terminal)
                            processBuilder.redirectErrorStream(true)
                            val process = processBuilder.start()

                            // 给进程一点时间启动
                            delay(1000)

                            // 检查进程是否已经退出
                            if (!process.isAlive) {
                                val exitCode = process.exitValue()
                                println("终端 ${terminal[0]} 退出，退出码: $exitCode")
                                if (exitCode != 0) {
                                    continue
                                }
                            }

                            println("FRP客户端已在新窗口中启动 (使用 ${terminal[0]})")
                            success = true
                            break
                        } catch (e: Exception) {
                            println("尝试 ${terminal[0]} 失败: ${e.message}")
                            continue
                        }
                    }

                    if (!success) {
                        // 尝试使用x-terminal-emulator
                        try {
                            println("尝试使用系统默认终端 x-terminal-emulator")
                            val processBuilder = ProcessBuilder("x-terminal-emulator", "-e", "bash ${batchFile.absolutePath}")
                            val process = processBuilder.start()
                            delay(1000)
                            success = true
                            println("FRP客户端已在系统默认终端中启动")
                        } catch (e: Exception) {
                            println("尝试系统默认终端失败: ${e.message}")
                        }
                    }

                    if (!success) {
                        // 如果所有终端都失败，回退到直接在当前终端运行
                        println("无法找到可用的终端模拟器，将在当前终端运行FRP客户端")
                        val processBuilder = ProcessBuilder()
                        processBuilder.command("bash", batchFile.absolutePath)
                        val process = processBuilder.start()
                        println("FRP客户端已启动")
                    }
                }
            } catch (e: Exception) {
                println("启动FRP客户端失败: ${e.message}")
            }
        }
    }

    /**
     * 从配置文件加载token
     */
    private fun loadTokenFromConfig() {
        try {
            val configFile = File("user.config")
            if (!configFile.exists()) {
                println("配置文件不存在，将创建新的配置文件")
                configFile.writeText("token=请在此处填写您的token")
                println("已创建配置文件: ${configFile.absolutePath}")
                println("请编辑配置文件，填写您的token后重新运行程序")
                return
            }

            // 读取配置文件
            val configContent = configFile.readText()
            val tokenLine = configContent.lines().find { it.trim().startsWith("token=") }

            if (tokenLine != null) {
                token = tokenLine.substringAfter("token=").trim()
                println("已从配置文件加载token")
            } else {
                println("配置文件中未找到token设置")
            }
        } catch (e: Exception) {
            println("读取配置文件失败: ${e.message}")
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
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
    version 1.0.1
                    """
                    println(CommandLine.Help.Ansi.AUTO.text("@|bold,cyan $banner|@"))

                    // 继续正常执行
                    CommandLine.RunLast().execute(parseResult)
                }
                .setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.AUTO))
                .execute(*args)
            exitProcess(exitCode)
        }
    }
}

/**
 * FRP配置数据类
 */
data class FrpConfig(
    val id: Int = 0,
    val name: String = "",
    val localip: String = "127.0.0.1",
    val type: String = "tcp",
    val nport: Int = 0,
    val dorp: String = "",
    val node: String = "",
    val state: String = "true",
    val userid: Int = 0,
    val encryption: String = "false",
    val compression: String = "false",
    val ap: String = " ",
    val uptime: String = "",
    val client_version: String = "",
    val today_traffic_in: Int = 0,
    val today_traffic_out: Int = 0,
    val cur_conns: Int = 0,
    val nodestate: String = "",
    val ip: String = ""
)

/**
 * API响应数据类
 */
data class ApiResponse(
    val msg: String,
    val code: Int,
    val data: List<FrpConfig>,
    val state: String
)
