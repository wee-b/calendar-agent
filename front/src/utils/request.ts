// src/utils/request.ts
import axios from 'axios';
import type { InternalAxiosRequestConfig, AxiosResponse } from 'axios';
// 【重点】记得把 clearAuth 也引入进来
import { getToken, clearAuth } from './auth';
import { ElMessage } from 'element-plus';

// 1. 定义白名单 (不需要携带 token 的接口路径)
const whiteList = [
    '/user/login',
    '/user/register',
    '/test/testConnection',
    '/test/getToken'
];

// 2. 创建 axios 实例
const request = axios.create({
    baseURL: import.meta.env.VITE_API_BASE_URL || '',
    timeout: 10000, // 请求超时时间
    headers: {
        'Content-Type': 'application/json;charset=utf-8'
    }
});

const tokenName = import.meta.env.VITE_TOKEN_KEY || 'yvli-token';

// 3. 请求拦截器
request.interceptors.request.use(
    (config: InternalAxiosRequestConfig) => {
        const isWhiteListed = whiteList.some(path => config.url?.includes(path));

        if (!isWhiteListed) {
            const token = getToken();
            if (token) {
                config.headers[tokenName] = `${token}`;
            }
        }
        return config;
    },
    (error) => {
        return Promise.reject(error);
    }
);

// 4. 响应拦截器
request.interceptors.response.use(
    (response: AxiosResponse) => {
        const res = response.data;
        if (res.ok || res.code === 200) {
            return res.data;
        } else {
            const errorMsg = res.msg || '系统未知错误';
            ElMessage.error(errorMsg);
            return Promise.reject(new Error(errorMsg));
        }
    },
    (error) => {
        // 【补全部分】HTTP 网络状态码的错误处理
        let message = '';
        if (error && error.response) {
            switch (error.response.status) {
                case 401:
                    message = '登录状态已过期，请重新登录';
                    // 【核心】清除失效 Token，触发界面的 AuthModal 重新弹出
                    clearAuth();
                    break;
                case 403:
                    message = '拒绝访问 (403)';
                    break;
                case 404:
                    message = '请求的接口不存在 (404)';
                    break;
                case 500:
                    message = '后端服务器异常，请稍后再试 (500)';
                    break;
                default:
                    message = `网络连接错误 (${error.response.status})`;
            }
        } else {
            message = '网络连接异常，请检查后端服务是否启动';
        }

        ElMessage.error(message);
        return Promise.reject(error);
    }
);

export default request;