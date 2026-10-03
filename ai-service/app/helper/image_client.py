"""异步迁移 Java ImageAgent 的文生图协议与限额错误处理。"""

from urllib.parse import urlsplit

import httpx

from app.core.config.agent.agents import image_config
from app.core.exception.error_code import ErrorCode
from app.core.exception.exceptions import BusinessException


class ImageClient:
    def __init__(self, config=None, client=None):
        self.config = config if config is not None else image_config
        self.client = client

    async def generate(self, plan_json: str, instruction: str) -> str:
        config, model = self.config, self.config.model
        if model.api_type != "image-generation":
            raise BusinessException(ErrorCode.MODEL_PROVIDER_UNSUPPORTED)
        if not model.api_key or not model.base_url or not model.name:
            raise BusinessException(ErrorCode.MODEL_CONFIG_INCOMPLETE)
        payload = {
            "model": model.name, "size": config.size, "watermark": config.watermark,
            "output_format": "jpeg", "response_format": "url", "stream": False,
            "prompt": "请将规划数据制作成清晰易读的中文时间轴或日历示意图。突出目标、日期和任务，"
                      "不添加规划之外的事项。\n规划数据：\n" + plan_json + "\n图片要求：\n" + instruction,
        }
        client = self.client if self.client is not None else httpx.AsyncClient(timeout=config.timeout_seconds)
        try:
            response = await client.post(model.base_url.rstrip("/") + "/images/generations",
                                         headers={"Authorization": f"Bearer {model.api_key}"}, json=payload)
            if not response.is_success:
                try:
                    error = response.json().get("error", {})
                except (ValueError, AttributeError):
                    error = {}
                if response.status_code == 429 or isinstance(error, dict) and error.get("code") == "SetLimitExceeded":
                    raise BusinessException(ErrorCode.IMAGE_LIMIT)
                raise BusinessException(ErrorCode.IMAGE_FAILED)
            try:
                url = response.json()["data"][0]["url"]
                parts = urlsplit(url)
                if not isinstance(url, str) or parts.scheme not in {"http", "https"} or not parts.netloc:
                    raise ValueError("invalid image URL")
                return url.replace("(", "%28").replace(")", "%29").replace("\n", "").replace("\r", "")
            except (ValueError, TypeError, KeyError, IndexError, AttributeError) as exc:
                raise BusinessException(ErrorCode.IMAGE_FAILED) from exc
        except httpx.TimeoutException as exc:
            raise BusinessException(ErrorCode.MODEL_TIMEOUT) from exc
        except httpx.RequestError as exc:
            raise BusinessException(ErrorCode.IMAGE_FAILED) from exc
        finally:
            if self.client is None:
                await client.aclose()
