from dataclasses import replace
from unittest.mock import Mock

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from backend.ai.provider import FakeProvider
from backend.ai.schemas import ExtractionInput, Instruction, Timetable
from backend.ai.service import ExtractionService
from backend.api.main import create_app
from backend.api.models import MemoryRecord
from backend.config import Settings
from backend.database.repository import QuotaExceeded, Repository
from backend.memory.service import FakeMemory, SemanticMemory
from backend.weather.service import WeatherService


@pytest.fixture
def settings(tmp_path):
    return Settings(database_url=f"sqlite:///{tmp_path / 'test.db'}")


@pytest.fixture
def client(settings):
    with TestClient(create_app(settings)) as client:
        client.headers["Authorization"] = "Bearer local-development-only"
        yield client


def test_auth_rejection(client):
    assert client.get("/api/v1/events", headers={"Authorization": "Bearer bad"}).status_code == 401


def test_extraction_cache_and_confirmation(client):
    body = {"text": "Monday lab 14:00", "reference_date": "2026-09-26"}
    first = client.post("/api/v1/ai/timetable/extract", json=body)
    assert first.status_code == 200
    assert first.json()["requires_confirmation"] and first.json()["mock"]
    assert not first.json()["cached"]
    assert client.post("/api/v1/ai/timetable/extract", json=body).json()["cached"]
    assert client.get("/api/v1/events").json() == []


def test_bad_schema():
    with pytest.raises(ValidationError):
        Timetable.model_validate(
            {"events": [{"title": "lab", "day": "Monday", "start_time": "15:00", "end_time": "14:00"}]}
        )
    with pytest.raises(ValidationError):
        ExtractionInput(text="", reference_date="2026-09-26")
    with pytest.raises(ValidationError):
        ExtractionInput(image_base64="not-base64!", reference_date="2026-09-26")


def test_failed_ai_retries_at_most_once(settings):
    provider = Mock(identity="broken", extract=Mock(return_value='{"events":[]}'))
    service = ExtractionService(Repository(settings.database_url), provider, settings)
    with pytest.raises(ValueError):
        service.extract("alice", "timetable", ExtractionInput(text="hello", reference_date="2026-09-26"))
    assert provider.extract.call_count == 2


def test_quota_counts_physical_attempts(settings):
    settings = replace(settings, daily_user_calls=1)
    service = ExtractionService(Repository(settings.database_url), FakeProvider(), settings)
    service.extract("alice", "timetable", ExtractionInput(text="one", reference_date="2026-09-26"))
    with pytest.raises(QuotaExceeded):
        service.extract("alice", "timetable", ExtractionInput(text="two", reference_date="2026-09-26"))


def test_isolation_and_delete(settings):
    memory = FakeMemory(Repository(settings.database_url))
    memory.put("alice", MemoryRecord(id="same", text="bring calculator"))
    memory.put("bob", MemoryRecord(id="same", text="private charger"))
    assert memory.search("alice", "calculator", "", 5)[0]["text"] == "bring calculator"
    memory.delete("alice", "same")
    assert memory.search("alice", "calculator", "", 5) == []
    assert len(memory.search("bob", "charger", "", 5)) == 1


def test_weather_timeout(monkeypatch):
    def timeout(*args, **kwargs):
        raise httpx.ReadTimeout("timeout")

    monkeypatch.setattr(httpx, "get", timeout)
    assert WeatherService().forecast(1.3, 103.8)["available"] is False


def test_weather_hourly_forecast(monkeypatch):
    class FakeResponse:
        def raise_for_status(self):
            pass

        def json(self):
            return {
                "hourly": {
                    "time": [
                        "2026-09-27T14:00",
                        "2026-09-27T15:00",
                    ],
                    "precipitation_probability": [
                        70,
                        80,
                    ],
                    "temperature_2m": [
                        29.5,
                        28.8,
                    ],
                    "weather_code": [
                        61,
                        63,
                    ],
                },
            }

    monkeypatch.setattr(
        httpx,
        "get",
        lambda *args, **kwargs: FakeResponse(),
    )

    result = WeatherService().forecast(1.35, 103.68)

    assert result["available"] is True
    assert result["mock"] is False

    assert result["rain_probability"] == 80

    assert len(result["hourly"]) == 2

    assert result["hourly"][0] == {
        "time": "2026-09-27T14:00Z",
        "rain_probability": 70,
        "temperature": 29.5,
        "weather_code": 61,
    }

    assert result["hourly"][1]["rain_probability"] == 80


