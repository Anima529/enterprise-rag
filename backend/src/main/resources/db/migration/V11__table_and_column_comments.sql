-- 数据字典：14 张业务表和 1 张 Flyway 迁移历史表。
-- 只补充表、字段的说明；已执行的 V1–V10 保持原样，避免改变迁移校验和。
-- 业务时间字段为 TIMESTAMPTZ：表示绝对时间，查询时按连接的时区显示。

-- 1. 部门与用户：身份、归属和角色。
COMMENT ON TABLE sys_department IS '部门：用户和知识库的归属单位；部门停用或软删除后，其用户不能继续访问业务资源';
COMMENT ON COLUMN sys_department.id IS '部门自增主键；限制在 JavaScript 安全整数范围内';
COMMENT ON COLUMN sys_department.code IS '部门唯一编码；大写字母开头，仅允许大写字母、数字和下划线';
COMMENT ON COLUMN sys_department.name IS '部门名称';
COMMENT ON COLUMN sys_department.status IS '部门状态：ACTIVE 启用，DISABLED 停用';
COMMENT ON COLUMN sys_department.created_at IS '记录创建时间；TIMESTAMPTZ，按查询连接的时区显示';
COMMENT ON COLUMN sys_department.updated_at IS '记录最后更新时间；由数据库更新触发器维护';
COMMENT ON COLUMN sys_department.deleted IS '部门软删除标记；true 表示业务不可用，记录仍保留';

COMMENT ON TABLE sys_user IS '用户：登录账号、BCrypt 密码哈希、部门和系统角色；知识库权限另由所有权和 ACL 计算';
COMMENT ON COLUMN sys_user.id IS '用户自增主键；限制在 JavaScript 安全整数范围内';
COMMENT ON COLUMN sys_user.username IS '唯一登录账号；小写字母开头，允许小写字母、数字、下划线、点和短横线';
COMMENT ON COLUMN sys_user.password_hash IS 'BCrypt 密码哈希；不保存明文密码';
COMMENT ON COLUMN sys_user.department_id IS '所属部门 ID，关联 sys_department.id';
COMMENT ON COLUMN sys_user.role IS '系统角色：USER 普通用户，SYSTEM_ADMIN 系统管理员；不是知识库 ACL 权限';
COMMENT ON COLUMN sys_user.status IS '用户状态：ACTIVE 启用，DISABLED 停用';
COMMENT ON COLUMN sys_user.created_at IS '记录创建时间；TIMESTAMPTZ，按查询连接的时区显示';
COMMENT ON COLUMN sys_user.updated_at IS '记录最后更新时间；由数据库更新触发器维护';
COMMENT ON COLUMN sys_user.deleted IS '用户软删除标记；true 表示不能继续使用业务资源';

-- 2. 知识库及其显式授权。
COMMENT ON TABLE knowledge_base IS '知识库：组织文档的容器，保存归属部门、所有者、启停状态和模型配置';
COMMENT ON COLUMN knowledge_base.id IS '知识库自增主键；限制在 JavaScript 安全整数范围内';
COMMENT ON COLUMN knowledge_base.name IS '知识库名称';
COMMENT ON COLUMN knowledge_base.description IS '知识库说明；默认空字符串';
COMMENT ON COLUMN knowledge_base.department_id IS '知识库归属部门 ID，关联 sys_department.id；同部门不代表自动获得访问权限';
COMMENT ON COLUMN knowledge_base.owner_user_id IS '知识库所有者 ID，关联 sys_user.id；所有者具有隐式 ADMIN 权限';
COMMENT ON COLUMN knowledge_base.visibility IS '可见性；当前只支持 PRIVATE，读取仍需通过权限校验';
COMMENT ON COLUMN knowledge_base.status IS '知识库状态：ENABLED 启用，DISABLED 停用；停用后不参与普通访问和检索';
COMMENT ON COLUMN knowledge_base.embedding_model IS '向量模型名称；当前应用固定使用 text-embedding-v3';
COMMENT ON COLUMN knowledge_base.embedding_dimensions IS '向量维度；当前固定为 1024，须与分块向量一致';
COMMENT ON COLUMN knowledge_base.generation_model IS '回答生成模型配置；默认 qwen-plus，后续问答链路使用';
COMMENT ON COLUMN knowledge_base.created_at IS '记录创建时间；TIMESTAMPTZ，按查询连接的时区显示';
COMMENT ON COLUMN knowledge_base.updated_at IS '记录最后更新时间；由数据库更新触发器维护';
COMMENT ON COLUMN knowledge_base.deleted IS '知识库软删除标记；true 表示所有业务入口不可访问';

