import request from '../utils/request';

export interface AlmanacDayVO {
  solarDate: string;
  lunarDate: string;
  week: string;
  jianXing: string;
  traditionalYiList: string[];
  traditionalJiList: string[];
  yiList: string[];
  jiList: string[];
  luckyColor: string;
  luckyNum: number;
}

export const getAlmanacDayAPI = (date: string): Promise<AlmanacDayVO> => {
  return request.get('/almanac/day', { params: { date } });
};