def test_mock_weather_is_hourly_and_available(client):
    result = client.get("/api/v1/weather?latitude=1.35&longitude=103.68")
    assert result.status_code == 200
    data = result.json()
    assert data["available"] and data["mock"]
    assert len(data["hourly"]) == 48
    assert data["hourly"][0]["time"].endswith("Z")
    assert client.get("/api/v1/weather?latitude=100&longitude=103").status_code == 422


def test_weather_invalid_provider_response_and_cache():
    provider = Mock()
    provider.forecast.return_value = {
        "mock": False,
        "hourly": [{"time": "2026-09-27T14:00Z", "rain_probability": 61,
                    "temperature": 29.5, "weather_code": 61}],
    }
    service = WeatherService(provider=provider)
    assert service.forecast(1.35, 103.68)["available"]
    assert service.forecast(1.35, 103.68)["available"]
    assert provider.forecast.call_count == 1
    provider.forecast.return_value["hourly"][0]["rain_probability"] = 101
    assert WeatherService(provider=provider).forecast(1.35, 103.68)["available"] is False
    provider.forecast.return_value["hourly"][0]["rain_probability"] = 60.5
    assert WeatherService(provider=provider).forecast(1.35, 103.68)["hourly"][0]["rain_probability"] == 61


def test_event_crud_and_memory(client):
    event = {"id": "lab", "title": "Lab", "start": 30000840, "end": 30000960}
    assert client.post("/api/v1/events", json=event).status_code == 201
    assert len(client.get("/api/v1/events").json()) == 1
    assert client.post("/api/v1/memory", json={"id": "note", "text": "bring charger"}).status_code == 201
    assert len(client.post("/api/v1/memory/search", json={"query": "charger"}).json()["memories"]) == 1
    assert client.delete("/api/v1/memory/note").status_code == 204
    assert client.delete("/api/v1/events/lab").status_code == 204
    assert client.get("/api/v1/events").json() == []


def test_request_limits(client):
    assert client.post("/api/v1/ai/timetable/extract", content=b"x" * 3_000_001).status_code == 413
    assert client.post("/api/v1/memory/search", json={"query": "charger", "limit": 6}).status_code == 422


def test_vector_upsert_scoping_no_reembedding_and_delete(settings):
    import numpy as np
    from qdrant_client import QdrantClient, models

    # Exercise real vector storage/filtering with an explicit test encoder.
    encoder = Mock()
    encoder.encode.return_value = np.array([1.0, 0.0, 0.0])
    memory = SemanticMemory.__new__(SemanticMemory)
    memory.repository = Repository(settings.database_url)
    memory.encoder = encoder
    memory.models = models
    memory.client = QdrantClient(":memory:")
    memory.collection = "test"
    memory.client.create_collection(
        "test", vectors_config=models.VectorParams(size=3, distance=models.Distance.COSINE)
    )
    note = MemoryRecord(id="note", text="Bring a calculator", course="math")
    memory.put("alice", note)
    memory.put("alice", note)
    assert encoder.encode.call_count == 1
    memory.put("bob", MemoryRecord(id="note", text="Bob's private note", course="math"))
    results = memory.search("alice", "what tomorrow", "math", 5)
    assert [item["text"] for item in results] == ["Bring a calculator"]
    assert encoder.encode.call_count == 3  # two memory writes and one query
    memory.delete("alice", "note")
    assert memory.search("alice", "calculator", "", 5) == []
    assert len(memory.search("bob", "private", "", 5)) == 1
    memory.client.close()


def test_timetable_cache_reused_on_another_day(settings):
    provider = Mock(wraps=FakeProvider())
    provider.identity = "fake-v1"
    service = ExtractionService(Repository(settings.database_url), provider, settings)
    service.extract("alice", "timetable", ExtractionInput(text="same image", reference_date="2026-09-26"))
    result = service.extract(
        "alice", "timetable", ExtractionInput(text="same image", reference_date="2026-09-27")
    )
    assert result["cached"] and provider.extract.call_count == 1