COMMENT ON TABLE knowledge_base_acl IS '知识库显式授权：按用户、部门或系统角色授予 READ、WRITE、ADMIN；所有者和系统管理员的隐式权限不在此表登记';
COMMENT ON COLUMN knowledge_base_acl.id IS '授权记录自增主键；限制在 JavaScript 安全整数范围内';
COMMENT ON COLUMN knowledge_base_acl.knowledge_base_id IS '被授权的知识库 ID，关联 knowledge_base.id';
COMMENT ON COLUMN knowledge_base_acl.subject_type IS '授权主体类型：USER 用户，DEPARTMENT 部门，ROLE 系统角色';
COMMENT ON COLUMN knowledge_base_acl.subject_id IS '主体标识：用户或部门 ID 的字符串，或 USER、SYSTEM_ADMIN 角色名；含义由 subject_type 决定';
COMMENT ON COLUMN knowledge_base_acl.permission IS '知识库权限：READ 读取，WRITE 读取及写入，ADMIN 读取、写入及管理；高权限包含低权限';
COMMENT ON COLUMN knowledge_base_acl.created_at IS '记录创建时间；TIMESTAMPTZ，按查询连接的时区显示';
COMMENT ON COLUMN knowledge_base_acl.updated_at IS '记录最后更新时间；由数据库更新触发器维护';

-- 3. 文档主记录、版本快照和上传补偿清理。
COMMENT ON TABLE document IS '文档主记录：保存知识库归属、当前版本指针、原文件元信息和处理状态；删除接口只设置本表的 deleted，不物理删除分块或原文件';
COMMENT ON COLUMN document.id IS '文档自增主键；限制在 JavaScript 安全整数范围内';
COMMENT ON COLUMN document.knowledge_base_id IS '所属知识库 ID，关联 knowledge_base.id';
COMMENT ON COLUMN document.current_version IS '当前生效版本号，从 1 开始；与本表 id 一起关联 document_version(document_id, version_no)';
COMMENT ON COLUMN document.name IS '上传文件的原始文件名；原文件在 MinIO 中使用独立生成的对象路径';
COMMENT ON COLUMN document.source_format IS '原文件格式：PDF、DOC、DOCX、XLSX、TXT、MD';
COMMENT ON COLUMN document.mime_type IS '校验后保存的文件 MIME 类型';
COMMENT ON COLUMN document.size_bytes IS '原文件大小，单位字节';
COMMENT ON COLUMN document.sha256 IS '原文件字节的 SHA-256，64 位小写十六进制；同一知识库内未删除文档按此去重';
COMMENT ON COLUMN document.object_key IS 'MinIO 私有桶中的原文件对象路径；不是本机路径，不直接通过文档元数据接口暴露';
COMMENT ON COLUMN document.status IS '当前文档处理状态：UPLOADED 已上传、PARSING 解析、CHUNKING 分块、EMBEDDING 向量化、INDEXING 整版校验、COMPLETED 完成、FAILED 失败';
COMMENT ON COLUMN document.created_by IS '上传者用户 ID，关联 sys_user.id';
COMMENT ON COLUMN document.created_at IS '记录创建时间；TIMESTAMPTZ，按查询连接的时区显示';
COMMENT ON COLUMN document.updated_at IS '记录最后更新时间；状态变化和软删除也会触发更新';
COMMENT ON COLUMN document.deleted IS '文档软删除标记；true 后停止业务访问和检索，关联版本、正文、分块、向量与原文件仍保留';

