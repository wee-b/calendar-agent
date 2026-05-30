import request from '../utils/request';

export interface TodoVO {
  todoId: number;
  title: string;
  color: string;
  startDate: string;
  endDate: string;
  weekDays: number[];
  priority: number;
  status: number;
  voiceText: string;
  dates: string[];
  createTime: string;
  updateTime: string;
}

export interface TodoCreateDTO {
  title: string;
  color?: string;
  dayContent?: string;
  startDate: string;
  endDate: string;
  weekDays: number[];
}

export interface TodoUpdateDTO {
  title: string;
  color?: string;
  dayContent?: string;
  startDate: string;
  endDate: string;
  weekDays: number[];
}

// 查询当前用户所有待办
export const listTodosAPI = (): Promise<TodoVO[]> => {
  return request.get('/todo/list');
};

// 创建待办
export const createTodoAPI = (data: TodoCreateDTO): Promise<TodoVO> => {
  return request.post('/todo', data);
};

// 修改待办
export const updateTodoAPI = (todoId: number, data: TodoUpdateDTO): Promise<TodoVO> => {
  return request.put(`/todo/${todoId}`, data);
};

// 删除待办
export const deleteTodoAPI = (todoId: number): Promise<void> => {
  return request.delete(`/todo/${todoId}`);
};


// ★ 新增：切换任务日期状态请求体
export interface TodoDateToggleDTO {
  todoId: number;
  todoDate: string;
}

// ★ 新增：完成/取消完成某天任务
export const toggleTodoDateStatusAPI = (data: TodoDateToggleDTO): Promise<number> => {
  return request.put('/todo/toggle-date', data);
};
