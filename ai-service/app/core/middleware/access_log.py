"""Only log failed HTTP requests; successful turns have their own flow summary."""

import logging
from time import perf_counter

from fastapi import Request


logger = logging.getLogger(__name__)


async def access_log(request: Request, call_next):
    started = perf_counter()
    response = await call_next(request)
    if response.status_code >= 400:
        log = logger.error if response.status_code >= 500 else logger.warning
        log("%s %s %s %.1fms", request.method, request.url.path,
            response.status_code, (perf_counter() - started) * 1000)
    return response
