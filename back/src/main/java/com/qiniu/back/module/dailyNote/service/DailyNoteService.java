package com.qiniu.back.module.dailyNote.service;

import com.qiniu.back.domain.dailyNote.dto.DailyNoteSaveDTO;
import com.qiniu.back.domain.dailyNote.vo.DayTodosVO;
import com.qiniu.back.domain.dailyNote.vo.MonthCountVO;

import java.util.List;

public interface DailyNoteService {

    List<MonthCountVO> getMonthCount(int year, int month);

    DayTodosVO getDayDetail(String date);

    void saveDailyNote(DailyNoteSaveDTO request);
}
