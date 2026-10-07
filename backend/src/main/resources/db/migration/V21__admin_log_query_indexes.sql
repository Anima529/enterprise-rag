-- 管理员日志按时间稳定分页，并支持用户、操作、状态和请求标识筛选。
CREATE INDEX audit_log_user_time_idx ON audit_log(user_id, created_at DESC, id DESC);
CREATE INDEX audit_log_action_time_idx ON audit_log(action, created_at DESC, id DESC);
CREATE INDEX audit_log_request_idx ON audit_log(request_id);
CREATE INDEX rag_trace_time_request_idx ON rag_request_trace(created_at DESC, request_id DESC);
CREATE INDEX rag_trace_status_time_idx ON rag_request_trace(status, created_at DESC, request_id DESC);
