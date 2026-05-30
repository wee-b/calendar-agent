import request from '../utils/request';

export interface MonthCountVO {
  date: string;
  count: number;
}

export interface DayTodoItem {
  todoId: number;
  title: string;
  color: string;
  dayContent: string;
  status: number;
}

export interface DayTodosVO {
  todos: DayTodoItem[];
  dailyNote: string;
}

// 查看当月每日待办数量
export const getMonthCountAPI = (year: number, month: number): Promise<MonthCountVO[]> => {
  return request.get('/calendar/month-count', { params: { year, month } });
};

// 查看某天所有待办
export const getDayTodosAPI = (date: string): Promise<DayTodosVO> => {
  return request.get('/calendar/day', { params: { date } });
};
