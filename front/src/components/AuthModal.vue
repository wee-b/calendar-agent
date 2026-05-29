<template>
  <div class="modal-overlay" @click="$emit('close')">
    <div class="auth-card" @click.stop>
      <div class="header">
        <h2>{{ isLogin ? '登录语音日历' : '注册新账号' }}</h2>
        <p class="subtitle">{{ isLogin ? '欢迎回来，请登录您的账号' : '填写以下信息开启您的日程' }}</p>
      </div>

      <form @submit.prevent="handleSubmit">
        <div class="form-group">
          <label>手机号</label>
          <input type="tel" v-model="formData.phone" required placeholder="请输入手机号" />
        </div>

        <div class="form-group">
          <label>密码</label>
          <input type="password" v-model="formData.password" required placeholder="请输入密码" />
        </div>

        <template v-if="!isLogin">
          <div class="form-group">
            <label>用户名</label>
            <input type="text" v-model="formData.userName" required placeholder="起个好听的名字" />
          </div>

          <div class="form-group form-row">
            <div class="half-width">
              <label>性别</label>
              <div class="radio-group">
                <label><input type="radio" v-model="formData.gender" :value="1" /> 男</label>
                <label><input type="radio" v-model="formData.gender" :value="2" /> 女</label>
                <label><input type="radio" v-model="formData.gender" :value="0" /> 保密</label>
              </div>
            </div>
            <div class="half-width">
              <label>生日</label>
              <input type="date" v-model="formData.birthday" required />
            </div>
          </div>
        </template>

        <button type="submit" class="submit-btn" :disabled="loading">
          {{ loading ? '处理中...' : (isLogin ? '登 录' : '注 册') }}
        </button>
      </form>

      <div class="toggle-mode">
        <span @click="toggleMode">
          {{ isLogin ? '没有账号？点击注册 ➔' : '⬅ 返回登录' }}
        </span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue';
import { loginAPI, registerAPI } from '../api/user';
import { setToken, setUserInfo } from '../utils/auth';
import { ElMessage } from 'element-plus'; // 引入 ElMessage

// 定义向父组件发送的事件
const emit = defineEmits<{
  (e: 'success'): void;
  (e: 'close'): void;
}>();

const isLogin = ref(true);
const loading = ref(false);

// 按照 OpenAPI 文档定义的字段
const formData = reactive({
  phone: '',
  password: '',
  userName: '',
  gender: 0, // 0-保密，1-男，2-女
  birthday: ''
});

const toggleMode = () => {
  isLogin.value = !isLogin.value;
  // 切换时清空密码，保留手机号
  formData.password = '';
};


const handleSubmit = async () => {
  loading.value = true;
  try {
    if (isLogin.value) {
      // 登录
      const res = await loginAPI({ phone: formData.phone, password: formData.password });
      setToken(res.token);
      setUserInfo(res.user);

      // 登录成功加个友好的欢迎语
      ElMessage.success(`欢迎回来，${res.user.userName}`);
      emit('success');
    } else {
      // 注册
      await registerAPI({
        phone: formData.phone,
        password: formData.password,
        userName: formData.userName,
        gender: formData.gender,
        birthday: formData.birthday ? new Date(formData.birthday).toISOString() : undefined
      });

      // 替换 alert
      ElMessage.success('注册成功，请使用新账号登录！');
      isLogin.value = true;
    }
  } catch (error) {
    // 错误已经在 request.ts 里弹窗过了，这里无需再弹
  } finally {
    loading.value = false;
  }
};
</script>

<style scoped>
/* 全屏遮罩层，带模糊效果 */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  background-color: rgba(92, 75, 55, 0.6); /* 复古深褐色半透明 */
  backdrop-filter: blur(4px);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 9999; /* 确保在最顶层 */
}

/* 弹窗卡片：融入复古纸张主题 */
.auth-card {
  width: 100%;
  max-width: 420px;
  padding: 40px;
  background: #fdfae9; /* 纸张底色 */
  border: 2px solid #d3c4a1;
  border-radius: 12px;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.15);
  box-sizing: border-box;
}

.header {
  text-align: center;
  margin-bottom: 30px;
}

h2 {
  color: #5c4b37;
  margin: 0 0 10px 0;
  font-size: 1.6rem;
}

.subtitle {
  color: #b5a992;
  margin: 0;
  font-size: 14px;
}

.form-group { margin-bottom: 20px; }
.form-row { display: flex; gap: 16px; }
.half-width { flex: 1; }

label {
  display: block;
  margin-bottom: 8px;
  color: #5c4b37;
  font-size: 14px;
  font-weight: bold;
}

/* 输入框复古化 */
input[type="text"],
input[type="tel"],
input[type="password"],
input[type="date"] {
  width: 100%;
  padding: 12px;
  background-color: #fcf9ee;
  border: 1px solid #d3c4a1;
  border-radius: 6px;
  box-sizing: border-box;
  font-size: 14px;
  color: #5c4b37;
  transition: all 0.3s;
}

input:focus {
  border-color: #5c4b37;
  outline: none;
  box-shadow: 0 0 0 2px rgba(92, 75, 55, 0.1);
}

/* 单选框组 */
.radio-group {
  display: flex;
  gap: 12px;
  padding-top: 8px;
}
.radio-group label {
  font-weight: normal;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 4px;
}

.submit-btn {
  width: 100%;
  padding: 14px;
  background-color: #5c4b37;
  color: #fdfae9;
  border: none;
  border-radius: 6px;
  font-size: 16px;
  font-weight: bold;
  cursor: pointer;
  margin-top: 10px;
  transition: all 0.2s;
}

.submit-btn:hover:not(:disabled) { background-color: #4a3c2c; }
.submit-btn:disabled { opacity: 0.7; cursor: not-allowed; }

.toggle-mode {
  text-align: center;
  margin-top: 24px;
  font-size: 14px;
  color: #8c7a65;
  cursor: pointer;
  transition: color 0.2s;
}

.toggle-mode span:hover {
  color: #5c4b37;
  text-decoration: underline;
}
</style>