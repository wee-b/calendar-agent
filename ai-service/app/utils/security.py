"""Pure helpers for the Java Sa-Token client key contract."""

from app.core.config import get_settings


def token_key(token: str) -> str:
    return f"{get_settings().token_header_name}:client:token:{token}"


def parse_login_id(value: object) -> int | None:
    if not isinstance(value, str) or not value.isdecimal():
        return None
    user_id = int(value)
    return user_id if user_id > 0 else None