COMMENT ON TABLE document_version IS '文档版本快照：每个版本的格式、文件大小、内容哈希、对象路径及处理状态；当前普通上传建立版本 1，不自动覆盖已有文档';
COMMENT ON COLUMN document_version.id IS '版本记录自增主键；业务引用版本时使用 document_id 与 version_no';
COMMENT ON COLUMN document_version.document_id IS '所属文档 ID，关联 document.id';
COMMENT ON COLUMN document_version.version_no IS '该文档内的版本号，从 1 开始；与 document_id 联合唯一';
COMMENT ON COLUMN document_version.source_format IS '该版本原文件格式：PDF、DOC、DOCX、XLSX、TXT、MD';
COMMENT ON COLUMN document_version.mime_type IS '该版本文件的 MIME 类型';
COMMENT ON COLUMN document_version.size_bytes IS '该版本原文件大小，单位字节';
COMMENT ON COLUMN document_version.sha256 IS '该版本原文件字节的 SHA-256，64 位小写十六进制';
COMMENT ON COLUMN document_version.object_key IS '该版本原文件在 MinIO 私有桶中的对象路径；授权下载和孤儿对象清理据此查找引用';
COMMENT ON COLUMN document_version.status IS '该版本处理状态：UPLOADED、PARSING、CHUNKING、EMBEDDING、INDEXING、COMPLETED、FAILED';
COMMENT ON COLUMN document_version.created_at IS '记录创建时间；TIMESTAMPTZ，按查询连接的时区显示';
COMMENT ON COLUMN document_version.updated_at IS '版本记录最后更新时间；由数据库更新触发器维护';

COMMENT ON TABLE document_storage_cleanup IS '上传意图及孤儿对象补偿队列：上传前登记，业务提交成功后移除；清理器仅删除未被版本引用的对象，不负责清空软删除文档';
COMMENT ON COLUMN document_storage_cleanup.object_key IS '待确认或待清理的 MinIO 对象路径，也是本表主键';
COMMENT ON COLUMN document_storage_cleanup.next_attempt_at IS '下次允许清理的时间；领取时更新为清理租约截止时间，避免并发清理正在提交的上传';
COMMENT ON COLUMN document_storage_cleanup.created_at IS '上传意图或补偿记录创建时间；TIMESTAMPTZ';
COMMENT ON COLUMN document_storage_cleanup.updated_at IS '清理记录最后更新时间；由数据库更新触发器维护';

-- 4. 异步索引任务及每轮执行的阶段明细。
COMMENT ON TABLE document_index_task IS '文档版本的异步索引任务：保存调度状态、处理阶段、重试次数和执行租约；同一文档版本及任务类型联合唯一';
COMMENT ON COLUMN document_index_task.id IS '索引任务自增主键；限制在 JavaScript 安全整数范围内';
COMMENT ON COLUMN document_index_task.document_id IS '待处理文档 ID；与 document_version 一起关联 document_version 表';
COMMENT ON COLUMN document_index_task.document_version IS '待处理版本号；必须与文档当前版本和任务有效性校验一致';
COMMENT ON COLUMN document_index_task.task_type IS '任务类型：INDEX 建立索引，REINDEX 重建索引';
COMMENT ON COLUMN document_index_task.status IS '任务调度状态：PENDING 待领取、RUNNING 执行中、COMPLETED 完成、FAILED 失败；与文档处理阶段不同';
COMMENT ON COLUMN document_index_task.retry_count IS '任务累计重试计数；自动重试和人工重试均会增加';
COMMENT ON COLUMN document_index_task.error_code IS '最近一次失败的业务错误码；开始新一轮执行时清空';
COMMENT ON COLUMN document_index_task.error_message IS '最近一次失败的可展示说明；开始新一轮执行时清空';
COMMENT ON COLUMN document_index_task.started_at IS '最近一轮执行开始时间；任务尚未执行或人工重置后可为空';
COMMENT ON COLUMN document_index_task.finished_at IS '最近一轮执行结束时间；执行中可为空';
COMMENT ON COLUMN document_index_task.created_at IS '任务记录创建时间；TIMESTAMPTZ';
COMMENT ON COLUMN document_index_task.updated_at IS '任务记录最后更新时间；由数据库更新触发器维护';
COMMENT ON COLUMN document_index_task.stage IS '处理阶段：UPLOADED、PARSING、CHUNKING、EMBEDDING、INDEXING、COMPLETED、FAILED';
COMMENT ON COLUMN document_index_task.attempt_count IS '实际领取执行的累计轮数；每次领取加 1，也是 document_index_step.attempt 的来源';
COMMENT ON COLUMN document_index_task.auto_retry_count IS '当前人工重试周期内的自动重试计数，用于限制自动重试；人工重试时归零';
COMMENT ON COLUMN document_index_task.next_attempt_at IS '最早可领取执行或重试的时间；PENDING 任务到期后才可调度';
COMMENT ON COLUMN document_index_task.lease_owner IS '本轮执行租约的随机标识；防止旧执行者覆盖新执行结果，仅 RUNNING 状态非空';
COMMENT ON COLUMN document_index_task.lease_expires_at IS '本轮执行租约的截止时间；过期后允许恢复，仅 RUNNING 状态非空';

