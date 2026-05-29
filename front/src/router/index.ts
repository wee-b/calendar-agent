// src/router/index.ts
import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView/HomeView.vue'

const router = createRouter({
    history: createWebHistory(),
    routes: [
        // {
        //     path: '/login',
        //     name: 'Login',
        //     component: LoginView
        // },
        {
            path: '/',
            name: 'Home',
            component: HomeView
        }
    ]
})

// 简单的路由守卫拦截 (如果没匹配到路由，默认去首页)
router.beforeEach((to, from, next) => {
    if (to.path === '/login' || to.path === '/') {
        next()
    } else {
        next('/')
    }
})

export default router