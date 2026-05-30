// 农历数据 1900-2100
// 每个年份用 4 位表示：前 3 位表示农历月信息（大小月），第 4 位表示闰月月份（0=无闰月）

const LUNAR_INFO = [
  0x04bd8,0x04ae0,0x0a570,0x054d5,0x0d260,0x0d950,0x16554,0x056a0,0x09ad0,0x055d2,
  0x04ae0,0x0a5b6,0x0a4d0,0x0d250,0x1d255,0x0b540,0x0d6a0,0x0ada2,0x095b0,0x14977,
  0x04970,0x0a4b0,0x0b4b5,0x06a50,0x06d40,0x1ab54,0x02b60,0x09570,0x052f2,0x04970,
  0x06566,0x0d4a0,0x0ea50,0x06e95,0x05ad0,0x02b60,0x186e3,0x092e0,0x1c8d7,0x0c950,
  0x0d4a0,0x1d8a6,0x0b550,0x056a0,0x1a5b4,0x025d0,0x092d0,0x0d2b2,0x0a950,0x0b557,
  0x06ca0,0x0b550,0x15355,0x04da0,0x0a5b0,0x14573,0x052b0,0x0a9a8,0x0e950,0x06aa0,
  0x0aea6,0x0ab50,0x04b60,0x0aae4,0x0a570,0x05260,0x0f263,0x0d950,0x05b57,0x056a0,
  0x096d0,0x04dd5,0x04ad0,0x0a4d0,0x0d4d4,0x0d250,0x0d558,0x0b540,0x0b6a0,0x195a6,
  0x095b0,0x049b0,0x0a974,0x0a4b0,0x0b27a,0x06a50,0x06d40,0x0af46,0x0ab60,0x09570,
  0x04af5,0x04970,0x064b0,0x074a3,0x0ea50,0x06b58,0x05ac0,0x0ab60,0x096d5,0x092e0,
  0x0c960,0x0d954,0x0d4a0,0x0da50,0x07552,0x056a0,0x0abb7,0x025d0,0x092d0,0x0cab5,
  0x0a950,0x0b4a0,0x0baa4,0x0ad50,0x055d9,0x04ba0,0x0a5b0,0x15176,0x052b0,0x0a930,
  0x07954,0x06aa0,0x0ad50,0x05b52,0x04b60,0x0a6e6,0x0a4e0,0x0d260,0x0ea65,0x0d530,
  0x05aa0,0x076a3,0x096d0,0x04afb,0x04ad0,0x0a4d0,0x1d0b6,0x0d250,0x0d520,0x0dd45,
  0x0b5a0,0x056d0,0x055b2,0x049b0,0x0a577,0x0a4b0,0x0aa50,0x1b255,0x06d20,0x0ada0,
  0x14b63,0x09370,0x049f8,0x04970,0x064b0,0x168a6,0x0ea50,0x06aa0,0x1a6c4,0x0aae0,
  0x092e0,0x0d2e3,0x0c960,0x0d557,0x0d4a0,0x0da50,0x05d55,0x056a0,0x0a6d0,0x055d4,
  0x052d0,0x0a9b8,0x0a950,0x0b4a0,0x0b6a6,0x0ad50,0x055a0,0x0aba4,0x0a5b0,0x052b0,
  0x0b273,0x06930,0x07337,0x06aa0,0x0ad50,0x14b55,0x04b60,0x0a570,0x054e4,0x0d160,
  0x0e968,0x0d520,0x0daa0,0x16aa6,0x056d0,0x04ae0,0x0a9d4,0x0a4d0,0x0d150,0x0f252,
  0x0d520
];

const LUNAR_MONTH_NAMES = ['', '正月','二月','三月','四月','五月','六月','七月','八月','九月','十月','冬月','腊月'];
const LUNAR_DAY_NAMES = [
  '','初一','初二','初三','初四','初五','初六','初七','初八','初九','初十',
  '十一','十二','十三','十四','十五','十六','十七','十八','十九','二十',
  '廿一','廿二','廿三','廿四','廿五','廿六','廿七','廿八','廿九','三十'
];
const SOLAR_TERM_NAMES = [
  '小寒','大寒','立春','雨水','惊蛰','春分','清明','谷雨',
  '立夏','小满','芒种','夏至','小暑','大暑','立秋','处暑',
  '白露','秋分','寒露','霜降','立冬','小雪','大雪','冬至'
];

const SOLAR_TERM_INFO = [
  0,21208,42467,63836,85337,107014,128867,150921,
  173149,195551,218072,240693,263343,285989,308563,331033,
  353350,375494,397447,419210,440795,462224,483532,504758
];

// 公历节日
const SOLAR_FESTIVALS: Record<string, string> = {
  '01-01': '元旦',
  '02-14': '情人节',
  '03-08': '妇女节',
  '03-12': '植树节',
  '04-01': '愚人节',
  '05-01': '劳动节',
  '05-04': '青年节',
  '06-01': '儿童节',
  '07-01': '建党节',
  '08-01': '建军节',
  '09-10': '教师节',
  '10-01': '国庆节',
  '12-25': '圣诞节',
};

