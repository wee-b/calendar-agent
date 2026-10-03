"""用户可查看和删除自己的长期记忆。"""
from datetime import datetime
from fastapi import APIRouter, Request, HTTPException, Path
from pydantic import BaseModel, ConfigDict
from app.repository.memory import MemoryRepository
from app.core.response.utils import success

router = APIRouter(prefix="/memory", tags=["记忆"])


class MemoryView(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    memory_id: int
    memory_type: str
    content: str
    source: str
    confidence: float
    expire_time: datetime | None


@router.get("")
async def list_memories(request: Request):
    rows = await MemoryRepository().list_active(request.state.user_id)
    return success([MemoryView.model_validate(row) for row in rows])


@router.delete("/{memory_id}")
async def delete_memory(request: Request, memory_id: int = Path(gt=0)):
    if not await MemoryRepository().delete(request.state.user_id, memory_id):
        raise HTTPException(status_code=404, detail="记忆不存在")
    return success()