COMMENT ON TABLE document_index_step IS '索引任务阶段明细：记录每轮执行的解析、分块、向量化、整版校验耗时和错误；重试保留旧轮明细';
COMMENT ON COLUMN document_index_step.id IS '阶段明细自增主键；限制在 JavaScript 安全整数范围内';
COMMENT ON COLUMN document_index_step.task_id IS '所属任务 ID，关联 document_index_task.id；物理删除任务时级联删除明细';
COMMENT ON COLUMN document_index_step.attempt IS '执行轮次，从 1 开始；同一 task_id、attempt、stage 只保存一条明细';
COMMENT ON COLUMN document_index_step.stage IS '本条明细所属阶段：PARSING 解析、CHUNKING 分块、EMBEDDING 向量化、INDEXING 整版校验';
COMMENT ON COLUMN document_index_step.status IS '阶段执行状态：RUNNING 执行中、COMPLETED 完成、FAILED 失败';
COMMENT ON COLUMN document_index_step.started_at IS '该阶段开始时间；TIMESTAMPTZ';
COMMENT ON COLUMN document_index_step.finished_at IS '该阶段结束时间；未结束时为空';
COMMENT ON COLUMN document_index_step.error_code IS '该阶段失败的业务错误码；成功或执行中通常为空';
COMMENT ON COLUMN document_index_step.error_message IS '该阶段失败的可展示说明';
COMMENT ON COLUMN document_index_step.created_at IS '阶段明细记录创建时间；TIMESTAMPTZ';
COMMENT ON COLUMN document_index_step.updated_at IS '阶段明细最后更新时间；由数据库更新触发器维护';

-- 5. 解析结果：汇总与带来源信息的正文块。
COMMENT ON TABLE document_parse_result IS '文档版本的解析汇总：记录解析器版本、正文块数、字符数和警告；正文保存在 document_parsed_block';
COMMENT ON COLUMN document_parse_result.document_id IS '所属文档 ID；与 document_version 构成复合主键并关联文档版本';
COMMENT ON COLUMN document_parse_result.document_version IS '所属文档版本号；同一版本保存一份当前解析汇总';
COMMENT ON COLUMN document_parse_result.source_format IS '解析源格式：PDF、DOC、DOCX、XLSX、TXT、MD';
COMMENT ON COLUMN document_parse_result.parser_version IS '实际使用的解析器实现版本，用于识别解析规则';
COMMENT ON COLUMN document_parse_result.block_count IS '本版解析得到的正文块总数';
COMMENT ON COLUMN document_parse_result.character_count IS '本版解析正文的累计字符数；不是 token 数';
COMMENT ON COLUMN document_parse_result.warnings IS '解析警告的 JSON 数组；无警告时为空数组';
COMMENT ON COLUMN document_parse_result.created_at IS '解析汇总记录创建时间；TIMESTAMPTZ';
COMMENT ON COLUMN document_parse_result.updated_at IS '最近一次解析结果持久化时间；分块与预览据此校验结果的新旧关系';

COMMENT ON TABLE document_parsed_block IS '解析正文块：按版本和顺序保存从原文件提取的文本及页码、章节、行号或表格来源；尚未按检索 token 窗口切分';
COMMENT ON COLUMN document_parsed_block.document_id IS '所属文档 ID；与 document_version 关联 document_parse_result';
COMMENT ON COLUMN document_parsed_block.document_version IS '所属文档版本号';
COMMENT ON COLUMN document_parsed_block.block_index IS '该版本内的正文块顺序号，从 0 开始；与文档 ID、版本号构成复合主键';
COMMENT ON COLUMN document_parsed_block.text IS '解析出的正文文本；按纯文本展示，不执行其中的 HTML 或代码';
COMMENT ON COLUMN document_parsed_block.page IS '原文件页码，从 1 开始；无页码的格式可为空';
COMMENT ON COLUMN document_parsed_block.chapter IS '原文件章节或上级标题；无法识别时可为空';
COMMENT ON COLUMN document_parsed_block.section IS '原文件小节或下级标题；无法识别时可为空';
COMMENT ON COLUMN document_parsed_block.metadata IS '来源及结构元数据 JSON 对象，例如原文件行号、标题路径、工作表、表头与表格行组';
COMMENT ON COLUMN document_parsed_block.created_at IS '正文块记录创建时间；TIMESTAMPTZ';
COMMENT ON COLUMN document_parsed_block.updated_at IS '正文块最后更新时间；由数据库更新触发器维护';

