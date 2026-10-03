package com.qiniu.back.domain.todo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.List;

/** MCP 批量创建业务参数，不接收用户身份或 AI 草稿。 */
@Data
public class TodoBatchCreateDTO {
    @NotNull
    @Size(min = 1, max = 100)
    @Valid
    private List<TodoCreateDTO> todos;
}
