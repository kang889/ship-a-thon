from typing import Annotated, Any

from pydantic import Field

from backend.ai.schemas import StrictModel


Id = Annotated[str, Field(min_length=1, max_length=128, pattern=r"^[a-zA-Z0-9_-]+$")]


class EventRecord(StrictModel):
    id: Id
    title: str = Field(min_length=1, max_length=200)
    course: str = Field(default="", max_length=100)
    type: str = Field(default="class", max_length=50)
    location: str = Field(default="", max_length=200)
    notes: str = Field(default="", max_length=8000)
    start: int = Field(ge=0)
    end: int = Field(ge=0)
    repeatDays: int = Field(default=0, ge=0, le=7)
    untilDay: int = -1
    items: list[dict[str, Any]] = Field(default_factory=list, max_length=100)
    tasks: list[dict[str, Any]] = Field(default_factory=list, max_length=100)


class MemoryRecord(StrictModel):
    id: Id
    text: str = Field(min_length=1, max_length=8000)
    course: str = Field(default="", max_length=100)
    event_id: str = Field(default="", max_length=128)
    memory_type: str = Field(default="note", max_length=50)
    source: str = Field(default="manual", max_length=50)


class SearchRequest(StrictModel):
    query: str = Field(min_length=1, max_length=2000)
    course: str = Field(default="", max_length=100)
    limit: int = Field(default=5, ge=1, le=5)


class ForgetRecord(StrictModel):
    id: Id
    event_id: Id
    item_id: Id
    occurred_at: int = Field(ge=0)


class SyncRequest(StrictModel):
    events: list[EventRecord] = Field(max_length=500)


class ErrorResponse(StrictModel):
    error: str
    message: str
