# stockmind-agent

初始化来源：`/Users/huang/Documents/stockMind Agent.rtf`

## 版本基线
- AgentScope Java: `2.0.0-RC2`
- Java: `17+`
- Spring Boot: `3.x`（当前初始化为 `3.3.0`）
- Redis: `7.x`
- MySQL: `8.x`
- Reactor BOM: `2025.0.2`

## 模块划分（标准 Java 分层）
- `stockmind-common`: 公共基础能力
- `stockmind-domain`: 领域模型与领域服务
- `stockmind-application`: 应用服务编排
- `stockmind-infrastructure`: 基础设施实现（Redis/MySQL）
- `stockmind-bootstrap`: Spring Boot 启动与对外接口


## 新增独立 Agent 模块
- `javascope-agent`: 独立承载 javascope agent 能力
  - 无 `agentscope-harness` 依赖，执行器由 `MainAgentRuntime` 直接调用 OpenAI 兼容 Chat Completions API
  - 主配置：`AgentRuntimeProperties` + `application-agent-runtime.yml`
  - 通过 `AgentPromptProvider`、`AgentToolExecutor` 两个 SPI 注入业务提示词和业务工具

## 运行与提问

### 1) 配置大模型 API（必填）

DashScope 示例：

```bash
export AGENT_RUNTIME_PROVIDER=dashscope
export AGENT_RUNTIME_MODEL=qwen-plus
export AGENT_RUNTIME_API_KEY=你的真实Key
# 可选，私有网关时填写
export AGENT_RUNTIME_BASE_URL=
```

OpenAI/兼容接口示例：

```bash
export AGENT_RUNTIME_PROVIDER=openai
export AGENT_RUNTIME_MODEL=gpt-4o-mini
export AGENT_RUNTIME_API_KEY=你的真实Key
# OpenAI兼容网关时填写，例如 https://api.openai.com/v1
export AGENT_RUNTIME_BASE_URL=
```

### 2) 启动项目

```bash
cd /Users/huang/Documents/agent/stockmind-agent
mvn -pl stockmind-bootstrap -am spring-boot:run
```

### 3) 提问（HTTP）

```bash
curl 'http://localhost:8080/api/agent/demo?input=帮我分析AAPL今天是否值得关注'
```

### 4) 对话模式（HTTP）

```bash
curl -X POST 'http://localhost:8080/api/agent/chat' \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"chat-001","userId":"demo-user","input":"先给我看AAPL短线观点"}'

curl -X POST 'http://localhost:8080/api/agent/chat' \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"chat-001","userId":"demo-user","input":"基于你上一次的结论，再给我一个入场条件"}'
```

### 5) 前端对话页面

启动服务后，浏览器打开：

```text
http://localhost:8080/chat.html
```
