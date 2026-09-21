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

/*
 * 窄屏（#137）：控制台壳折成上下——导航条到顶上、内容吃满整宽。
 *
 * 侧栏是 200px 固定的，360px 的窗口上只剩 160px 给内容（再扣主区那 40px 留白与 body 那 16px
 * 只剩 104px），页面必然横滚——消息历史页那套双栏在 360px 上实测有 234 个元素探出视口。
 * 原型本来就是这个形状（顶部 appbar + 下面一整块），这条把壳对齐过去。
 * 760px 是「手机 / 竖向平板」那一档：再宽一点侧栏与双栏还排得开（页面自己那条断点在
 * `styles/history-console.css` 的 1000px 上——两栏 / 两块快照 / 对照块三处一起翻）。
 */
@media (max-width: 760px) {
  .layout { flex-direction: column; height: auto; min-height: 100vh; }
  /*
   * `el-aside` 的宽度是**行内**写的（组件把 `width` prop 变成 `--el-aside-width: 200px` 挂在
   * style 上），样式表里同一条自定义属性只有在带 `!important` 时才压得过行内那一条。
   * 少了这个 `!important`，折成上下之后导航条仍是 200px 宽的一条、右边整片空着——而且页面级
   * 也不横滚（列向布局里 main 照样吃满宽），**测试抓不到**（#137 两轴复核抓到的一处假绿）。
   */
  .aside {
    --el-aside-width: 100% !important;
    border-right: 0; border-bottom: 1px solid var(--border);
  }
  /* 竖向菜单改成顶上一条：菜单项平分整宽、自己换行 */
  .aside .el-menu { display: flex; flex-wrap: wrap; border-right: 0; }
  .aside .el-menu-item { flex: 1 1 auto; justify-content: center; padding: 0 10px; }
  .logo { padding: 10px 12px; }
  /* 主区留白跟着原型收到 12px：360px 上每一像素都算数（分页条那一行最吃宽） */
  .el-main { padding: 12px; }
}
</style>
