<template>
  <aside class="sidebar left-sidebar" :class="{ 'is-collapsed': !isOpen }">
    <div class="sidebar-content">
      <div class="todo-header">
        <h2>待办清单</h2>
        <button class="create-btn" @click="openCreateModal">＋ 创建待办</button>
      </div>

      <ul class="todo-items" v-if="todos.length > 0">
        <li
          v-for="todo in todos"
          :key="todo.todoId"
          class="todo-row"
          :class="{ 'is-selected': todo.todoId === props.selectedTodoId }"
          :style="todo.todoId === props.selectedTodoId ? { background: todo.color + '22', borderRadius: '6px' } : {}"
          @click="emit('select', todo)"
        >
          <span class="color-dot" :style="{ background: todo.color }"></span>
          <span class="todo-title">{{ todo.title }}</span>
          <span
            class="todo-count"
            :title="`还有${todo.dates.length}天的任务`"
            v-if="todo.status === 0"
          >{{ todo.dates.length }}</span>
          <span class="todo-count todo-count-done" title="已完成所有任务" v-else>✓</span>
          <div class="actions-wrapper">
            <button class="more-btn" @click.stop="toggleMenu(todo.todoId)">···</button>
            <div class="action-menu" v-if="openMenuId === todo.todoId">
              <div class="menu-item" @click.stop="openEditModal(todo)">修改</div>
              <div class="menu-item menu-item-danger" @click.stop="handleDelete(todo.todoId)">删除</div>
            </div>
          </div>
        </li>
      </ul>

      <p class="empty-tip" v-else>暂无待办，点击上方按钮创建</p>
    </div>

    <!-- 创建/编辑弹窗 -->
    <div class="modal-overlay" v-if="showModal" @click="closeModal">
      <div class="modal-card" @click.stop>
        <h3>{{ editingTodo ? '修改待办' : '创建待办' }}</h3>

        <div class="form-group">
          <label>目标名称 <span class="required">*</span></label>
          <input type="text" v-model="form.title" placeholder="例如：学英语" />
        </div>

        <div class="form-group">
          <label>高亮颜色</label>
          <div class="color-row">
            <input type="color" v-model="form.color" class="color-picker" />
            <input type="text" v-model="form.color" placeholder="#5c4b37" class="color-text" />
          </div>
        </div>

        <div class="form-group">
          <label>每日任务描述</label>
          <input type="text" v-model="form.dayContent" placeholder="例如：背50个单词" />
        </div>

        <div class="form-row">
          <div class="form-group half">
            <label>开始日期 <span class="required">*</span></label>
            <input type="date" v-model="form.startDate" />
          </div>
          <div class="form-group half">
            <label>结束日期 <span class="required">*</span></label>
            <input type="date" v-model="form.endDate" />
          </div>
        </div>

        <div class="form-group">
          <label>每周执行日 <span class="required">*</span></label>
          <div class="weekday-picker">
            <label
              v-for="(name, idx) in weekLabels"
              :key="idx"
              class="weekday-chip"
              :class="{ active: form.weekDays.includes(idx + 1) }"
            >
              <input
                type="checkbox"
                :value="idx + 1"
                v-model="form.weekDays"
                hidden
              />
              {{ name }}
            </label>
          </div>
        </div>

        <div class="modal-actions">
          <button class="save-btn" @click="handleSave" :disabled="saving">
            {{ saving ? '保存中...' : '保存' }}
          </button>
          <button class="cancel-btn" @click="closeModal">取消</button>
        </div>
      </div>
    </div>
  </aside>
</template>

<script setup lang="ts">
import {ref, reactive, onMounted, onUnmounted, watch} from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { isLoggedIn,tokenRef } from '../../../utils/auth';
import {
  listTodosAPI,
  createTodoAPI,
  updateTodoAPI,
  deleteTodoAPI,
  type TodoVO,
} from '../../../api/todo';

const props = defineProps<{ isOpen: boolean; selectedTodoId?: number }>();

const emit = defineEmits<{
  (e: 'select', todo: TodoVO): void;
}>();

const weekLabels = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];

const todos = ref<TodoVO[]>([]);
const showModal = ref(false);
const editingTodo = ref<TodoVO | null>(null);
const saving = ref(false);
const openMenuId = ref<number | null>(null);

const form = reactive({
  title: '',
  color: '#5c4b37',
  dayContent: '',
  startDate: '',
  endDate: '',
  weekDays: [] as number[],
});

// 点击其他地方关闭菜单
const toggleMenu = (id: number) => {
  openMenuId.value = openMenuId.value === id ? null : id;
};

// 关闭所有菜单
const closeMenu = () => {
  openMenuId.value = null;
};

