from dataclasses import dataclass


@dataclass(frozen=True)
class MemoryChange:
    key: str
    content: str
    memory_type: str
    forget: bool = False