-- 6. 父子分块：汇总、正文、父子关系与 Child 向量。
COMMENT ON TABLE document_chunk_result IS '文档版本的分块汇总：记录策略、tokenizer、窗口配置及父子块数量；具体文本和向量保存在 document_chunk';
COMMENT ON COLUMN document_chunk_result.document_id IS '所属文档 ID；与 document_version 构成复合主键并关联文档版本';
COMMENT ON COLUMN document_chunk_result.document_version IS '所属文档版本号；同一版本保存一份当前分块汇总';
COMMENT ON COLUMN document_chunk_result.strategy_version IS '分块策略实现版本，用于识别边界与父子分块规则';
COMMENT ON COLUMN document_chunk_result.tokenizer IS '计算 token 数使用的编码器名称；当前使用 cl100k_base';
COMMENT ON COLUMN document_chunk_result.child_tokens IS '子块 token 窗口上限配置';
COMMENT ON COLUMN document_chunk_result.parent_tokens IS '父块 token 窗口上限配置；不小于子块窗口';
COMMENT ON COLUMN document_chunk_result.overlap_tokens IS '相邻子块重叠 token 数配置；必须小于 child_tokens';
COMMENT ON COLUMN document_chunk_result.parent_count IS '本版有效父块总数';
COMMENT ON COLUMN document_chunk_result.child_count IS '本版有效子块总数；向量化须覆盖全部子块';
COMMENT ON COLUMN document_chunk_result.created_at IS '分块汇总记录创建时间；TIMESTAMPTZ';
COMMENT ON COLUMN document_chunk_result.updated_at IS '最近一次分块结果持久化时间；向量汇总的 chunked_at 必须与之相同';
COMMENT ON COLUMN document_chunk_result.boundary_lookback_characters IS 'token 截断点之前回退寻找标点的字符窗口，范围 0–1024；NULL 表示增加此配置前的历史结果';

COMMENT ON TABLE document_chunk IS '父子分块及子块向量：PARENT 提供较完整的上下文，CHILD 用于向量召回；文档软删除时本表记录和向量保留，读取须同时检查 document.deleted';
COMMENT ON COLUMN document_chunk.id IS '分块自增主键；同版重试按块类型和顺序复用稳定 ID';
COMMENT ON COLUMN document_chunk.knowledge_base_id IS '所属知识库 ID；与 department_id 联合校验知识库归属，也是检索过滤字段';
COMMENT ON COLUMN document_chunk.department_id IS '所属知识库的归属部门 ID；与 knowledge_base_id 成对校验，避免跨部门拼接范围';
COMMENT ON COLUMN document_chunk.document_id IS '所属文档 ID；与 knowledge_base_id 联合校验文档归属';
COMMENT ON COLUMN document_chunk.document_version IS '所属文档版本号；与 document_id 关联 document_chunk_result';
COMMENT ON COLUMN document_chunk.parent_chunk_id IS '子块对应的父块 ID；父块自身为空，复合外键保证父子属于同一文档版本和权限范围';
COMMENT ON COLUMN document_chunk.parent_type IS '复合外键辅助字段；子块固定为 PARENT，父块为空，保证引用目标确为父块';
COMMENT ON COLUMN document_chunk.chunk_type IS '分块类型：PARENT 父块，CHILD 子块；只有 CHILD 可以保存向量';
COMMENT ON COLUMN document_chunk.chunk_index IS '块顺序号；同一文档版本和块类型内唯一，用于有序预览及重试复用 ID';
COMMENT ON COLUMN document_chunk.content IS '分块正文文本；向量必须对应此正文，按纯文本展示';
COMMENT ON COLUMN document_chunk.token_count IS '该分块正文的 token 数，使用分块汇总中指定的 tokenizer 计算';
COMMENT ON COLUMN document_chunk.page_number IS '该分块来源页码，从 1 开始；无页码时可为空';
COMMENT ON COLUMN document_chunk.chapter IS '该分块来源章节或上级标题';
COMMENT ON COLUMN document_chunk.section IS '该分块来源小节或下级标题';
COMMENT ON COLUMN document_chunk.metadata IS '分块结构和来源的 JSON 对象；用于预览、父块定位和后续引用';
COMMENT ON COLUMN document_chunk.created_at IS '分块记录首次创建时间；TIMESTAMPTZ';
COMMENT ON COLUMN document_chunk.updated_at IS '分块记录最后更新时间；正文、向量或分块删除标记变化均会更新，不仅代表分块生成时间';
COMMENT ON COLUMN document_chunk.deleted IS '分块自身的软删除标记，重分块时隐藏旧块；文档删除不直接修改它，业务查询还须过滤 document.deleted';
COMMENT ON COLUMN document_chunk.embedding IS 'Child 正文的 pgvector 向量，固定 1024 维；Parent 和尚未向量化的 Child 为空';
COMMENT ON COLUMN document_chunk.embedding_model IS '生成该向量的模型；有向量时固定为 text-embedding-v3';
COMMENT ON COLUMN document_chunk.embedding_dimensions IS '该向量维度；有向量时固定为 1024';
COMMENT ON COLUMN document_chunk.embedding_content_hash IS '生成向量时正文 UTF-8 字节的 SHA-256；用于确认向量对应当前正文，不是原文件哈希';

