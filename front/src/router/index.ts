// src/router/index.ts
import { createRouter, createWebHistory } from 'vue-router'
import LayoutView from '../views/LayoutView/LayoutView.vue'

const router = createRouter({
    history: createWebHistory(),
    routes: [
        {
            path: '/',
            component: LayoutView,
            children: [
                {
                    path: '',
                    redirect: '/conversation'
                },
                {
                    path: 'calendar-view',
                    name: 'Calendar',
                    component: () => import('../views/HomeView/HomeView.vue')
                },
                {
                    path: 'today',
                    name: 'Today',
                    component: () => import('../views/TodayView/TodayView.vue')
                },
                {
                    path: 'conversation',
                    name: 'Chat',
                    component: () => import('../views/ChatView/ChatView.vue')
                }
            ]
        }
    ]
})

// 路由守卫：未匹配的路径重定向到首页
router.beforeEach((to, _from, next) => {
    if (to.matched.length === 0) {
        next('/')
    } else {
        next()
    }
})

export default router
