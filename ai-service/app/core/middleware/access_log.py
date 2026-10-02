"""Log request method, path, status and duration without sensitive headers/body."""

import logging
from time import perf_counter

from fastapi import Request


logger = logging.getLogger(__name__)


async def access_log(request: Request, call_next):
    started = perf_counter()
    response = await call_next(request)
    log = logger.error if response.status_code >= 400 else logger.info
    log("%s %s %s %.1fms", request.method, request.url.path,
        response.status_code, (perf_counter() - started) * 1000)
    return response
