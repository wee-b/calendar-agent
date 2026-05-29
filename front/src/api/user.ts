// src/api/user.ts
import request from '../utils/request';
import type { UserInfo } from '../utils/auth';

// 根据 OpenAPI 文档定义请求参数类型
export interface LoginData {
    phone?: string;
    password?: string;
}

export interface RegisterData {
    phone?: string;
    password?: string;
    userName?: string;
    gender?: number;
    birthday?: string;
}

// 接口返回的登录 VO 类型
export interface LoginResult {
    token: string;
    user: UserInfo;
}

// 1. 用户登录
export const loginAPI = (data: LoginData): Promise<LoginResult> => {
    return request.post('/user/login', data);
};

// 2. 用户注册
export const registerAPI = (data: RegisterData): Promise<void> => {
    return request.post('/user/register', data);
};

// 3. 退出登录
export const logoutAPI = (): Promise<void> => {
    return request.post('/user/logout');
};

// 4. 获取当前用户信息
export const getUserInfoAPI = (): Promise<UserInfo> => {
    return request.get('/user/info');
};

// 5. 修改用户信息
export const updateUserInfoAPI = (data: UserInfo): Promise<UserInfo> => {
    return request.put('/user/info', data);
};