const fetchTodos = async () => {
  if (!isLoggedIn()) return; // ★ 新增拦截：未登录不请求
  try {
    todos.value = await listTodosAPI();
  } catch {
    // request 拦截器已弹窗
  }
};

const resetForm = () => {
  form.title = '';
  form.color = '#5c4b37';
  form.dayContent = '';
  form.startDate = '';
  form.endDate = '';
  form.weekDays = [];
};

const openCreateModal = () => {
  if (!isLoggedIn()) {
    ElMessage.warning('请先登录');
    return;
  }
  editingTodo.value = null;
  resetForm();
  showModal.value = true;
};

const openEditModal = (todo: TodoVO) => {
  editingTodo.value = todo;
  form.title = todo.title;
  form.color = todo.color;
  form.dayContent = '';
  form.startDate = todo.startDate;
  form.endDate = todo.endDate;
  form.weekDays = [...todo.weekDays];
  closeMenu();
  showModal.value = true;
};

const closeModal = () => {
  showModal.value = false;
};

const handleSave = async () => {
  if (!form.title.trim()) {
    ElMessage.warning('请输入目标名称');
    return;
  }
  if (!form.startDate) {
    ElMessage.warning('请选择开始日期');
    return;
  }
  if (!form.endDate) {
    ElMessage.warning('请选择结束日期');
    return;
  }
  if (form.weekDays.length === 0) {
    ElMessage.warning('请选择至少一个执行日');
    return;
  }

  saving.value = true;
  try {
    const data = {
      title: form.title.trim(),
      color: form.color,
      dayContent: form.dayContent.trim() || undefined,
      startDate: form.startDate,
      endDate: form.endDate,
      weekDays: form.weekDays,
    };

    if (editingTodo.value) {
      await updateTodoAPI(editingTodo.value.todoId, data);
      ElMessage.success('修改成功');
    } else {
      await createTodoAPI(data);
      ElMessage.success('创建成功');
    }
    closeModal();
    await fetchTodos();
  } catch {
    // request 拦截器已弹窗
  } finally {
    saving.value = false;
  }
};

const handleDelete = async (todoId: number) => {
  closeMenu();
  try {
    await ElMessageBox.confirm('确定要删除该待办吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    });
    await deleteTodoAPI(todoId);
    ElMessage.success('已删除');
    await fetchTodos();
  } catch {
    // 取消或失败
  }
};

const handleOutsideClick = () => {
  openMenuId.value = null;
};

onMounted(() => {
  fetchTodos();
  document.addEventListener('click', handleOutsideClick);
});

// ★ 新增：监听登录状态变化
watch(tokenRef, (newVal) => {
  if (newVal) {
    fetchTodos(); // 登录成功，重新拉取待办
  } else {
    todos.value = []; // 退出登录，清空列表
  }
});


onUnmounted(() => {
  document.removeEventListener('click', handleOutsideClick);
});
</script>

<style scoped>
/* 侧边栏基础结构 */
.sidebar {
  width: 20%;
  min-width: 280px;
  max-width: 400px;
  background-color: #fdfae9;
  transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
  display: flex;
  flex-direction: column;
  z-index: 5;
  overflow: hidden;
}

.left-sidebar {
  border-right: 2px solid #d3c4a1;
}

.sidebar-content {
  width: 100%;
  padding: 20px;
  min-width: 280px;
  height: 100%;
  box-sizing: border-box;
  overflow-y: auto;
}

.sidebar.is-collapsed {
  width: 0 !important;
  min-width: 0 !important;
  border: none;
}

/* 头部 */
.todo-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  border-bottom: 2px dashed #d3c4a1;
  padding-bottom: 12px;
}

.todo-header h2 {
  color: #5c4b37;
  font-size: 1.2rem;
  margin: 0;
}

.create-btn {
  padding: 6px 14px;
  background: #5c4b37;
  color: #fdfae9;
  border: none;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.2s;
}

.create-btn:hover {
  background: #4a3c2c;
}

/* 列表 */
.todo-items {
  list-style: none;
  padding: 0;
  margin: 0;
}

.todo-row {
  display: flex;
  align-items: center;
  padding: 10px 0;
  border-bottom: 1px solid #eaddc4;
  gap: 10px;
}

.todo-row:hover {
  background-color: rgba(211, 196, 161, 0.15);
}

/* 颜色圆点 */
.color-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  flex-shrink: 0;
  margin-left: 6px;
}

