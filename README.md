# Enterprise RAG

基于 Java 与 Vue 的企业知识库问答系统。通过文档解析、父子分块、混合检索和大模型生成，
将知识库中的内容组织为带原文引用的回答，并结合部门与知识库权限限制检索范围。

本仓库主要用于后端与前端源码备份和阅读。运行应用需要另行准备 PostgreSQL、Redis、MinIO
及模型服务，本地环境配置、部署脚本和历史验收资料不属于本次源码备份范围。

## 主要功能

| 能力 | 说明 |
| --- | --- |
| 身份与权限 | 登录、部门管理、知识库管理，以及用户、部门、角色维度的 READ / WRITE / ADMIN 授权 |
| 文档处理 | 支持 PDF、DOC、DOCX、XLSX、TXT、Markdown；原文件存入 MinIO，异步完成解析、分块和索引 |
| 文档版本 | 上传新版、重建索引、失败重试；新版本完成索引后再切换当前检索版本 |
| 混合检索 | pgvector 向量检索与 PostgreSQL FTS 关键词检索，经过 RRF 融合和 Reranker 精排 |
| 自适应问答 | SIMPLE / STANDARD / COMPLEX 路由、多轮问题改写、复杂问题拆解及 HyDE 辅助召回 |
| 流式回答 | SSE 输出、会话历史、生成取消，以及 PDF 页码、Word 章节、Excel 行等来源定位 |
| 证据核验 | 检查回答与本次入模证据的支持关系，展示核验状态和置信度 |
| 评估与用量 | 离线评估、调用与 Token 统计、成本估算、用量图表、管理员日志和检索追踪 |
| 请求治理 | 用户与部门限流、模型并发控制，以及随权限和文档版本校验的回答缓存 |

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3.5.11、Spring AI 1.1.2、Maven |
| 持久化 | MyBatis-Plus / MyBatis、Flyway、PostgreSQL 16、pgvector 0.8.6 |
| 身份认证 | Sa-Token、BCrypt |
| 缓存与存储 | Redis、MinIO |
| 前端 | Vue 3、TypeScript、Vite、Pinia、Vue Router、Axios |
| 内容与图表 | Markdown-it、ECharts |
| 通信 | REST API、SSE |

具体依赖版本以 [后端依赖](backend/pom.xml) 和 [前端依赖](frontend/package.json) 为准。

## RAG 处理流程

文档入库：

```text
上传与权限校验 → MinIO 保存原文件 → 文档解析 → Parent-Child 分块
→ Child Embedding → 向量与关键词索引 → 文档版本生效
```

问答检索：

```text
用户问题 → 当前身份与知识库权限 → 历史问题改写与路线选择
→ 向量 / 关键词召回 → RRF 融合 → 按路线精排 → Parent 上下文组装
→ SSE 回答与引用 → 证据核验 → 会话与用量记录
```

检索在数据库查询阶段过滤授权范围，并检查文档当前版本。复杂问题的子查询沿用同一授权范围，
HyDE 生成的假设文本仅辅助向量召回。引用保存来源快照，查看原文时再次检查访问权限。

## 源码结构

```text
.
├─ backend/
│  ├─ pom.xml
│  └─ src/
│     ├─ main/java/com/example/rag/
│     │  ├─ auth/、user/、department/    # 身份、用户与部门
│     │  ├─ knowledgebase/              # 知识库与 ACL
│     │  ├─ document/                   # 上传、解析、分块、索引与版本
│     │  ├─ rag/                        # 检索、生成、提示词与证据核验
│     │  ├─ conversation/               # 会话、消息与引用快照
│     │  ├─ evaluation/、usage/          # 离线评估与调用计量
│     │  └─ governance/、common/、system/ # 治理、公共能力与系统状态
│     ├─ main/resources/
│     │  ├─ application.yml             # 环境变量与业务预算配置
│     │  ├─ db/migration/               # Flyway 数据库迁移
│     │  ├─ mapper/                     # MyBatis SQL XML
│     │  ├─ prompts/                    # 提示词模板
│     │  └─ lua/                        # Redis 原子操作
│     └─ test/                          # 后端测试与测试资源
├─ frontend/
│  ├─ package.json
│  ├─ package-lock.json
│  ├─ src/
│  │  ├─ api/、types/                   # 接口封装与协议类型
│  │  ├─ views/、components/            # 页面与共用组件
│  │  ├─ stores/、router/               # 状态管理与路由
│  │  └─ config/、constants/、utils/     # 配置、常量与工具
│  └─ test/                            # 前端测试
└─ README.md
```

