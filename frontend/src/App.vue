<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="logo">边缘数据网关</div>
      <el-menu :default-active="$route.path" router>
        <el-menu-item index="/data-sources">数据源</el-menu-item>
        <el-menu-item index="/jobs">任务与映射</el-menu-item>
        <el-menu-item index="/history">消息历史</el-menu-item>
        <el-menu-item index="/templates">模板</el-menu-item>
        <el-menu-item index="/sites">站点与 API Key</el-menu-item>
      </el-menu>
    </el-aside>
    <el-main>
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.layout { height: 100vh; }
.aside { border-right: 1px solid var(--border); }
.logo { font-size: var(--fs-body); font-weight: 600; padding: 16px 12px; }
</style>
