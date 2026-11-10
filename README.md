# 私有知识库 RAG 问答系统（Java）

基于 Java 自主封装的 RAG 后端服务，不依赖 AnythingLLM 等现成产品。完整实现
**文档解析 → 文本切分 → 向量化 → Milvus 向量入库 → 相似度检索 → Prompt 组装 → 本地大模型问答 → Redis 缓存**链路，
对外提供 REST 接口，支撑企业内部文档问答。

## RAG 核心链路（src/main/java/com/example/rag）

document/：Tika 解析 PDF/Word/Excel/PPT/Markdown/TXT + LangChain4j DocumentSplitters.recursive 递归分块（段落→行→句→词，带重叠）
embedding/：Ollama /api/embed 批量向量化（旧接口自动回退），离线哈希向量模型用于本地测试
vectorstore/：自实现 LangChain4j EmbeddingStore 的 MilvusChunkStore（显式 Schema、COSINE/AUTOINDEX、按文档/知识库过滤与删除）+ InMemoryChunkStore
service/：异步大文档分片入库（分批向量化、整体 deadline、单批超时、状态落库 PROCESSING/READY/FAILED）、检索、Prompt 组装（带 [1][2] 引用与长度上限）、问答
llm/：Ollama /api/chat，连接/读取超时 + 重试 + 504/502 错误归类
cache/：Redis 高频问答缓存（TTL、按知识库失效）+ 内存实现；无召回不缓存、文档删除即失效
repository/：JdbcTemplate 文档元数据（H2/MySQL 双脚本）

## 技术栈

| 组件 | 版本 / 说明 |
| --- | --- |
| Java / Spring Boot | 17 / 3.2.12 |
| LangChain4j | 1.0.0（`Document`、`DocumentSplitters`、`EmbeddingModel`、`EmbeddingStore`） |
| Milvus | 官方 Java SDK 2.6.27 + Milvus 2.5（COSINE / AUTOINDEX） |
| MySQL / H2 | 文档元数据；prod 用 MySQL 8，dev/test 用 H2 内存库 |
| Redis | 高频问答结果缓存（带 TTL、按知识库失效） |
| Ollama | `/api/embed` 向量化 + `/api/chat` 生成（支持超时重试与旧接口回退） |
| Apache Tika | PDF / Word / Excel / PPT / HTML / TXT / Markdown 解析 |
| Docker Compose | 一键拉起全部中间件 |

## 运行前提

- **JDK 17**（Spring Boot 3 / LangChain4j 1.x 要求；本机默认 JDK 8 时需切换 `JAVA_HOME`）
- Maven 3.9+

```powershell
# Windows PowerShell 临时切换 JDK 示例
$env:JAVA_HOME='C:\Path\To\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
java -version
```

## 快速开始（dev：零外部依赖）

`dev` 是默认 profile，使用 H2 + 内存向量库 + 离线哈希向量 + 模拟生成模型，
**不需要 Docker、Milvus、MySQL、Redis、Ollama** 即可跑通完整链路，专门用于本地开发与单元测试。

```bash
mvn spring-boot:run
# 或 mvn package && java -jar target/rag-knowledge-1.0.0.jar
```

上传文档并提问：

```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -F "file=@./员工手册.md" -F "knowledgeBase=hr"

# 入库是异步的，稍等后查看状态
curl "http://localhost:8080/api/documents?knowledgeBase=hr"

curl -X POST http://localhost:8080/api/qa/ask -H "Content-Type: application/json" \
  -d '{"question":"年假申请需要提前几天提交？","knowledgeBase":"hr"}'
```

## 生产模式（真实中间件）

```bash
# 1. 启动依赖（Milvus + etcd + MinIO + MySQL + Redis + Ollama）
docker compose up -d

# 2. 拉取本地模型（首次执行）
docker exec -it rag-knowledge-ollama-1 ollama pull nomic-embed-text
docker exec -it rag-knowledge-ollama-1 ollama pull qwen2.5:7b

# 3. 启动服务
mvn spring-boot:run -Dspring-boot.run.profiles=prod
# 或把应用也放进 compose：docker compose --profile app up -d --build
```

注意：`rag.embedding.dimension`（默认 768，对应 `nomic-embed-text`）必须与
`rag.vector.dimension`、Milvus 集合维度一致；更换 Embedding 模型时三者要同步修改。

## REST 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/documents/upload` | multipart 上传文档（`file`、`knowledgeBase`），异步向量入库，返回 202 |
| GET | `/api/documents` | 文档列表，可按 `knowledgeBase` 过滤 |
| GET | `/api/documents/{id}` | 文档详情与入库状态 |
| DELETE | `/api/documents/{id}` | 删除元数据、原始文件与全部向量，并失效相关缓存 |
| POST | `/api/qa/ask` | 知识库问答，返回答案 + 引用来源 + 缓存标记 |
| POST | `/api/retrieval/search` | 直接查看召回片段与相关度，用于评估召回率 |
| DELETE | `/api/qa/cache` | 手动清理指定知识库的问答缓存 |

`/api/qa/ask` 请求示例：

```json
{
  "question": "差旅报销发票几天内提交？",
  "knowledgeBase": "finance",
  "documentId": null,
  "topK": 5,
  "minScore": 0.65
}
```

## 设计与边界处理

- **大文档分片**：按 `rag.ingest-batch-size` 分批调用向量模型、分批写入 Milvus；
  整体受 `rag.ingest-timeout-seconds` deadline 约束，单批向量化独立超时，避免长文档阻塞请求线程。
- **异步入库**：上传请求立即返回 `PROCESSING`，独立线程池处理，状态落库为 `READY / FAILED`（含错误信息）。
- **超时 / 异常**：Ollama 调用带连接/读取超时与重试；超时归类为 504、模型不可用为 502；
  缓存读写异常不阻断主链路；无召回时不调用大模型并给出明确提示。
- **缓存一致性**：缓存 Key 由「知识库 + 文档 + 检索参数 + 问题 + 模型」生成；
  文档删除后按知识库前缀失效；无召回结果不缓存，避免新增文档后一直返回旧结论。
- **幂等**：重新入库前先删除该文档旧向量，chunk ID 采用 `documentId:index` 稳定格式。
- **Prompt 安全**：系统提示词限定只依据参考资料作答，`rag.max-context-chars` 控制上下文长度，
  片段带 `[1] [2]` 编号便于答案溯源。

## 召回率调优

`src/test/java/.../RecallEvaluationTest.java` 用可控语料对
`chunkSize ∈ {180,220,400,800}` × `minScore ∈ {0.55,0.65,0.75}` 做网格评估，
输出 Recall@K / Precision 矩阵并给出推荐参数。运行：

```bash
mvn test
```

调优项集中在 `application.yml` 的 `rag.*`：`chunk-size`、`chunk-overlap`、
`retrieval-top-k`、`similarity-threshold`、`max-context-chars`。
注意 `similarity-threshold` 使用相关度分数 `(cosine + 1) / 2 ∈ [0,1]`，
阈值越高召回越少、精度越高。

## 目录结构

```
src/main/java/com/example/rag
├── config/        配置属性与条件装配（memory / milvus、hashing / ollama）
├── controller/    REST 接口
├── service/       入库链路、检索、问答、Prompt 组装、文档管理
├── document/      Tika 解析 + LangChain4j 递归分块
├── embedding/     Ollama / 离线哈希向量模型
├── vectorstore/   Milvus / 内存 EmbeddingStore 实现
├── llm/           Ollama 生成模型、超时重试
├── cache/         Redis / 内存问答缓存
└── repository/    JdbcTemplate 文档元数据
```
