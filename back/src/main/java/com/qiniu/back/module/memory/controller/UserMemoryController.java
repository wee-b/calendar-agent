package com.qiniu.back.module.memory.controller;

import com.qiniu.back.domain.ResponseDTO;
import com.qiniu.back.domain.memory.vo.UserMemoryVO;
import com.qiniu.back.module.memory.service.UserMemoryService;
import com.qiniu.back.util.LoginUserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "用户记忆模块")
@RestController
@RequestMapping("/memory")
public class UserMemoryController {

    @Autowired
    private UserMemoryService userMemoryService;

    @GetMapping
    @Operation(summary = "查询当前用户长期记忆")
    public ResponseDTO<List<UserMemoryVO>> list(@RequestParam(required = false) String type) {
        return ResponseDTO.ok(userMemoryService.listActive(LoginUserContext.getUserId(), type));
    }

    @DeleteMapping("/{memoryId}")
    @Operation(summary = "删除一条长期记忆")
    public ResponseDTO<Void> delete(@PathVariable Long memoryId) {
        userMemoryService.delete(LoginUserContext.getUserId(), memoryId);
        return ResponseDTO.ok();
    }

    @PostMapping("/refresh-behaviors")
    @Operation(summary = "刷新当前用户行为习惯记忆")
    public ResponseDTO<Map<String, Integer>> refreshBehaviors() {
        int count = userMemoryService.refreshBehaviorMemories(LoginUserContext.getUserId());
        return ResponseDTO.ok(Map.of("createdCount", count));
    }
}
