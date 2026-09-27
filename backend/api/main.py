import json
import logging
import time
from typing import Annotated

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from backend.ai.direct_model_provider import DirectModelProvider
from backend.ai.provider import FakeProvider
from backend.ai.schemas import ExtractionInput
from backend.ai.service import ExtractionService
from backend.api.models import (
    ErrorResponse,
    EventRecord,
    ForgetRecord,
    MemoryRecord,
    SearchRequest,
    SyncRequest,
)
from backend.auth.authentication import Authentication
from backend.auth.entitlements import Entitlements
from backend.config import Settings
from backend.database.repository import QuotaExceeded, Repository
from backend.memory.service import FakeMemory, SemanticMemory
from backend.weather.service import WeatherService

logger = logging.getLogger("student_memory")


def create_app(settings=None, provider=None, memory_service=None):
    settings = settings or Settings.from_env()
    repo = Repository(settings.database_url)
    authentication = Authentication(settings)
    entitlements = Entitlements(settings)
    provider = provider or (
        DirectModelProvider(settings.api_key, settings.model) if settings.real_ai else FakeProvider()
    )
    extraction = ExtractionService(repo, provider, settings)
    memories = memory_service or (
        FakeMemory(repo)
        if settings.mode == "MOCK" or settings.memory_mode == "lexical"
        else SemanticMemory(repo, settings)
    )
    weather = WeatherService(mock=settings.mode == "MOCK")
    app = FastAPI(
        title="Student Memory Copilot",
        version="0.1.0",
        responses={
            401: {"model": ErrorResponse},
            422: {"model": ErrorResponse},
            429: {"model": ErrorResponse},
            502: {"model": ErrorResponse},
        },
    )
    app.state.repository = repo

    @app.middleware("http")
    async def limits_and_logging(request: Request, call_next):
        started = time.monotonic()
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > 3_000_000:
                return JSONResponse(
                    {"error": "payload_too_large", "message": "Maximum request size is 3 MB"}, status_code=413
                )
        request._body = bytes(body)
        result = await call_next(request)
        logger.info(
            json.dumps(
                {
                    "method": request.method,
                    "status": result.status_code,
                    "elapsed_ms": round((time.monotonic() - started) * 1000),
                }
            )
        )
        return result

    @app.exception_handler(HTTPException)
    async def http_error(_request, error):
        return JSONResponse(
            {"error": "request_failed", "message": str(error.detail)}, status_code=error.status_code
        )

    @app.exception_handler(RequestValidationError)
    async def validation_error(_request, _error):
        return JSONResponse(
            {"error": "invalid_input", "message": "Check the request fields and image format."},
            status_code=422,
        )

    @app.exception_handler(QuotaExceeded)
    async def quota_error(_request, error):
        return JSONResponse({"error": "daily_limit", "message": str(error)}, status_code=429)

    @app.exception_handler(Exception)
    async def service_error(_request, _error):
        return JSONResponse(
            {
                "error": "service_unavailable",
                "message": "Service unavailable. Your offline data is unaffected.",
            },
            status_code=503,
        )

    def current_user(authorization: Annotated[str | None, Header()] = None):
        return authentication.user(authorization)

    user_dependency = Depends(current_user)

    @app.get("/api/v1/health")
    def health():
        return {"ok": True, "mode": settings.mode, "paid_ai": settings.real_ai}

    def extract(kind, body, user):
        entitlements.require_pro(user)
        try:
            return extraction.extract(user, kind, body)
        except (ValueError, httpx.HTTPError):
            raise HTTPException(502, "Extraction unavailable. Please use manual entry.") from None

    @app.post("/api/v1/ai/timetable/extract")
    def timetable(body: ExtractionInput, user: str = user_dependency):
        return extract("timetable", body, user)

    @app.post("/api/v1/ai/instruction/extract")
    def instruction(body: ExtractionInput, user: str = user_dependency):
        return extract("instruction", body, user)

    @app.get("/api/v1/events")
    def events(user: str = user_dependency):
        return repo.list(user, "event")

    @app.post("/api/v1/events", status_code=201)
    def save_event(body: EventRecord, user: str = user_dependency):
        repo.put(user, "event", body.id, body.model_dump_json())
        return body

    @app.put("/api/v1/events/{event_id}")
    def update_event(event_id: str, body: EventRecord, user: str = user_dependency):
        if event_id != body.id:
            raise HTTPException(422, "Event ID mismatch")
        return save_event(body, user)

    @app.delete("/api/v1/events/{event_id}", status_code=204)
    def delete_event(event_id: str, user: str = user_dependency):
        repo.delete(user, "event", event_id)

    @app.post("/api/v1/forget-events", status_code=201)
    def forget(body: ForgetRecord, user: str = user_dependency):
        repo.put(user, "forget", body.id, body.model_dump_json())
        return body

    @app.post("/api/v1/sync")
    def sync(body: SyncRequest, user: str = user_dependency):
        # An explicit upload/upsert operation, not an implicit destructive replacement.
        repo.put_events(user, body.events)
        return {"events": repo.list(user, "event")}

    @app.post("/api/v1/memory", status_code=201)
    def store_memory(body: MemoryRecord, user: str = user_dependency):
        entitlements.require_pro(user)
        memories.put(user, body)
        return {"id": body.id, "mock": settings.mode == "MOCK" or settings.memory_mode == "lexical"}

    @app.post("/api/v1/memory/search")
    def search(body: SearchRequest, user: str = user_dependency):
        entitlements.require_pro(user)
        return {
            "memories": memories.search(user, body.query, body.course, body.limit),
            "mock": settings.mode == "MOCK" or settings.memory_mode == "lexical",
        }

    @app.delete("/api/v1/memory/{memory_id}", status_code=204)
    def delete_memory(memory_id: str, user: str = user_dependency):
        memories.delete(user, memory_id)

    @app.get("/api/v1/weather")
    def forecast(
        latitude: float = Query(ge=-90, le=90),
        longitude: float = Query(ge=-180, le=180),
        user: str = user_dependency,
    ):
        return weather.forecast(latitude, longitude)

    return app
