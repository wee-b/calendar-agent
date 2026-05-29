// src/utils/auth.ts
import { ref } from 'vue';

// 定义接口返回的用户信息类型 (从你的 OpenAPI 文档推导)
export interface UserInfo {
    userId?: number;
    userCode?: string;
    userName?: string;
    phone?: string;
    avatar?: string;
    gender?: number; // 0-保密，1-男，2-女
    birthday?: string;
    createTime?: string;
}

const TOKEN_KEY = import.meta.env.VITE_TOKEN_KEY || 'yvli-token';
const USER_INFO_KEY = 'VOICE_CALENDAR_USER';

// 响应式状态，方便在 Vue 组件中直接监听变化
export const tokenRef = ref<string>(localStorage.getItem(TOKEN_KEY) || '');
export const userInfoRef = ref<UserInfo | null>(
    localStorage.getItem(USER_INFO_KEY) ? JSON.parse(localStorage.getItem(USER_INFO_KEY) as string) : null
);

// 获取 Token
export const getToken = () => localStorage.getItem(TOKEN_KEY);

// 设置 Token
export const setToken = (token: string) => {
    localStorage.setItem(TOKEN_KEY, token);
    tokenRef.value = token;
};

// 获取用户信息
export const getUserInfo = (): UserInfo | null => {
    const userStr = localStorage.getItem(USER_INFO_KEY);
    return userStr ? JSON.parse(userStr) : null;
};

// 设置用户信息
export const setUserInfo = (userInfo: UserInfo) => {
    localStorage.setItem(USER_INFO_KEY, JSON.stringify(userInfo));
    userInfoRef.value = userInfo;
};

// 判断当前是否已登录
export const isLoggedIn = () => !!tokenRef.value;

// 清除所有认证信息 (退出登录或 Token 失效时调用)
export const clearAuth = () => {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_INFO_KEY);
    tokenRef.value = '';
    userInfoRef.value = null;
};