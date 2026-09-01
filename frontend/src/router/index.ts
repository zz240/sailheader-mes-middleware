import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/data-sources' },
    { path: '/data-sources', component: () => import('../views/DataSourcesView.vue') },
    { path: '/jobs', component: () => import('../views/JobsView.vue') },
    { path: '/history', component: () => import('../views/HistoryView.vue') },
    { path: '/templates', component: () => import('../views/TemplatesView.vue') },
    { path: '/sites', component: () => import('../views/SitesView.vue') },
  ],
})

export default router