def test_global_quota_is_atomic(settings):
    from concurrent.futures import ThreadPoolExecutor

    repo = Repository(settings.database_url)

    def reserve(index):
        try:
            repo.reserve_call(str(index), 5, 3)
            return True
        except QuotaExceeded:
            return False

    with ThreadPoolExecutor(max_workers=6) as pool:
        assert sum(pool.map(reserve, range(10))) == 3


def test_pro_gate_fails_closed_and_caches(settings, monkeypatch):
    from fastapi import HTTPException
    from backend.auth.entitlements import Entitlements

    gate = Entitlements(replace(settings, mode="PRODUCTION", revenuecat_secret="test-secret"))
    get = Mock(return_value=Mock(json=lambda: {"subscriber": {"entitlements": {}}}))
    monkeypatch.setattr(httpx, "get", get)
    with pytest.raises(HTTPException) as error:
        gate.require_pro("alice")
    assert error.value.status_code == 403
    get.return_value = Mock(json=lambda: {"subscriber": {"entitlements": {"pro": {"expires_date": None}}}})
    gate.require_pro("alice")
    gate.require_pro("alice")
    assert get.call_count == 2


def test_valid_instruction_structured_extraction(client):
    # Phase 13: pasted lecturer text returns strict structured Bring/Do information,
    # requires confirmation, and never auto-persists an event.
    body = {
        "text": "Next Wednesday complete Questions 1-8 and bring your scientific calculator.",
        "reference_date": "2026-09-26",
    }
    response = client.post("/api/v1/ai/instruction/extract", json=body)
    assert response.status_code == 200
    payload = response.json()
    assert payload["requires_confirmation"] and payload["mock"] and not payload["cached"]
    data = payload["data"]
    # Structured fields validate against the Instruction schema before reaching the client.
    Instruction.model_validate(data)
    assert data["required_items"] == ["Scientific calculator"]
    assert data["tasks"] and "duration" in data["tasks"][0]
    # Extraction alone must not create any event.
    assert client.get("/api/v1/events").json() == []


def test_invalid_instruction_schema_rejection():
    # A missing required date and an out-of-range task duration must both be rejected,
    # so malformed AI output can never be silently trusted.
    with pytest.raises(ValidationError):
        Instruction.model_validate({"related_event": "Math", "required_items": [], "tasks": []})
    with pytest.raises(ValidationError):
        Instruction.model_validate(
            {
                "related_event": "Math",
                "date": "2026-10-07",
                "required_items": [],
                "tasks": [{"title": "Prep", "duration": 0}],
            }
        )


def test_instruction_extraction_cache(client):
    # Phase 13: identical instruction input is served from the content-hash cache
    # on the second request rather than calling the provider again.
    body = {"text": "Bring the lab manual on Monday.", "reference_date": "2026-09-26"}
    first = client.post("/api/v1/ai/instruction/extract", json=body)
    assert first.status_code == 200 and not first.json()["cached"]
    second = client.post("/api/v1/ai/instruction/extract", json=body)
    assert second.status_code == 200 and second.json()["cached"]
    assert first.json()["data"] == second.json()["data"]


def test_extraction_cache_is_user_scoped(settings):
    # The cache is keyed per authenticated user, so one student's extraction can never
    # be served to another student.
    provider = Mock(wraps=FakeProvider())
    provider.identity = "fake-v1"
    service = ExtractionService(Repository(settings.database_url), provider, settings)
    request = ExtractionInput(text="shared timetable", reference_date="2026-09-26")
    alice_first = service.extract("alice", "timetable", request)
    assert not alice_first["cached"]
    # A different user with identical input must trigger a fresh extraction, not a cache hit.
    bob = service.extract("bob", "timetable", request)
    assert not bob["cached"]
    alice_second = service.extract("alice", "timetable", request)
    assert alice_second["cached"]
    assert provider.extract.call_count == 2  # one per distinct user, no cross-user reuse
