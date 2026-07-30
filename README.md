# chmlFrp-cli

命令行工具，用于获取远程FRP配置并启动FRP客户端，支持 **Windows / Linux / macOS** 三平台。

**这个做出来其实是给我自己用的，因为平时启动多个客户端我嫌麻烦**

## 功能特点

- **OAuth2 登录**：通过浏览器 PKCE 认证，无需手动复制 token
- **隧道管理**：交互式启动/停止本地 frpc 进程，实时显示运行状态
- **内嵌 frp v0.70.1**：Windows/Linux amd64 + macOS Intel/Apple Silicon 四平台二进制，开箱即用
- **跨平台**：Windows / Linux / macOS 全支持
- **自动刷新 token**：token 过期自动续期，无需重新登录
- **多隧道同时运行**：每个隧道在新终端窗口中独立运行

## 系统要求

- Java 21 或更高版本（[下载地址](https://www.azul.com/downloads/#zulu)）

> frp 客户端已内嵌，无需额外下载。Linux arm64 / mips 等非主流架构请自行下载对应 frp 放到程序同目录。

## 安装

从 [Releases](https://github.com/ShiYioo/chmlfrp-cli/releases) 下载最新 JAR 文件即可。

## 使用方法

### 首次使用 — 登录

```bash
java -jar chmlFrp-cli-2.0.0.jar --login
```

浏览器会自动打开 ChmlFrp 登录页面，登录后会弹出协议确认框，点击「打开」即可完成登录。

### 交互模式（推荐）

```bash
java -jar chmlFrp-cli-2.0.0.jar
```

```
序号 | 隧道名称     | 节点       | 本地端口 | 远程端口/域名      | 类型 | 服务器 | 本地
0    | xiaodushi   | 日本东京-2 | 5173     | 59146              | tcp  | 离线   | -
1    | travel      | 中国香港   | 9090     | aibuild.shiyio.uk  | http | 在线   | 运行中

操作: [数字]启动/停止  a全部停止  exit退出
> 0        ← 启动隧道 0
> 0        ← 再次按 0 停止该隧道
> a        ← 停止所有本地 frpc
> exit     ← 退出
```

### 命令行参数

```
用法: chmlFrp-cli [--login] [--logout] [-l] [-s=<n>] [--stop=<n>] [--stop-all] [--token=<t>]
  --login                 通过浏览器 OAuth 登录
  --logout                退出登录并清除凭据
  -l, --list              列出所有隧道配置
  -s, --select=<n>        启动指定序号的隧道
      --stop=<n>          停止指定序号的本地 frpc
      --stop-all          停止所有本地 frpc
      --token=<t>         直接指定 access_token（跳过 OAuth）
  -h, --help              显示帮助
  -V, --version           显示版本
```

### 示例

```bash
# 登录
java -jar chmlFrp-cli-2.0.0.jar --login

# 列出隧道
java -jar chmlFrp-cli-2.0.0.jar --list

# 启动第 0 个隧道
java -jar chmlFrp-cli-2.0.0.jar --select=0

# 停止第 1 个隧道
java -jar chmlFrp-cli-2.0.0.jar --stop=1

# 停止所有
java -jar chmlFrp-cli-2.0.0.jar --stop-all

# 退出登录
java -jar chmlFrp-cli-2.0.0.jar --logout
```

## 配置文件

登录后会在当前目录生成 `user.config`：

```
access_token=eyJhbGci...
refresh_token=eyJhbGci...
expires_at=1785379124
```

## 内嵌的 frp 二进制

| 文件 | 平台 |
|------|------|
| `frpc-windows-amd64.exe` | Windows x64 |
| `frpc-linux-amd64` | Linux x64 |
| `frpc-darwin-amd64` | macOS Intel |
| `frpc-darwin-arm64` | macOS Apple Silicon |

来源：[fatedier/frp v0.70.1](https://github.com/fatedier/frp/releases/tag/v0.70.1)

## 构建项目

```bash
./gradlew shadowJar
```

生成文件：`build/libs/chmlFrp-cli-2.0.0.jar`

## 发布

推送 `v*` 格式的 tag 自动触发 GitHub Actions 构建并发布 Release：

```bash
git tag v2.1.0
git push origin v2.1.0
```

## 依赖项

- Kotlin Coroutines：异步操作
- Fastjson2：JSON 解析
- OkHttp：HTTP 客户端
- Picocli：命令行参数解析

## 版本信息

当前版本: **2.0.0**

## 作者

Shi Yi
