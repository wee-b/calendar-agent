
"""Console logging for the application and Uvicorn."""

import logging


class LevelColorFormatter(logging.Formatter):
    RED = "\x1b[31m"
    WHITE = "\x1b[37m"
    RESET = "\x1b[0m"

    def format(self, record: logging.LogRecord) -> str:
        message = super().format(record)
        color = self.RED if record.levelno >= logging.ERROR else self.WHITE
        return f"{color}{message}{self.RESET}"


def configure_logging() -> None:
    handler = logging.StreamHandler()
    handler.setFormatter(LevelColorFormatter(
        "%(asctime)s %(levelname)s %(name)s %(message)s",
    ))
    # A runner may have installed a WARNING-level root handler before import.
    logging.basicConfig(level=logging.INFO, handlers=[handler], force=True)

    # Uvicorn normally installs separate formatters; route its logs through root.
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        logger = logging.getLogger(name)
        logger.handlers.clear()
        logger.propagate = True

    # The application middleware already writes one access log per response.
    logging.getLogger("uvicorn.access").disabled = True
