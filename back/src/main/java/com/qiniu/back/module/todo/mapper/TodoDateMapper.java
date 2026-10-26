package com.qiniu.back.module.todo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiniu.back.domain.todo.TodoDate;
import com.qiniu.back.domain.dailyNote.vo.ScheduleItemVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface TodoDateMapper extends BaseMapper<TodoDate> {
    int insertBatch(@Param("list") List<TodoDate> list);

    @Select("""
            SELECT t.todo_id AS todoId, t.title, td.todo_date AS date,
                   td.day_content AS dayContent, td.status
            FROM yl_todo_date td
            JOIN yl_todo t ON t.todo_id = td.todo_id
            WHERE t.user_id = #{userId} AND t.deleted_flag = 0
              AND td.todo_date >= #{startDate} AND td.todo_date <= #{endDate}
            ORDER BY td.todo_date, t.todo_id
            """)
    List<ScheduleItemVO> selectScheduleRange(@Param("userId") Long userId,
                                            @Param("startDate") LocalDate startDate,
                                            @Param("endDate") LocalDate endDate);
}
