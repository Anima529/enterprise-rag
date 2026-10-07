# Enterprise RAG 前端（Phase 11）

2026-10-07 文档详情中的“解析正文预览”和“父子分块预览”改为两个按钮，分别打开独立预览弹窗。
正文分页、父子类型筛选、分块分页与来源表格继续可用；关闭预览返回原详情位置，关闭详情同时清理预览。

2026-10-07 将问答中的“查看本次检索追踪”改为当前会话内直接弹窗，保留草稿、地址和阅读位置。
问答与检索调试复用同一追踪内容展示，读取仍由后端验证管理员身份、请求归属和知识库权限。

2026-10-07 将检索诊断、自动路线追踪、实验启动与详情对比、日志详情统一放入独立结果弹窗。
点击后立即显示加载状态，结果与错误在窗口内呈现；支持关闭按钮、Esc、遮罩关闭和内部滚动，
关闭后的迟到响应不会恢复内容。后台评估继续运行，列表轮询及原有引用、文档详情弹窗保持可用。

2026-10-07 统一移除登录、知识库、文档、聊天与管理页面错误提示中的 requestId 后缀，
HTTP 与 SSE 错误仅展示原因。追踪 ID 继续保留在响应、错误对象、日志及诊断页面中。

2026-10-06 完成工作台视觉与体验重构：统一深墨绿/暖白设计体系、分组侧栏、员工任务入口、响应式登录页、
独立滚动的问答阅读区、键盘发送与引用面板。员工日常页面和管理页面使用同一套表单、表格与状态样式。
设计自检、截图、浏览器复核方法见 [视觉与体验验收](../docs/frontend-design/README.md)。

2026-10-06 为“我的用量”和“用量管理”增加 ECharts 调用趋势、模型占比、成本估算覆盖和阶段对比，
默认近 7 天并支持今天、近 30 天与自定义日期。保留真实账本和未知成本语义，图表按需加载。
实现边界、生产截图及复核方法见 [用量图表验收](../docs/usage-charts/README.md)。

Node 24.11.1 / npm 11.6.4。Vue 3 + TypeScript strict + Vite，已初始化 Router、Pinia、Axios、ESLint 与 Vue SFC 类型检查。

`/chat/:conversationId?` 提供本人会话列表/新建/改名/删除、授权 KB 选择、流式 Markdown、引用快照与授权下载、取消和刷新恢复。POST fetch 使用 satoken 与 JSON body；UTF-8/SSE 拆包、重复帧、断流与错误均有测试，不自动重发模型请求。Markdown 禁止原始 HTML，图片仅显示替代文字，引用只接受本次证据标记。文档详情增加上传新版本、重建索引和最新失败候选重试；旧版仍可检索直到新版完成。见 [Phase 9–11 说明](../docs/PHASE9_11_DECISIONS.md)。

```powershell
npm --prefix frontend ci
npm --prefix frontend run lint
npm --prefix frontend run typecheck
npm --prefix frontend test
npm --prefix frontend run build
npm --prefix frontend run dev
```

http://127.0.0.1:5173/ 跳转到 /profile，未登录时跳转 /login。登录后显示真实账号与部门，管理员可查看用户/部门分页目录；普通用户的管理路由显示 403。/system 继续公开展示真实基础设施、数据库版本、迁移数量与追踪 ID；未知页面显示 404 页。

Vite 将 /api 与 /actuator/health 代理到 http://127.0.0.1:8080；可通过 BACKEND_URL 覆盖目标。preview 仅用于预览静态构建，真实 API 联调使用 dev 代理。所有请求位于 src/api，Axios 附加 satoken Header。sessionStorage 只保存当前标签页 Token；刷新后通过 /auth/me 恢复身份。401 清理状态并跳转登录，登录错误密码不误清当前会话，旧请求的 401 不清理新 Token；503 保留 Token 供重试。登出失败会提示，后端成功撤销后才清理本地状态。

`/knowledge-bases` 提供授权内列表与创建，`/knowledge-bases/:id` 提供详情、编辑、ACL 与删除。系统管理员在 `/admin/knowledge-bases` 与详情页管理全部未删除 KB，包括停用状态恢复。界面使用后端返回的有效 permission 决定按钮显示；写操作仍由后端重新鉴权。授权支持用户/部门 ID 与系统角色，保存会替换全部显式授权。

后端和前端运行后，在根目录执行 `node --env-file=deploy/.env.local scripts/smoke-phase3.mjs --report` 验证知识库/ACL，再运行 `scripts/smoke-phase2.mjs` 验证登录回归。npm test 使用 Vite SSR 加载真实 store/interceptor/redirect/API 模块，替身仅在测试目录，测试 WebSocket 已关闭。浏览器验收图和记录位于 ../docs/phase3/。

Phase 5 文档详情显示任务阶段、执行/重试次数、下次自动重试时间及步骤耗时，WRITE 用户可重试当前失败任务。Phase 6 提供“解析正文预览”；Phase 7 增加“父子分块预览”：筛选 PARENT/CHILD、分页并核对 ID/父子关系/tokenCount/来源位置，读取中禁用分块控件，页码与返回正文同步。Phase 8 的“向量索引”显示固定模型/维度、Child/批次/模型调用数、tokens 和耗时，并区分向量已保存与最终 COMPLETED。HTML/链接/代码均以文本插值显示；撤销权限后隐藏详情。真实六格式和长文本联调运行根目录 `pwsh -File scripts/accept-phase8.ps1`，说明见 `../docs/PHASE8_DECISIONS.md`。历史失败任务需人工重试，解析或分块预览可用不代表完整索引完成。