// 农历节日
const LUNAR_FESTIVALS: Record<string, string> = {
  '01-01': '春节',
  '01-15': '元宵节',
  '05-05': '端午节',
  '07-07': '七夕',
  '07-15': '中元节',
  '08-15': '中秋节',
  '09-09': '重阳节',
  '12-30': '除夕',
  '12-29': '除夕',
};

export interface LunarInfo {
  lunarMonth: number;
  lunarDay: number;
  isLeapMonth: boolean;
  lunarDate: string;   // 如 "正月十五"
  festival: string;     // 节日名称
  solarTerm: string;    // 节气名称
}

function getLunarYearInfo(year: number): number {
  return LUNAR_INFO[year - 1900];
}

function getLeapMonth(year: number): number {
  return getLunarYearInfo(year) & 0xf;
}

function getLunarYearDays(year: number): number {
  let sum = 348; // 12 * 29
  const info = getLunarYearInfo(year);
  for (let i = 0x8000; i > 0x8; i >>= 1) {
    sum += (info & i) ? 1 : 0;
  }
  return sum + getLeapMonthDays(year);
}

function getLeapMonthDays(year: number): number {
  if (getLeapMonth(year)) {
    return (getLunarYearInfo(year) & 0x10000) ? 30 : 29;
  }
  return 0;
}

function getMonthDays(year: number, month: number): number {
  return (getLunarYearInfo(year) & (0x10000 >> month)) ? 30 : 29;
}

// 将公历日期转为农历
export function solarToLunar(y: number, m: number, d: number): LunarInfo {
  // 计算与1900-01-31（农历1900年正月初一）的天数差
  let offset = 0;
  for (let i = 1900; i < y; i++) {
    offset += getLunarYearDays(i);
  }
  const leapMonth = getLeapMonth(y);
  let isLeap = false;

  for (let i = 1; i < m; i++) {
    offset += getMonthDays(y, i);
  }
  // 加上闰月天数
  if (leapMonth > 0 && m > leapMonth) {
    offset += getLeapMonthDays(y);
  }
  offset += d - 1;

  // 从1900年正月初一开始推算农历日期
  let lunarYear = 1900;
  let yearDays = getLunarYearDays(lunarYear);
  while (lunarYear < 2100 && offset >= yearDays) {
    offset -= yearDays;
    lunarYear++;
    yearDays = getLunarYearDays(lunarYear);
  }

  let lunarMonth = 1;
  const lm = getLeapMonth(lunarYear);
  let isLeapMonth = false;
  let monthDays: number;

  for (let i = 1; i <= 12; i++) {
    monthDays = getMonthDays(lunarYear, i);
    if (offset < monthDays) {
      lunarMonth = i;
      break;
    }
    offset -= monthDays;

    if (i === lm) {
      monthDays = getLeapMonthDays(lunarYear);
      if (offset < monthDays) {
        lunarMonth = i;
        isLeapMonth = true;
        break;
      }
      offset -= monthDays;
    }
  }

  const lunarDay = offset + 1;

  // 节日
  const dateKey = `${String(lunarMonth).padStart(2, '0')}-${String(lunarDay).padStart(2, '0')}`;
  let festival = isLeapMonth ? '' : (LUNAR_FESTIVALS[dateKey] || '');

  // 特殊处理除夕（腊月最后一天）
  if (lunarMonth === 12) {
    const daysIn12 = getMonthDays(lunarYear, 12);
    if (lunarDay === daysIn12 && !festival) {
      festival = '除夕';
    }
  }

  // 节气
  const solarTerm = getSolarTerm(y, m, d);

  // 公历节日
  const solarKey = `${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
  if (!festival && SOLAR_FESTIVALS[solarKey]) {
    festival = SOLAR_FESTIVALS[solarKey];
  }

  return {
    lunarMonth,
    lunarDay,
    isLeapMonth,
    lunarDate: (isLeapMonth ? '闰' : '') + LUNAR_MONTH_NAMES[lunarMonth] + LUNAR_DAY_NAMES[lunarDay],
    festival,
    solarTerm,
  };
}

function getSolarTerm(y: number, m: number, d: number): string {
  const termIdx = (m - 1) * 2;
  const termDay = getSolarTermDay(y, termIdx);
  const nextTermDay = getSolarTermDay(y, termIdx + 1);
  if (d === termDay) return SOLAR_TERM_NAMES[termIdx];
  if (d === nextTermDay) return SOLAR_TERM_NAMES[termIdx + 1];
  return '';
}

function getSolarTermDay(y: number, n: number): number {
  const centuryValue = (y - 1900) * 365.2422 + 6.2 + (y - 1900) * 0.00004;
  const base = Math.floor(centuryValue / 365.2422) * 365 + Math.floor(centuryValue % 365.2422);
  const termDays = SOLAR_TERM_INFO;
  const off = termDays[n] - termDays[0];
  const days = (y - 1900) * 365 + Math.floor((y - 1901) / 4) + off;
  const firstDayOfYear = new Date(y, 0, 1).getDay();
  return Math.floor(days + firstDayOfYear / 24) + 5;
}
