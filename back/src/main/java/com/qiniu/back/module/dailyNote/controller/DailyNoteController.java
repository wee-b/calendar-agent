package com.qiniu.back.module.dailyNote.controller;

import com.qiniu.back.domain.ResponseDTO;
import com.qiniu.back.domain.dailyNote.dto.DailyNoteSaveDTO;
import com.qiniu.back.domain.dailyNote.vo.DayTodosVO;
import com.qiniu.back.domain.dailyNote.vo.MonthCountVO;
import com.qiniu.back.module.dailyNote.service.DailyNoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "日记/日历模块")
@RestController
public class DailyNoteController {

    @Autowired
    private DailyNoteService dailyNoteService;

    @GetMapping("/calendar/month-count")
    @Operation(summary = "获取当月每日待办数量（日历小圆点）")
    public ResponseDTO<List<MonthCountVO>> monthCount(@RequestParam int year, @RequestParam int month) {
        return ResponseDTO.ok(dailyNoteService.getMonthCount(year, month));
    }

    @GetMapping("/calendar/day")
    @Operation(summary = "查看某天的所有待办 + 日记")
    public ResponseDTO<DayTodosVO> dayDetail(@RequestParam String date) {
        return ResponseDTO.ok(dailyNoteService.getDayDetail(date));
    }

    @PutMapping("/daily-note")
    @Operation(summary = "保存/修改某天的日记")
    public ResponseDTO<Void> saveDailyNote(@RequestBody @Valid DailyNoteSaveDTO request) {
        dailyNoteService.saveDailyNote(request);
        return ResponseDTO.ok();
    }
}
