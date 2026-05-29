<template>
  <div class="auth-container">
    <div class="auth-card">
      <h2>{{ isLogin ? '登录语音日历' : '注册新账号' }}</h2>

      <form @submit.prevent="handleSubmit">
        <div class="form-group" v-if="!isLogin">
          <label>用户名</label>
          <input type="text" v-model="formData.username" placeholder="请输入用户名" />
        </div>

        <div class="form-group">
          <label>邮箱 / 账号</label>
          <input type="email" v-model="formData.email" required placeholder="请输入邮箱" />
        </div>

        <div class="form-group">
          <label>密码</label>
          <input type="password" v-model="formData.password" required placeholder="请输入密码" />
        </div>

        <button type="submit" class="submit-btn">
          {{ isLogin ? '登 录' : '注 册' }}
        </button>
      </form>

      <div class="toggle-mode">
        <span @click="isLogin = !isLogin">
          {{ isLogin ? '没有账号？点击注册' : '已有账号？点击登录' }}
        </span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue';
import { useRouter } from 'vue-router';

const router = useRouter();
const isLogin = ref(true);
const formData = reactive({ username: '', email: '', password: '' });

const handleSubmit = () => {
  // 这里暂时模拟登录成功，直接跳转到主页 ('/')
  console.log('提交的数据:', formData);
  router.push('/');
};
</script>

<style scoped>
.auth-container { display: flex; justify-content: center; align-items: center; min-height: 100vh; background-color: #f5f7fa; }
.auth-card { width: 100%; max-width: 400px; padding: 40px; background: white; border-radius: 12px; box-shadow: 0 8px 24px rgba(0, 0, 0, 0.05); }
h2 { text-align: center; color: #333; margin-bottom: 30px; }
.form-group { margin-bottom: 20px; }
label { display: block; margin-bottom: 8px; color: #666; font-size: 14px; }
input { width: 100%; padding: 12px; border: 1px solid #ddd; border-radius: 6px; font-size: 14px; }
input:focus { border-color: #4CAF50; outline: none; }
.submit-btn { width: 100%; padding: 12px; background-color: #4CAF50; color: white; border: none; border-radius: 6px; font-size: 16px; cursor: pointer; margin-top: 10px; }
.toggle-mode { text-align: center; margin-top: 20px; font-size: 14px; color: #4CAF50; cursor: pointer; }
</style>