-- 7. 整版向量化汇总；向量本身不在此表。
COMMENT ON TABLE document_embedding_result IS '文档版本的向量化汇总：保存模型、维度、源分块快照时间、数量和调用统计；全部 Child 向量与汇总在同一事务提交';
COMMENT ON COLUMN document_embedding_result.document_id IS '所属文档 ID；与 document_version 构成复合主键并关联分块汇总';
COMMENT ON COLUMN document_embedding_result.document_version IS '所属文档版本号；同一版本保存一份当前向量化汇总';
COMMENT ON COLUMN document_embedding_result.model IS '向量模型，固定为 text-embedding-v3';
COMMENT ON COLUMN document_embedding_result.dimensions IS '向量维度，固定为 1024';
COMMENT ON COLUMN document_embedding_result.chunked_at IS '本次向量化所依据的分块快照时间；等于 document_chunk_result.updated_at，用于防止读取过期向量';
COMMENT ON COLUMN document_embedding_result.child_count IS '本次完成向量化的子块数量；应覆盖本版全部有效 Child';
COMMENT ON COLUMN document_embedding_result.batch_count IS '按配置切分的逻辑向量化批次数';
COMMENT ON COLUMN document_embedding_result.call_count IS '模型实际调用次数，包含重试；因此不小于 batch_count';
COMMENT ON COLUMN document_embedding_result.total_tokens IS '向量化响应返回并累计的输入 token 用量';
COMMENT ON COLUMN document_embedding_result.duration_ms IS '本次向量化处理耗时，单位毫秒';
COMMENT ON COLUMN document_embedding_result.created_at IS '向量化汇总记录首次创建时间；TIMESTAMPTZ';
COMMENT ON COLUMN document_embedding_result.updated_at IS '最近一次向量化汇总持久化时间；接口将此字段作为 embeddedAt 返回';

-- 8. Flyway 自动维护的迁移历史，不是业务数据。
COMMENT ON TABLE flyway_schema_history IS 'Flyway 自动维护的数据库迁移历史：记录执行版本、脚本、校验和及成功状态；业务代码不直接写入或删除记录';
COMMENT ON COLUMN flyway_schema_history.installed_rank IS '迁移安装顺序，也是历史表主键；不等同于迁移版本号';
COMMENT ON COLUMN flyway_schema_history.version IS '版本化迁移的版本号；可重复迁移等记录可为空';
COMMENT ON COLUMN flyway_schema_history.description IS '迁移说明，通常来自迁移脚本文件名';
COMMENT ON COLUMN flyway_schema_history.type IS '迁移类型，例如 SQL、BASELINE 或 SCHEMA';
COMMENT ON COLUMN flyway_schema_history.script IS '迁移脚本名称或来源标识';
COMMENT ON COLUMN flyway_schema_history.checksum IS 'Flyway 保存的脚本校验和；用于检测已执行迁移是否被修改';
COMMENT ON COLUMN flyway_schema_history.installed_by IS '执行迁移的数据库用户名';
COMMENT ON COLUMN flyway_schema_history.installed_on IS '迁移安装时间；此表由 Flyway 定义，该字段为不带时区的 timestamp';
COMMENT ON COLUMN flyway_schema_history.execution_time IS '迁移执行耗时，单位毫秒';
COMMENT ON COLUMN flyway_schema_history.success IS '迁移是否执行成功；由 Flyway 维护';
