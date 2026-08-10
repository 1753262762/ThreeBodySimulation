/**
 * 正式路由：参数实验室、指定实验、报告页。
 * 已移除原型总览与四套备选方案的画廊路由。
 */
import { createRouter, createWebHistory } from 'vue-router'
import LabView from './views/LabView.vue'
import ExperimentView from './views/ExperimentView.vue'
import ReportView from './views/ReportView.vue'
import NotFoundView from './views/NotFoundView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'lab', component: LabView },
    { path: '/experiments/:id', name: 'experiment', component: ExperimentView },
    { path: '/reports/:id', name: 'report', component: ReportView },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFoundView },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

export default router