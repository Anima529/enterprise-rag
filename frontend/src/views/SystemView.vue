<script setup lang="ts">
import { onMounted } from 'vue'
import { useSystemStore } from '../stores/system'

const system = useSystemStore()

/** 进入系统页时刷新基础设施状态。 */
onMounted(() => {
  void system.refresh()
})
</script>
<template>
  <main class="shell status-page">
    <header class="page-header">
      <div>
        <p class="eyebrow">SYSTEM HEALTH</p>
        <h1>系统运行状态</h1>
        <p class="subtitle"> 检查数据库、缓存和文件存储，确认知识库服务的基础设施已就绪。 </p>
      </div>
      <button
        :disabled="system.loading"
        @click="system.refresh()"
      >
        {{ system.loading ? '检查中…' : '刷新状态' }}
      </button>
    </header>
    <section
      v-if="system.error"
      class="notice error"
      role="alert"
    >
      <h2>连接检查失败</h2>
      <p>{{ system.error }}</p>
    </section>
    <p
      v-else-if="system.loading"
      class="notice"
      role="status"
    >
      正在获取服务的实时状态…
    </p>
    <template v-else-if="system.status">
      <section
        class="notice"
        :class="system.healthy ? 'success' : 'error'"
        role="status"
      >
        <h2>{{ system.healthy ? '基础设施已就绪' : '部分依赖不可用' }}</h2>
        <p>检查时间：{{ new Date(system.status.checkedAt).toLocaleString('zh-CN') }}</p>
      </section>
      <section
        class="dependency-grid"
        aria-label="基础设施"
      >
        <article
          v-for="(value, name) in system.status.dependencies"
          :key="name"
          class="card"
        >
          <span
            class="badge"
            :class="value === 'UP' ? 'up' : 'down'"
            >{{ value }}</span
          >
          <h2>{{ { postgres: 'PostgreSQL', redis: 'Redis', minio: 'MinIO' }[name] }}</h2>
          <p>{{ { postgres: '知识与业务数据', redis: '会话与实时缓存', minio: '私有文档存储' }[name] }}</p>
        </article>
      </section>
      <section class="card details">
        <h2>数据库初始化</h2>
        <dl>
          <div
            ><dt>PostgreSQL</dt><dd>{{ system.status.database.databaseVersion }}</dd></div
          >
          <div
            ><dt>pgvector</dt><dd>{{ system.status.database.vectorVersion }}</dd></div
          >
          <div
            ><dt>已应用迁移</dt><dd>{{ system.status.database.migrations }}</dd></div
          >
        </dl>
      </section>
    </template>
    <footer>
      <p v-if="system.requestId">
        requestId：<code>{{ system.requestId }}</code>
      </p>
      <p v-if="system.traceId">
        traceId：<code>{{ system.traceId }}</code>
      </p>
      <p>
        <RouterLink to="/profile"> 查看当前账号 </RouterLink>
      </p>
    </footer>
  </main>
</template>