后端按业务模块组织，模块内部再分 controller、service、mapper、dto、domain 等职责。
SQL 位于 Mapper XML，提示词位于资源模板；前端将接口、类型、状态和页面分别组织。

## 推荐阅读入口

| 关注点 | 入口 |
| --- | --- |
| 应用启动与配置 | [RagApplication](backend/src/main/java/com/example/rag/RagApplication.java)、[application.yml](backend/src/main/resources/application.yml) |
| 知识库权限 | [PermissionScopeService](backend/src/main/java/com/example/rag/common/security/PermissionScopeService.java) |
| 文档索引任务 | [IndexTaskWorker](backend/src/main/java/com/example/rag/document/worker/index/IndexTaskWorker.java) |
| 混合检索 | [HybridRetrievalService](backend/src/main/java/com/example/rag/rag/retrieve/service/HybridRetrievalService.java) |
| 自适应检索 | [AdaptiveRetrievalService](backend/src/main/java/com/example/rag/rag/adaptive/service/AdaptiveRetrievalService.java) |
| 流式问答 | [ChatService](backend/src/main/java/com/example/rag/rag/chat/service/ChatService.java)、[前端 SSE 处理](frontend/src/api/sse.ts) |
| 证据核验 | [EvidenceJudge](backend/src/main/java/com/example/rag/rag/faithfulness/service/EvidenceJudge.java) |
| 前端页面 | [路由配置](frontend/src/router/index.ts)、[问答页面](frontend/src/views/ChatView.vue) |

## 编译与本地运行

开发工具使用 JDK 21.0.10、Maven 3.9.4、Node.js 24.11.1 和 npm 11.6.4。
在项目根目录执行：

```bash
# 打包后端源码，跳过测试编译与执行
mvn -f backend/pom.xml -Dmaven.test.skip=true package

# 安装前端依赖并检查、构建
npm --prefix frontend ci
npm --prefix frontend run lint
npm --prefix frontend run typecheck
npm --prefix frontend test
npm --prefix frontend run build
```

启动后端前，需要准备 PostgreSQL 16 与 pgvector 0.8.6、Redis 和 MinIO 私有桶，
并按 [application.yml](backend/src/main/resources/application.yml) 设置数据库、Redis、MinIO 连接环境变量。
文档向量化、回答生成和精排分别使用 `EMBEDDING_*`、`LLM_*`、`RERANK_*` 配置；
切换向量模型后，需要重建文档索引。API Key 与密码通过本地环境提供。

准备好上述依赖后，分别在两个终端执行：

```bash
mvn -f backend/pom.xml spring-boot:run
```

```bash
npm --prefix frontend run dev
```

前端默认访问地址为 `http://127.0.0.1:5173`，后端默认端口为 `8080`。
Vite 将 `/api` 与健康接口代理到后端，可通过 `BACKEND_URL` 修改目标。
后端启动时自动应用 Flyway 迁移；开发账号初始化需显式启用 `dev` profile 与 `RAG_SEED_ENABLED`，
并设置三个 `SEED_*_PASSWORD`，具体逻辑见 [DevSeedInitializer](backend/src/main/java/com/example/rag/auth/initializer/seed/DevSeedInitializer.java)。

源码中保留了测试，但部分后端契约测试依赖未随本次备份提交的资料，前端的格式、可读性和魔法值检查
依赖根目录开发脚本。执行完整工程验收时，需要补齐这些配套文件与外部基础设施。
