"""CORS setup shared by the application entry point."""

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.core.config import get_settings


def configure_cors(app: FastAPI) -> None:
    origins = get_settings().cors_origins
    if origins:
        app.add_middleware(CORSMiddleware, allow_origins=origins,
                           allow_credentials=True, allow_methods=["*"], allow_headers=["*"])