/* 标题 */
.todo-title {
  flex: 1;
  color: #5c4b37;
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 剩余天数 */
.todo-count {
  flex-shrink: 0;
  min-width: 22px;
  height: 22px;
  line-height: 22px;
  text-align: center;
  background: #eaddc4;
  color: #5c4b37;
  font-size: 12px;
  font-weight: bold;
  border-radius: 11px;
  margin-right: 4px;
}
.todo-count-done {
  background: #c8e6c9;
  color: #2e7d32;
}

/* ··· 操作按钮 */
.actions-wrapper {
  position: relative;
  flex-shrink: 0;
}

.more-btn {
  background: none;
  border: none;
  color: #b5a992;
  font-size: 18px;
  cursor: pointer;
  padding: 2px 6px;
  border-radius: 4px;
  letter-spacing: 2px;
  transition: color 0.2s;
}

.more-btn:hover {
  color: #5c4b37;
}

/* 下拉菜单 */
.action-menu {
  position: absolute;
  right: 0;
  top: 100%;
  margin-top: 4px;
  background: #fdfae9;
  border: 2px solid #d3c4a1;
  border-radius: 6px;
  min-width: 80px;
  box-shadow: 0 4px 12px rgba(92, 75, 55, 0.15);
  z-index: 50;
  overflow: hidden;
}

.menu-item {
  padding: 8px 14px;
  color: #5c4b37;
  font-size: 13px;
  cursor: pointer;
  text-align: center;
  transition: background 0.2s;
}

.menu-item:hover {
  background: #eaddc4;
}

.menu-item-danger {
  color: #bc423f;
  border-top: 1px dashed #d3c4a1;
}

.menu-item-danger:hover {
  background: #bc423f;
  color: white;
}

.empty-tip {
  text-align: center;
  color: #b5a992;
  font-size: 14px;
  margin-top: 40px;
}

/* 弹窗 */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  background-color: rgba(92, 75, 55, 0.5);
  backdrop-filter: blur(3px);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 1000;
}

.modal-card {
  width: 100%;
  max-width: 460px;
  max-height: 90vh;
  overflow-y: auto;
  padding: 32px;
  background: #fdfae9;
  border: 2px solid #d3c4a1;
  border-radius: 12px;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.15);
  box-sizing: border-box;
}

.modal-card h3 {
  color: #5c4b37;
  margin: 0 0 24px 0;
  font-size: 1.3rem;
  text-align: center;
}

/* 表单 */
.form-group {
  margin-bottom: 18px;
}

.form-group label {
  display: block;
  margin-bottom: 6px;
  color: #5c4b37;
  font-size: 13px;
  font-weight: bold;
}

.required {
  color: #bc423f;
}

.form-group input[type="text"],
.form-group input[type="date"] {
  width: 100%;
  padding: 10px 12px;
  background: #fcf9ee;
  border: 1px solid #d3c4a1;
  border-radius: 6px;
  font-size: 14px;
  color: #5c4b37;
  box-sizing: border-box;
  transition: border-color 0.2s;
}

.form-group input:focus {
  border-color: #5c4b37;
  outline: none;
}

.form-row {
  display: flex;
  gap: 14px;
}

.form-row .half {
  flex: 1;
}

/* 颜色选择 */
.color-row {
  display: flex;
  gap: 10px;
  align-items: center;
}

.color-picker {
  width: 36px;
  height: 36px;
  border: 1px solid #d3c4a1;
  border-radius: 4px;
  padding: 2px;
  cursor: pointer;
  background: none;
}

.color-text {
  flex: 1;
}

/* 星期选择 */
.weekday-picker {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.weekday-chip {
  padding: 6px 12px;
  border: 1px solid #d3c4a1;
  border-radius: 20px;
  font-size: 12px;
  color: #5c4b37;
  cursor: pointer;
  transition: all 0.2s;
  user-select: none;
}

.weekday-chip.active {
  background: #5c4b37;
  color: #fdfae9;
  border-color: #5c4b37;
}

/* 弹窗按钮 */
.modal-actions {
  display: flex;
  gap: 12px;
  margin-top: 24px;
}

.save-btn {
  flex: 1;
  padding: 12px;
  background: #5c4b37;
  color: #fdfae9;
  border: none;
  border-radius: 6px;
  font-size: 15px;
  font-weight: bold;
  cursor: pointer;
  transition: background 0.2s;
}

.save-btn:hover:not(:disabled) {
  background: #4a3c2c;
}

.save-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.cancel-btn {
  flex: 1;
  padding: 12px;
  background: transparent;
  color: #5c4b37;
  border: 1px solid #d3c4a1;
  border-radius: 6px;
  font-size: 15px;
  cursor: pointer;
  transition: background 0.2s;
}

.cancel-btn:hover {
  background: #eaddc4;
}
</style>
