from fastapi import APIRouter, File, HTTPException, Path, Request, UploadFile

from app.core.response.utils import success
from app.service.documents import (DocumentService, DocumentUploadService,
                                   DocumentParseService, DocumentManagementService, file_result)

router = APIRouter(prefix="/documents", tags=["知识库"])


@router.get("")
async def list_documents(request: Request):
    files = await DocumentService().repository.list_files(request.state.user_id)
    return success([file_result(item) for item in files])


@router.post("")
async def upload_document(request: Request, file: UploadFile = File(...)):
    if not file.filename:
        raise HTTPException(400, "请选择文件")
    service = DocumentUploadService()
    limit = service.settings.document_max_bytes
    data = await file.read(limit + 1)
    return success(await service.upload(request.state.user_id, file.filename,
                                        data, file.content_type or "application/octet-stream"))


@router.post("/{file_id}/parse")
async def parse_document(request: Request, file_id: int = Path(..., gt=0)):
    return success(await DocumentParseService().parse(request.state.user_id, file_id))


@router.post("/{file_id}/reparse")
async def reparse_document(request: Request, file_id: int = Path(..., gt=0)):
    return success(await DocumentParseService().parse(request.state.user_id, file_id, force=True))


@router.delete("/{file_id}/parse")
async def delete_document_parse(request: Request, file_id: int = Path(..., gt=0)):
    return success(await DocumentManagementService().delete_parse(request.state.user_id, file_id))


@router.delete("/{file_id}")
async def delete_document_source(request: Request, file_id: int = Path(..., gt=0)):
    await DocumentManagementService().delete_source(request.state.user_id, file_id)
    return success()
