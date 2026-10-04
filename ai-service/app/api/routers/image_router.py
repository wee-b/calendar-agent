"""通过签名地址展示或下载 MinIO 中的原图。"""

from pathlib import PurePosixPath

from fastapi import APIRouter, Path, Query
from fastapi.responses import Response

from app.service.images import ImageStorageService


router = APIRouter(prefix="/images", tags=["图片"])


@router.get("/{object_key:path}")
async def get_image(object_key: str = Path(...), sig: str = Query(...),
                    download: bool = False):
    data, content_type = await ImageStorageService().read(object_key, sig)
    headers = {"Cache-Control": "private, max-age=3600", "X-Content-Type-Options": "nosniff",
               "Content-Disposition": ("attachment" if download else "inline")
               + f'; filename="{PurePosixPath(object_key).name}"'}
    return Response(data, media_type=content_type, headers=headers)
